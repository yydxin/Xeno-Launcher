package com.movtery.zalithlauncher.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.setting.AllSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface XenoUpdateState {
    data object Idle : XenoUpdateState
    data class Checking(val manual: Boolean) : XenoUpdateState
    data class NoUpdate(val manual: Boolean) : XenoUpdateState
    data class Available(val info: XenoUpdateInfo, val manual: Boolean) : XenoUpdateState
    data class Downloading(
        val info: XenoUpdateInfo,
        val received: Long,
        val total: Long
    ) : XenoUpdateState
    data class Error(val message: String, val manual: Boolean) : XenoUpdateState
}

/**
 * 更新状态机。UI 只读 [state]；网络与文件操作都在 IO 线程。
 * 不依赖上游 Zalith-Info / 官网接口。
 */
object XenoUpdateManager {
    var state by mutableStateOf<XenoUpdateState>(XenoUpdateState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun dismiss() {
        job?.cancel()
        job = null
        state = XenoUpdateState.Idle
    }

    fun check(context: Context, manual: Boolean) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        state = XenoUpdateState.Checking(manual)
        job = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    XenoUpdateChecker.check(
                        currentVersion = BuildConfig.VERSION_NAME,
                        includePrerelease = AllSettings.xenoUpdateIncludePrerelease.getValue(),
                        mirrorPrefix = AllSettings.xenoUpdateMirror.getValue()
                    )
                }
                AllSettings.xenoUpdateLastCheck.save(System.currentTimeMillis())
                state = when {
                    info == null -> XenoUpdateState.NoUpdate(manual)
                    info.release.versionName == AllSettings.xenoUpdateIgnoredVersion.getValue() ->
                        XenoUpdateState.NoUpdate(manual)
                    else -> XenoUpdateState.Available(info, manual)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = XenoUpdateState.Error(
                    e.message ?: "检查更新失败：${e.javaClass.simpleName}",
                    manual
                )
            }
        }
    }

    fun ignore(info: XenoUpdateInfo) {
        AllSettings.xenoUpdateIgnoredVersion.save(info.release.versionName)
        state = XenoUpdateState.Idle
    }

    fun downloadAndInstall(context: Context, info: XenoUpdateInfo) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        state = XenoUpdateState.Downloading(info, 0L, info.apk.size)
        job = scope.launch {
            val file = File(appContext.cacheDir, "xeno-update/${info.apk.name}")
            try {
                val expected = withContext(Dispatchers.IO) {
                    val sums = info.checksums
                        ?: throw XenoUpdateException("Release 缺少 SHA256SUMS.txt，拒绝安装")
                    val text = XenoUpdateChecker.downloadText(sums.url)
                    val entry = XenoUpdateChecker.parseChecksums(text)
                        .firstOrNull { it.name == info.apk.name || it.name.endsWith("/${info.apk.name}") }
                        ?: throw XenoUpdateException("SHA256SUMS.txt 中没有 ${info.apk.name}")
                    entry.sha256
                }
                val actual = withContext(Dispatchers.IO) {
                    download(appContext, info, file) { received, total ->
                        scope.launch { state = XenoUpdateState.Downloading(info, received, total) }
                    }
                }
                if (!actual.equals(expected, ignoreCase = true)) {
                    file.delete()
                    throw XenoUpdateException("APK SHA-256 校验失败，已删除下载文件")
                }
                withContext(Dispatchers.IO) { validateApk(appContext, file) }
                withContext(Dispatchers.IO) { install(appContext, file) }
                state = XenoUpdateState.Idle
            } catch (e: CancellationException) {
                file.delete()
                state = XenoUpdateState.Idle
                throw e
            } catch (e: Throwable) {
                file.delete()
                state = XenoUpdateState.Error(e.message ?: "更新失败：${e.javaClass.simpleName}", true)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        job = null
        state = XenoUpdateState.Idle
    }

    private fun download(
        context: Context,
        info: XenoUpdateInfo,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): String {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val request = Request.Builder()
            .url(info.apk.url)
            .header("User-Agent", "XenoLauncher/${BuildConfig.VERSION_NAME} (Android)")
            .get()
            .build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw XenoUpdateException("下载 APK 失败（HTTP ${response.code}）", kind = XenoUpdateException.Kind.NETWORK)
            }
            val body = response.body ?: throw XenoUpdateException("下载响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apk.size
            var received = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                    output.flush()
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateApk(context: Context, file: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: throw XenoUpdateException("无法读取下载的 APK，文件可能损坏")
        if (archive.packageName != BuildConfig.APPLICATION_ID) {
            throw XenoUpdateException("下载的 APK 包名与当前应用不一致，拒绝安装")
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode <= BuildConfig.VERSION_CODE.toLong()) {
            throw XenoUpdateException("下载的 APK 版本不高于当前版本，拒绝安装")
        }
        val current = pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNING_CERTIFICATES)
        val currentHashes = signatureHashes(current)
        val archiveHashes = signatureHashes(archive)
        if (currentHashes.isEmpty() || archiveHashes.isEmpty() || currentHashes.intersect(archiveHashes).isEmpty()) {
            throw XenoUpdateException("下载的 APK 签名与当前应用不一致，拒绝安装")
        }
    }

    private fun signatureHashes(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        return signers.mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    private fun install(context: Context, file: File) {
        val authority = "${context.packageName}.provider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun checkOnStart(context: Context) {
        if (!AllSettings.xenoUpdateAutoCheck.getValue()) return
        check(context, manual = false)
    }
}
