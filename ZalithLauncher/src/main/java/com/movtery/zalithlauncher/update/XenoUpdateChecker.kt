package com.movtery.zalithlauncher.update

import android.os.Build
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.ai.arrOrNull
import com.movtery.zalithlauncher.ai.intOrNull
import com.movtery.zalithlauncher.ai.longOrNull
import com.movtery.zalithlauncher.ai.objOrNull
import com.movtery.zalithlauncher.ai.parseJsonObjectOrNull
import com.movtery.zalithlauncher.ai.strOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class XenoAsset(
    val name: String,
    val url: String,
    val size: Long = 0L
)

data class XenoRelease(
    val tag: String,
    val versionName: String,
    val body: String,
    val prerelease: Boolean,
    val publishedAt: String,
    val assets: List<XenoAsset>
)

data class XenoUpdateInfo(
    val currentVersion: String,
    val release: XenoRelease,
    val apk: XenoAsset,
    val checksums: XenoAsset?
)

class XenoUpdateException(
    message: String,
    val kind: Kind = Kind.OTHER,
    val rateLimitReset: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    enum class Kind { NETWORK, RATE_LIMIT, NOT_FOUND, SERVER, PARSE, NO_ASSET, OTHER }
}

data class ChecksumEntry(val sha256: String, val name: String)

/**
 * GitHub Releases 更新检查。
 * 主请求走 API；API 被限流/不可用时回退到 releases/latest 的 302 Location。
 * 不依赖任何上游版本接口。
 */
object XenoUpdateChecker {
    const val OWNER = "yydxin"
    const val REPO = "Xeno-Launcher"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20"
    private const val WEB_LATEST = "https://github.com/$OWNER/$REPO/releases/latest"
    private const val DOWNLOAD_BASE = "https://github.com/$OWNER/$REPO/releases/download"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /**
     * @return null 表示没有可用更新（含仓库还没有 Release）
     */
    fun check(
        currentVersion: String = BuildConfig.VERSION_NAME,
        includePrerelease: Boolean = false,
        mirrorPrefix: String? = null
    ): XenoUpdateInfo? {
        val releases = try {
            fetchReleases(includePrerelease)
        } catch (e: XenoUpdateException) {
            if (e.kind == XenoUpdateException.Kind.NOT_FOUND) return null
            // API 失败时走网页 302 回退
            if (e.kind == XenoUpdateException.Kind.RATE_LIMIT ||
                e.kind == XenoUpdateException.Kind.NETWORK ||
                e.kind == XenoUpdateException.Kind.SERVER
            ) {
                fallbackRelease(includePrerelease, mirrorPrefix)?.let { return it }
            }
            throw e
        }
        val newest = releases.firstOrNull()
            ?: return null
        if (semverCompare(newest.versionName, currentVersion) <= 0) return null
        val asset = chooseAsset(newest.assets)
            ?: throw XenoUpdateException("最新 Release 没有匹配当前设备 ABI 的 APK", XenoUpdateException.Kind.NO_ASSET)
        val sum = newest.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
        return XenoUpdateInfo(
            currentVersion = currentVersion,
            release = newest.copy(assets = newest.assets.map { it.copy(url = prefixUrl(it.url, mirrorPrefix)) }),
            apk = asset.copy(url = prefixUrl(asset.url, mirrorPrefix)),
            checksums = sum?.copy(url = prefixUrl(sum.url, mirrorPrefix))
        )
    }

    private fun fetchReleases(includePrerelease: Boolean): List<XenoRelease> {
        val url = if (includePrerelease) API_LIST else API_LATEST
        val response = execute(url, accept = "application/vnd.github+json")
        if (response.code == 404) throw XenoUpdateException("仓库还没有发布 Release", XenoUpdateException.Kind.NOT_FOUND)
        if (response.code == 403 || response.code == 429) {
            val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()
            throw XenoUpdateException("GitHub API 触发限流，请稍后再试", XenoUpdateException.Kind.RATE_LIMIT, reset)
        }
        if (!response.isSuccessful) {
            throw XenoUpdateException("GitHub API 返回 HTTP ${response.code}", XenoUpdateException.Kind.SERVER)
        }
        val body = response.body?.string().orEmpty()
        return parseReleasesJson(body, includePrerelease)
    }

    /** 纯函数，便于 JVM 测试；缺字段/空数组不会抛异常。 */
    fun parseReleasesJson(body: String, includePrerelease: Boolean): List<XenoRelease> {
        val element = runCatching { com.google.gson.JsonParser.parseString(body) }.getOrNull()
        val array = when {
            element == null || element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject && !includePrerelease ->
                com.google.gson.JsonArray().apply { add(element.asJsonObject) }
            else -> null
        } ?: throw XenoUpdateException("Release 数据无法解析", XenoUpdateException.Kind.PARSE)
        return array.mapNotNull { element ->
            val obj = element.objOrNull() ?: return@mapNotNull null
            if (obj.get("draft")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return@mapNotNull null
            val pre = obj.get("prerelease")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            if (!includePrerelease && pre) return@mapNotNull null
            parseRelease(obj)
        }.sortedWith { a, b -> semverCompare(b.versionName, a.versionName) }
    }

    private fun parseRelease(obj: JsonObject): XenoRelease? {
        val tag = obj.strOrNull("tag_name") ?: return null
        val name = obj.strOrNull("name").orEmpty()
        val version = normalizeVersion(tag).ifBlank { normalizeVersion(name) }
        if (version.isBlank()) return null
        val body = obj.strOrNull("body").orEmpty()
        val pre = obj.get("prerelease")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val published = obj.strOrNull("published_at").orEmpty()
        val assets = obj.arrOrNull("assets")?.mapNotNull { e ->
            val a = e.objOrNull() ?: return@mapNotNull null
            val url = a.strOrNull("browser_download_url") ?: return@mapNotNull null
            XenoAsset(a.strOrNull("name") ?: return@mapNotNull null, url, a.longOrNull("size") ?: 0L)
        }.orEmpty()
        return XenoRelease(tag, version, body, pre, published, assets)
    }

    private fun fallbackRelease(includePrerelease: Boolean, mirrorPrefix: String?): XenoUpdateInfo? {
        val redirectClient = client.newBuilder().followRedirects(false).build()
        val request = Request.Builder()
            .url(WEB_LATEST)
            .header("User-Agent", userAgent())
            .get()
            .build()
        val response = try {
            redirectClient.newCall(request).execute()
        } catch (e: Throwable) {
            throw XenoUpdateException("检查更新失败：${e.message ?: "网络错误"}", XenoUpdateException.Kind.NETWORK, cause = e)
        }
        response.use {
            if (it.code !in 300..399) {
                if (it.code == 404) return null
                throw XenoUpdateException("GitHub 返回 HTTP ${it.code}", XenoUpdateException.Kind.SERVER)
            }
            val location = it.header("Location").orEmpty()
            val tag = location.trimEnd('/').substringAfterLast("/tag/", "")
                .ifBlank { location.trimEnd('/').substringAfterLast("/", "") }
            val version = normalizeVersion(tag)
            if (version.isBlank()) throw XenoUpdateException("无法从 GitHub 跳转解析版本号", XenoUpdateException.Kind.PARSE)
            if (semverCompare(version, BuildConfig.VERSION_NAME) <= 0) return null
            // 页面回退拿不到资产列表，按发布工作流的命名约定拼装
            val apkName = "XenoLauncher-$version-${currentAbi()}.apk"
            val base = "$DOWNLOAD_BASE/$tag"
            val apk = XenoAsset(apkName, prefixUrl("$base/$apkName", mirrorPrefix))
            val sums = XenoAsset("SHA256SUMS.txt", prefixUrl("$base/SHA256SUMS.txt", mirrorPrefix))
            val release = XenoRelease(tag, version, "", false, "", listOf(apk, sums))
            return XenoUpdateInfo(BuildConfig.VERSION_NAME, release, apk, sums)
        }
    }

    private fun execute(url: String, accept: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", userAgent())
            .get()
            .build()
        return try {
            client.newCall(request).execute()
        } catch (e: Throwable) {
            throw XenoUpdateException("检查更新失败：${e.message ?: "网络错误"}", XenoUpdateException.Kind.NETWORK, cause = e)
        }
    }

    fun downloadText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw XenoUpdateException("下载校验文件失败（HTTP ${response.code}）")
                response.body?.string().orEmpty()
            }
        } catch (e: XenoUpdateException) {
            throw e
        } catch (e: Throwable) {
            throw XenoUpdateException("下载校验文件失败：${e.message ?: "网络错误"}", XenoUpdateException.Kind.NETWORK, cause = e)
        }
    }

    private fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull()
        ?.takeIf { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        ?: "arm64-v8a"

    internal fun chooseAsset(assets: List<XenoAsset>, abi: String = currentAbi()): XenoAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val aliases = when (abi) {
            "arm64-v8a" -> listOf("arm64-v8a", "arm64", "aarch64")
            "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "armeabi")
            "x86_64" -> listOf("x86_64", "x86-64")
            else -> listOf(abi)
        }
        return apks.firstOrNull { apk -> aliases.any { apk.name.contains(it, ignoreCase = true) } }
            ?: apks.firstOrNull { apk ->
                val lower = apk.name.lowercase()
                listOf("universal", "all", "release").any { it in lower } &&
                    listOf("arm64", "armeabi", "x86").none { it in lower }
            }
            ?: apks.singleOrNull()
    }

    private fun prefixUrl(url: String, prefix: String?): String {
        val clean = prefix?.trim()?.trimEnd('/').orEmpty()
        if (clean.isEmpty()) return url
        if (!url.startsWith("https://github.com/")) return url
        return "$clean/$url"
    }

    private fun userAgent(): String = "XenoLauncher/${BuildConfig.VERSION_NAME} (Android)"

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore('+')
            .let { it }
            .ifBlank { raw.trim() }

    /** semver 比较：预发布 < 正式版；缺省段按 0。返回 a - b 的符号。 */
    fun semverCompare(a: String, b: String): Int {
        val (aMain, aPre) = splitPre(a.trim().removePrefix("v").removePrefix("V"))
        val (bMain, bPre) = splitPre(b.trim().removePrefix("v").removePrefix("V"))
        val aParts = aMain.split('.').map { it.toIntOrNull() ?: 0 }
        val bParts = bMain.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(aParts.size, bParts.size)
        for (i in 0 until size) {
            val x = aParts.getOrElse(i) { 0 }
            val y = bParts.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        if (aPre == null && bPre == null) return 0
        if (aPre == null) return 1
        if (bPre == null) return -1
        return comparePre(aPre, bPre)
    }

    private fun splitPre(v: String): Pair<String, String?> {
        val idx = v.indexOf('-')
        return if (idx < 0) v to null else v.substring(0, idx) to v.substring(idx + 1)
    }

    private fun comparePre(a: String, b: String): Int {
        val ap = a.split('.')
        val bp = b.split('.')
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrNull(i) ?: return -1
            val y = bp.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val cmp = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun parseChecksums(text: String): List<ChecksumEntry> = text.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val hash = parts[0].removePrefix("*").trim()
        val name = parts[1].removePrefix("*").trim()
        if (hash.length != 64 || hash.any { it !in "0123456789abcdefABCDEF" }) return@mapNotNull null
        ChecksumEntry(hash.lowercase(), name)
    }.toList()

    fun parseRedirectTag(location: String): String? {
        val tag = location.trimEnd('/').substringAfterLast("/tag/", "")
            .ifBlank { location.trimEnd('/').substringAfterLast("/", "") }
        return tag.ifBlank { null }
    }
}
