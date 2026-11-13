/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.translate

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.ai.AiClient
import com.movtery.zalithlauncher.ai.AiException
import com.movtery.zalithlauncher.ai.AiSettingsStore
import com.movtery.zalithlauncher.ai.ChatMessage
import com.movtery.zalithlauncher.ai.tools.LauncherPathGuard
import com.movtery.zalithlauncher.ai.tools.ToolRuntime
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.file.calculateFileSha1
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val TAG = "ModTranslator"
private const val BATCH_SIZE = 60
private const val MAX_RETRY_PER_BATCH = 2
private const val MAX_LANG_FILE_BYTES = 2L * 1024 * 1024
private const val CACHE_VERSION = 1
private const val RESOURCE_PACK_NAME = "Xeno汉化包"

private const val SYSTEM_PROMPT = """你是 Minecraft 模组语言文件翻译器。把用户提供的 JSON 从英文翻译为简体中文。
必须遵守：
1. 只翻译 value；key 必须原样保留，不得增加、删除或改写任何 key。
2. 保留所有占位符及其数量：%s、%d、%1${'$'}s、%2${'$'}d、%% 等，顺序也不能改变。
3. 保留 § 颜色/格式代码及其数量，保留 \n 换行转义。
4. 专有名词使用 Minecraft 官方中文译名（例如 Creeper→苦力怕、Nether→下界、Ender Dragon→末影龙、Redstone→红石）。
5. 如果某个词不需要翻译（如模组名、命令、URL），保留原文。
6. 只输出一个合法 JSON 对象，不要输出 Markdown、代码块、解释或注释。"""

data class TranslationFailure(val file: String, val reason: String)

data class TranslationSummary(
    val totalJars: Int,
    val translated: Int,
    val skippedExisting: Int,
    val skippedOldLang: Int,
    val skippedNoLang: Int,
    val failed: Int,
    val packPath: String,
    val packFormat: Int,
    val packFormatRecognized: Boolean,
    val warnings: List<String>,
    val translatedMods: List<String>,
    val failures: List<TranslationFailure>
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("totalJars", totalJars)
        addProperty("translated", translated)
        addProperty("skippedExisting", skippedExisting)
        addProperty("skippedOldLang", skippedOldLang)
        addProperty("skippedNoLang", skippedNoLang)
        addProperty("failed", failed)
        addProperty("packPath", packPath)
        addProperty("packFormat", packFormat)
        addProperty("packFormatRecognized", packFormatRecognized)
        add("warnings", com.movtery.zalithlauncher.ai.tools.jsonArray(warnings))
        add("translatedMods", com.movtery.zalithlauncher.ai.tools.jsonArray(translatedMods))
        add("failures", com.google.gson.JsonArray().apply {
            failures.forEach { failure ->
                add(JsonObject().apply {
                    addProperty("file", failure.file)
                    addProperty("reason", failure.reason)
                })
            }
        })
    }
}

data class TranslationOutcome(
    val ok: Boolean,
    val message: String,
    val summary: TranslationSummary
)

/** 翻译单个实例的模组并生成资源包。绝不修改原 jar。 */
object ModTranslator {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun translate(version: Version, writeBack: Boolean = false): TranslationOutcome {
        if (writeBack) {
            return TranslationOutcome(
                ok = false,
                message = "当前版本不支持写回 jar，请使用生成资源包。",
                summary = emptySummary()
            )
        }
        val gameDir = version.getGameDir()
        if (!runCatching { LauncherPathGuard.isManaged(gameDir) }.getOrDefault(false)) {
            return TranslationOutcome(
                ok = false,
                message = "实例目录不在启动器管理范围内，已拒绝翻译。",
                summary = emptySummary()
            )
        }
        val modsDir = VersionFolders.MOD.getDir(gameDir)
        if (!modsDir.isDirectory) {
            return TranslationOutcome(
                ok = false,
                message = "实例「${version.getVersionName()}」没有 mods 目录。",
                summary = emptySummary()
            )
        }
        val jars = modsDir.listFiles()
            ?.filter {
                it.isFile &&
                        it.extension.equals("jar", ignoreCase = true) &&
                        !it.name.endsWith(".disabled", ignoreCase = true)
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (jars.isEmpty()) {
            return TranslationOutcome(
                ok = true,
                message = "实例「${version.getVersionName()}」的 mods 目录里没有 .jar 文件。",
                summary = emptySummary(totalJars = 0)
            )
        }

        val provider = AiSettingsStore.activeProvider()
            ?: return failOutcome("还没有配置 AI 服务商，请到「设置 → AI 服务」添加并选择服务商。")
        val apiKey = runCatching { AiSettingsStore.apiKey(provider.id) }.getOrDefault("")
        if (apiKey.isBlank()) {
            return failOutcome("服务商「${provider.name}」还没有配置 API Key，无法翻译。")
        }
        val model = AiSettingsStore.activeModel(provider)
        if (model.isBlank()) {
            return failOutcome("服务商「${provider.name}」还没有选择模型，无法翻译。")
        }
        val client = AiClient(provider, apiKey, model)

        val cacheDir = runCatching {
            File(PathManager.DIR_FILES_PRIVATE, "ai/translate-cache").apply { mkdirs() }
        }.getOrNull() ?: return failOutcome("无法访问启动器私有缓存目录，翻译未开始。")
        if (!runCatching { LauncherPathGuard.isManaged(cacheDir) }.getOrDefault(false)) {
            return failOutcome("翻译缓存目录不在启动器管理范围内，已拒绝写入。")
        }

        val warnings = mutableListOf<String>()
        val failures = mutableListOf<TranslationFailure>()
        val translated = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var skippedExisting = 0
        var skippedOldLang = 0
        var skippedNoLang = 0
        var processed = 0

        try {
            for ((index, jar) in jars.withIndex()) {
                currentCoroutineContext().ensureActive()
                processed = index + 1
                ToolRuntime.emit("正在翻译 $processed/${jars.size}：${jar.name}")
                try {
                    when (val result = translateOneJar(client, jar, cacheDir)) {
                        is JarResult.Success -> {
                            if (translated.containsKey(result.modId)) {
                                failures += TranslationFailure(jar.name, "modid ${result.modId} 与其他模组冲突，已跳过")
                            } else {
                                translated[result.modId] = result.entries
                            }
                        }
                        JarResult.SkipExisting -> skippedExisting++
                        JarResult.SkipOldLang -> skippedOldLang++
                        JarResult.SkipNoLang -> skippedNoLang++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TranslationFatalException) {
                    throw e
                } catch (e: Throwable) {
                    failures += TranslationFailure(
                        jar.name,
                        e.message ?: e::class.java.simpleName
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationFatalException) {
            return failOutcome(e.message ?: "AI 翻译服务配置错误，翻译已中止。", e)
        }

        if (translated.isEmpty()) {
            val summary = TranslationSummary(
                totalJars = jars.size,
                translated = 0,
                skippedExisting = skippedExisting,
                skippedOldLang = skippedOldLang,
                skippedNoLang = skippedNoLang,
                failed = failures.size,
                packPath = "",
                packFormat = 0,
                packFormatRecognized = false,
                warnings = warnings,
                translatedMods = emptyList(),
                failures = failures
            )
            return TranslationOutcome(
                ok = failures.isEmpty(),
                message = if (failures.isEmpty()) {
                    "没有需要翻译的模组（已跳过：已有中文 $skippedExisting、旧版 lang $skippedOldLang、无 lang $skippedNoLang）。"
                } else {
                    "没有成功翻译任何模组；${failures.size} 个失败，详见返回摘要。"
                },
                summary = summary
            )
        }

        val packFormat = packFormatFor(version.getVersionInfo()?.minecraftVersion)
        if (!packFormat.second) {
            warnings += "无法识别实例 Minecraft 版本「${version.getVersionInfo()?.minecraftVersion ?: "未知"}」， " +
                    "资源包 pack_format 已回退为 15，进游戏后若提示不兼容请手动调整。"
        }
        val packDir = File(VersionFolders.RESOURCE_PACK.getDir(gameDir), RESOURCE_PACK_NAME)
        if (!runCatching { LauncherPathGuard.isManaged(packDir) }.getOrDefault(false)) {
            return failOutcome("资源包目录不在启动器管理范围内，翻译结果未写入。")
        }

        try {
            backupExistingPack(version, packDir)
            if (packDir.exists() && !packDir.isDirectory) {
                if (!packDir.delete()) {
                    throw IllegalStateException("无法替换同名资源包文件：${packDir.absolutePath}")
                }
            }
            writeResourcePack(version, packDir, packFormat.first, translated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return TranslationOutcome(
                ok = false,
                message = "写入资源包失败：${e.message ?: e::class.java.simpleName}",
                summary = TranslationSummary(
                    totalJars = jars.size,
                    translated = 0,
                    skippedExisting = skippedExisting,
                    skippedOldLang = skippedOldLang,
                    skippedNoLang = skippedNoLang,
                    failed = failures.size,
                    packPath = "",
                    packFormat = packFormat.first,
                    packFormatRecognized = packFormat.second,
                    warnings = warnings,
                    translatedMods = emptyList(),
                    failures = failures
                )
            )
        }

        val summary = TranslationSummary(
            totalJars = jars.size,
            translated = translated.size,
            skippedExisting = skippedExisting,
            skippedOldLang = skippedOldLang,
            skippedNoLang = skippedNoLang,
            failed = failures.size,
            packPath = packDir.absolutePath,
            packFormat = packFormat.first,
            packFormatRecognized = packFormat.second,
            warnings = warnings,
            translatedMods = translated.keys.toList(),
            failures = failures
        )
        val message = buildString {
            append("成功翻译 ${translated.size} 个模组")
            if (failures.isNotEmpty()) append("，${failures.size} 个失败")
            append("；资源包已生成：${packDir.absolutePath}")
            if (!packFormat.second) append("（pack_format 已回退为 15）")
        }
        return TranslationOutcome(ok = true, message = message, summary = summary)
    }

    // ------------------------------------------------------------------ single jar

    private sealed interface JarResult {
        data class Success(val modId: String, val entries: LinkedHashMap<String, String>) : JarResult
        data object SkipExisting : JarResult
        data object SkipOldLang : JarResult
        data object SkipNoLang : JarResult
    }

    private suspend fun translateOneJar(
        client: AiClient,
        jar: File,
        cacheDir: File
    ): JarResult {
        ZipFile(jar).use { zip ->
            val metadataId = runCatching { readMetadataModId(zip) }.getOrNull()
            val assets = findLanguageEntries(zip)
            var modId = metadataId?.takeIf { isValidModId(it) }
            var enUsEntry = modId?.let { zip.getEntry("assets/$it/lang/en_us.json") }

            if (enUsEntry == null) {
                val candidates = assets.filter { it.first != modId }
                if (candidates.size == 1) {
                    val candidate = candidates.first()
                    if (modId != null) {
                        //元数据声明的 modid 与 jar 内实际 lang 路径不一致，以实际路径为准
                    }
                    modId = candidate.first
                    enUsEntry = zip.getEntry(candidate.second)
                } else if (candidates.size > 1) {
                    throw TranslationFatalException(
                        "jar ${jar.name} 内存在多个 assets/*/lang/en_us.json，无法确定 modid"
                    )
                }
            }

            if (modId == null) {
                if (findOldLangModIds(zip).isNotEmpty()) return JarResult.SkipOldLang
                return JarResult.SkipNoLang
            }
            if (zip.getEntry("assets/$modId/lang/zh_cn.json") != null ||
                zip.getEntry("assets/$modId/lang/zh_CN.json") != null
            ) {
                return JarResult.SkipExisting
            }
            if (enUsEntry == null) {
                if (zip.getEntry("assets/$modId/lang/en_US.lang") != null ||
                    zip.getEntry("assets/$modId/lang/en_us.lang") != null
                ) {
                    return JarResult.SkipOldLang
                }
                return JarResult.SkipNoLang
            }
            if (enUsEntry.size > MAX_LANG_FILE_BYTES) {
                throw TranslationFatalException("${jar.name} 的 en_us.json 过大（${enUsEntry.size} 字节），已跳过")
            }

            val sourceText = readEntryText(zip, enUsEntry).removePrefix("\uFEFF")
            val sourceObject = runCatching { JsonParser.parseString(sourceText).asJsonObject }
                .getOrElse { e ->
                    throw IllegalArgumentException("解析 ${jar.name} 的 en_us.json 失败：${e.message}")
                }
            val sourceEntries = LinkedHashMap<String, String>()
            sourceObject.entrySet().forEach { (key, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    sourceEntries[key] = value.asString
                }
            }
            if (sourceEntries.isEmpty()) return JarResult.SkipNoLang

            val jarSha1 = calculateFileSha1(jar)
            val cached = readCache(cacheDir, jarSha1, modId, sourceEntries)
            if (cached != null) {
                ToolRuntime.emit("命中翻译缓存：${jar.name}")
                return JarResult.Success(modId, cached)
            }

            val translated = translateEntries(client, jar.name, sourceEntries)
            writeCache(cacheDir, jarSha1, modId, translated)
            return JarResult.Success(modId, translated)
        }
    }

    private suspend fun translateEntries(
        client: AiClient,
        jarName: String,
        source: LinkedHashMap<String, String>
    ): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val chunks = source.entries.chunked(BATCH_SIZE)
        chunks.forEachIndexed { chunkIndex, chunk ->
            currentCoroutineContext().ensureActive()
            ToolRuntime.emit(
                "正在翻译 $jarName（第 ${chunkIndex + 1}/${chunks.size} 批，${chunk.size} 条）"
            )
            val request = LinkedHashMap<String, String>()
            chunk.forEach { (key, value) -> request[key] = value }
            val requestJson = JsonObject().apply {
                request.forEach { (key, value) -> addProperty(key, value) }
            }
            val messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = gson.toJson(requestJson))
            )

            var lastError: String? = null
            var completed = false
            for (attempt in 0..MAX_RETRY_PER_BATCH) {
                currentCoroutineContext().ensureActive()
                val content = try {
                    client.complete(messages, maxTokens = 8192).content
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    when (e.kind) {
                        AiException.Kind.AUTH,
                        AiException.Kind.BAD_REQUEST,
                        AiException.Kind.MODEL_NOT_FOUND -> throw TranslationFatalException(
                            "AI 服务配置错误：${e.message ?: "请求被拒绝"}"
                        )
                        AiException.Kind.CANCELLED -> throw CancellationException(
                            e.message ?: "翻译已取消"
                        )
                        else -> {
                            lastError = e.message ?: e.kind.name
                            if (attempt < MAX_RETRY_PER_BATCH) delay(500L * (attempt + 1))
                            continue
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e.message ?: e::class.java.simpleName
                    if (attempt < MAX_RETRY_PER_BATCH) delay(500L * (attempt + 1))
                    continue
                }

                val parsed = try {
                    parseTranslation(content)
                } catch (e: Throwable) {
                    lastError = e.message ?: "AI 返回的 JSON 不合法"
                    if (attempt < MAX_RETRY_PER_BATCH) delay(300L * (attempt + 1))
                    continue
                }
                val error = validateTranslation(parsed, request)
                if (error == null) {
                    parsed.forEach { (key, value) -> result[key] = value }
                    completed = true
                    break
                }
                lastError = error
                if (attempt < MAX_RETRY_PER_BATCH) delay(300L * (attempt + 1))
            }

            if (!completed) {
                throw IllegalArgumentException(
                    "批次 ${chunkIndex + 1}/${chunks.size} 连续 ${MAX_RETRY_PER_BATCH + 1} 次输出不合格：" +
                            (lastError ?: "未知原因")
                )
            }
        }

        val missing = source.keys - result.keys
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("翻译结果缺少 ${missing.size} 个 key（示例：${missing.take(3).joinToString()}）")
        }
        val extra = result.keys - source.keys
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("翻译结果多出 ${extra.size} 个 key（示例：${extra.take(3).joinToString()}）")
        }
        return result
    }

    // ------------------------------------------------------------------ validation

    private fun parseTranslation(content: String): LinkedHashMap<String, String> {
        if (content.isBlank()) throw IllegalArgumentException("AI 返回为空")
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalArgumentException("AI 返回中找不到 JSON 对象")
        val json = content.substring(start, end + 1)
        val element = runCatching { JsonParser.parseString(json) }.getOrElse { e ->
            throw IllegalArgumentException("AI 返回的 JSON 无法解析：${e.message}")
        }
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("AI 返回的不是 JSON 对象")
        val result = LinkedHashMap<String, String>()
        obj.entrySet().forEach { (key, value) ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                throw IllegalArgumentException("key「$key」的翻译不是字符串")
            }
            result[key] = value.asString
        }
        return result
    }

    /**
     * @return 错误说明；通过校验时返回 null
     */
    private fun validateTranslation(
        translated: Map<String, String>,
        source: Map<String, String>
    ): String? {
        if (translated.keys != source.keys) {
            val missing = source.keys - translated.keys
            val extra = translated.keys - source.keys
            return "key 集合不一致（缺少 ${missing.size}，多出 ${extra.size}）"
        }
        source.forEach { (key, sourceValue) ->
            val targetValue = translated[key] ?: return "缺少 key「$key」"
            val sourceTokens = placeholderCounts(sourceValue)
            val targetTokens = placeholderCounts(targetValue)
            if (sourceTokens != targetTokens) {
                return "key「$key」占位符数量不一致：原文 $sourceTokens，译文 $targetTokens"
            }
            if (sourceValue.count { it == '§' } != targetValue.count { it == '§' }) {
                return "key「$key」的 § 颜色代码数量不一致"
            }
            if (sourceValue.count { it == '\n' } != targetValue.count { it == '\n' }) {
                return "key「$key」的换行数量不一致"
            }
        }
        return null
    }

    private val PLACEHOLDER = Regex("""%(?:\d+\${'$'})?[sdf]|%%""")

    private fun placeholderCounts(text: String): Map<String, Int> =
        PLACEHOLDER.findAll(text).groupingBy { it.value }.eachCount()

    // ------------------------------------------------------------------ cache

    private fun cacheFile(cacheDir: File, sha1: String): File = File(cacheDir, "$sha1.json")

    private fun readCache(
        cacheDir: File,
        sha1: String,
        modId: String,
        source: Map<String, String>
    ): LinkedHashMap<String, String>? {
        val file = cacheFile(cacheDir, sha1)
        if (!file.isFile || file.length() > 4L * 1024 * 1024) return null
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.get("version")?.asInt != CACHE_VERSION) return null
            if (root.get("modId")?.asString != modId) return null
            if (root.get("sourceSha1")?.asString != sha1) return null
            val translations = root.getAsJsonObject("translations") ?: return null
            val result = LinkedHashMap<String, String>()
            translations.entrySet().forEach { (key, value) ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
                result[key] = value.asString
            }
            if (validateTranslation(result, source) != null) return null
            result
        }.getOrNull()
    }

    private fun writeCache(
        cacheDir: File,
        sha1: String,
        modId: String,
        translations: Map<String, String>
    ) {
        runCatching {
            val root = JsonObject().apply {
                addProperty("version", CACHE_VERSION)
                addProperty("modId", modId)
                addProperty("sourceSha1", sha1)
                add("translations", JsonObject().apply {
                    translations.forEach { (key, value) -> addProperty(key, value) }
                })
            }
            writeAtomic(cacheFile(cacheDir, sha1), gson.toJson(root))
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to write translation cache for $modId.", e)
        }
    }

    // ------------------------------------------------------------------ metadata

    private fun readMetadataModId(zip: ZipFile): String? {
        zip.getEntry("fabric.mod.json")?.let { entry ->
            val obj = runCatching {
                JsonParser.parseString(readEntryText(zip, entry).removePrefix("\uFEFF")).asJsonObject
            }.getOrNull()
            obj?.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { isValidModId(it) }?.let { return it }
        }
        zip.getEntry("quilt.mod.json")?.let { entry ->
            val obj = runCatching {
                JsonParser.parseString(readEntryText(zip, entry).removePrefix("\uFEFF")).asJsonObject
            }.getOrNull()
            obj?.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { isValidModId(it) }?.let { return it }
            obj?.getAsJsonObject("quilt_loader")?.get("id")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { isValidModId(it) }?.let { return it }
        }
        listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml").forEach { path ->
            zip.getEntry(path)?.let { entry ->
                val text = readEntryText(zip, entry)
                val id = Regex("""(?m)^\s*modId\s*=\s*["']([^"']+)["']""")
                    .find(text)?.groupValues?.getOrNull(1)
                if (!id.isNullOrBlank() && isValidModId(id)) return id
            }
        }
        return null
    }

    /** @return (modid, en_us.json entry path) */
    private fun findLanguageEntries(zip: ZipFile): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        zip.entries().asSequence().forEach { entry ->
            if (entry.isDirectory) return@forEach
            val match = Regex("""assets/([^/]+)/lang/en_us\.json""").matchEntire(entry.name)
                ?: return@forEach
            val modId = match.groupValues[1]
            if (isValidModId(modId)) result.add(modId to entry.name)
        }
        return result
    }

    private fun hasOldLang(zip: ZipFile, assets: List<Pair<String, String>>): Boolean {
        assets.forEach { (modId, _) ->
            if (zip.getEntry("assets/$modId/lang/en_US.lang") != null ||
                zip.getEntry("assets/$modId/lang/en_us.lang") != null
            ) return true
        }
        return false
    }

    private fun findOldLangModIds(zip: ZipFile): List<String> {
        val result = mutableListOf<String>()
        zip.entries().asSequence().forEach { entry ->
            if (entry.isDirectory) return@forEach
            val match = Regex("""assets/([^/]+)/lang/en_us\.lang""", RegexOption.IGNORE_CASE)
                .matchEntire(entry.name) ?: return@forEach
            val modId = match.groupValues[1]
            if (isValidModId(modId)) result.add(modId)
        }
        return result.distinct()
    }

    private fun isValidModId(modId: String): Boolean =
        modId.isNotBlank() &&
                modId.length <= 128 &&
                Regex("""^[A-Za-z0-9_.-]+$""").matches(modId) &&
                !modId.contains("..")

    private fun readEntryText(zip: ZipFile, entry: ZipEntry): String =
        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            val builder = StringBuilder()
            var total = 0L
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_LANG_FILE_BYTES) {
                    throw IllegalArgumentException("jar 内文件 ${entry.name} 超过大小限制")
                }
                builder.append(buffer, 0, read)
            }
            builder.toString()
        }

    // ------------------------------------------------------------------ resource pack

    private fun backupExistingPack(version: Version, packDir: File) {
        if (!packDir.exists()) return
        val backupRoot = File(version.getVersionPath(), "XingLan/ai-backup/${timestamp()}")
        val backupDir = File(backupRoot, "resourcepacks/$RESOURCE_PACK_NAME")
        if (!runCatching { LauncherPathGuard.canModify(backupDir, backupRoot) }.getOrDefault(false)) {
            throw IllegalStateException("备份路径未通过启动器路径校验：${backupDir.absolutePath}")
        }
        backupDir.parentFile?.mkdirs()
        if (!runCatching { packDir.copyRecursively(backupDir, overwrite = true) }.getOrDefault(false)) {
            throw IllegalStateException("备份现有资源包失败：${packDir.absolutePath}")
        }
        ToolRuntime.emit("已备份现有资源包到 ${backupDir.absolutePath}")
    }

    private fun writeResourcePack(
        version: Version,
        packDir: File,
        packFormat: Int,
        translations: Map<String, LinkedHashMap<String, String>>
    ) {
        if (!packDir.exists() && !packDir.mkdirs()) {
            throw IllegalStateException("无法创建资源包目录：${packDir.absolutePath}")
        }
        if (!runCatching { LauncherPathGuard.canModify(packDir, version.getGameDir()) }.getOrDefault(false)) {
            throw IllegalStateException("资源包目录未通过启动器路径校验：${packDir.absolutePath}")
        }

        val mcMeta = JsonObject().apply {
            add("pack", JsonObject().apply {
                addProperty("pack_format", packFormat)
                addProperty("description", "Xeno汉化包（AI 翻译，可能不完整；请遵守模组许可证）")
            })
        }
        val metaFile = LauncherPathGuard.resolveInside(packDir, "pack.mcmeta")
            ?: throw IllegalStateException("pack.mcmeta 路径不安全")
        if (!LauncherPathGuard.canModify(metaFile, version.getGameDir())) {
            throw IllegalStateException("pack.mcmeta 未通过路径校验")
        }
        writeAtomic(metaFile, gson.toJson(mcMeta))

        translations.forEach { (modId, entries) ->
            val relative = "assets/$modId/lang/zh_cn.json"
            val target = LauncherPathGuard.resolveInside(packDir, relative)
                ?: throw IllegalStateException("语言文件路径不安全：$relative")
            if (!LauncherPathGuard.canModify(target, version.getGameDir())) {
                throw IllegalStateException("语言文件未通过路径校验：${target.absolutePath}")
            }
            val json = JsonObject().apply {
                entries.forEach { (key, value) -> addProperty(key, value) }
            }
            writeAtomic(target, gson.toJson(json))
        }
    }

    private fun packFormatFor(minecraftVersion: String?): Pair<Int, Boolean> {
        val version = minecraftVersion?.trim().orEmpty()
        return when (version) {
            "1.20.1" -> 15 to true
            "1.20.4" -> 22 to true
            "1.21" -> 34 to true
            "1.21.4" -> 46 to true
            else -> 15 to false
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun writeAtomic(file: File, text: String) {
        val parent = file.parentFile ?: throw IllegalStateException("Missing parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Cannot create directory: ${parent.absolutePath}")
        }
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    private fun timestamp(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

    private fun emptySummary(totalJars: Int = 0): TranslationSummary = TranslationSummary(
        totalJars = totalJars,
        translated = 0,
        skippedExisting = 0,
        skippedOldLang = 0,
        skippedNoLang = 0,
        failed = 0,
        packPath = "",
        packFormat = 0,
        packFormatRecognized = false,
        warnings = emptyList(),
        translatedMods = emptyList(),
        failures = emptyList()
    )

    private fun failOutcome(message: String, cause: Throwable? = null): TranslationOutcome {
        cause?.let { Logger.warning(TAG, message, it) }
        return TranslationOutcome(false, message, emptySummary())
    }
}

private class TranslationFatalException(message: String) : Exception(message)
