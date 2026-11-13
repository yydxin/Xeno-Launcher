package com.movtery.zalithlauncher.game.renderer.mobileglues

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File-only diagnostics: safe before MMKV/launcher logging initialization. */
object MobileGluesConfigDebugLog {
    const val FILE_NAME_MARKER = "config-debug.enabled"
    const val FILE_NAME_LOG = "config-debug.log"
    private const val MAX_LOG_BYTES = 1024L * 1024L
    private val lock = Any()
    private val mappingLogExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
        Thread(work, "MG-config-mapping-log").apply { isDaemon = true }
    }

    val logFile: File get() = File(MobileGluesConfig.configDir, FILE_NAME_LOG)

    fun isEnabled(directory: File = MobileGluesConfig.configDir): Boolean = try {
        File(directory, FILE_NAME_MARKER).isFile
    } catch (e: Exception) {
        error(directory, "debug marker read failed", e)
        false
    }

    /** The marker survives process restarts; disabling never removes existing logs. */
    fun setEnabled(enabled: Boolean, directory: File = MobileGluesConfig.configDir): Boolean = synchronized(lock) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
            val marker = File(directory, FILE_NAME_MARKER)
            if (enabled) {
                if (!marker.isFile && !marker.createNewFile()) throw IOException("Cannot create $marker")
            } else if (marker.exists() && !marker.delete()) {
                throw IOException("Cannot remove $marker")
            }
            append(directory, "INFO debug enabled=$enabled", null)
            true
        } catch (e: Exception) {
            error(directory, "debug switch failed enabled=$enabled", e)
            false
        }
    }

    fun debug(directory: File, message: String) {
        if (isEnabled(directory)) append(directory, "DEBUG $message", null)
    }

    /** Errors are recorded even when verbose diagnostics are disabled. */
    fun error(directory: File, message: String, throwable: Throwable? = null) {
        append(directory, "ERROR $message", throwable)
    }

    fun valueToSlider(value: Int, directory: File = MobileGluesConfig.configDir): Float {
        val slider = MobileGluesGlslCachePolicy.valueToSlider(value)
        mappingLogExecutor.execute {
            debug(directory, "value->slider raw=$value normalized=${MobileGluesGlslCachePolicy.normalize(value)} slider=$slider")
        }
        return slider
    }

    fun sliderToValue(slider: Float, directory: File = MobileGluesConfig.configDir): Int = try {
        MobileGluesGlslCachePolicy.sliderToValue(slider).also {
            val converted = it
            mappingLogExecutor.execute { debug(directory, "slider->value slider=$slider normalized=$converted") }
        }
    } catch (e: Exception) {
        error(directory, "slider->value failed slider=$slider", e)
        throw e
    }

    /** Call on an IO thread before exporting, never from composition or drag callbacks. */
    fun flushPendingMappings() {
        mappingLogExecutor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Stable, bounded snapshot for Android's share sheet; the live file can keep growing. */
    fun exportSnapshot(destination: File): File {
        flushPendingMappings()
        synchronized(lock) {
            require(logFile.isFile) { "No configuration debug log yet" }
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                File(logFile.parentFile, "$FILE_NAME_LOG.previous").takeIf { it.isFile }?.inputStream()?.use { it.copyTo(output) }
                logFile.inputStream().use { it.copyTo(output) }
            }
        }
        return destination
    }

    /** Historical native evidence, not a claim that a new process has consumed the current file. */
    fun auditRendererSnapshot(config: MobileGluesConfig, directory: File = MobileGluesConfig.configDir) {
        if (!isEnabled(directory)) return
        val rendererLog = File(directory, MobileGluesConfig.FILE_NAME_LOG)
        try {
            val lastRead = if (rendererLog.isFile) java.io.RandomAccessFile(rendererLog, "r").use { file ->
                file.seek((file.length() - MAX_LOG_BYTES).coerceAtLeast(0))
                val tail = ByteArray((file.length() - file.filePointer).toInt())
                file.readFully(tail)
                Regex("Setting:\\s*maxGlslCacheSize\\s*=\\s*(-?\\d+)")
                    .findAll(tail.toString(Charsets.UTF_8)).lastOrNull()?.groupValues?.get(1)
            } else null
            debug(directory, "audit MG_DIR_PATH=${directory.absolutePath} expectedFile=${File(directory, MobileGluesConfig.FILE_NAME_CONFIG).absolutePath} " +
                "uiAndFileMB=${config.maxGlslCacheSize} lastNativeLogMB=${lastRead ?: "unavailable"} " +
                "nativeLogMtime=${rendererLog.lastModified()} configMtime=${File(directory, MobileGluesConfig.FILE_NAME_CONFIG).lastModified()}; " +
                "native log is last-run evidence only, not proof current settings were consumed")
        } catch (e: Exception) {
            error(directory, "native log audit failed", e)
        }
    }

    private fun append(directory: File, message: String, throwable: Throwable?) = synchronized(lock) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
            val log = File(directory, FILE_NAME_LOG)
            // Bound disk usage; this is diagnostic history, never the renderer config.
            if (log.length() >= MAX_LOG_BYTES) {
                log.copyTo(File(directory, "$FILE_NAME_LOG.previous"), overwrite = true)
                log.writeText("", Charsets.UTF_8)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(Date())
            log.appendText("$timestamp $message\n${throwable?.stackTraceToString().orEmpty()}", Charsets.UTF_8)
        } catch (logFailure: Exception) {
            // A full/unwritable filesystem cannot be repaired by the logger. Keep a fallback
            // without recursively logging or depending on Android/MMKV initialization.
            System.err.println("MobileGluesConfig: $message; log write failed: $logFailure")
            throwable?.printStackTrace(System.err)
        }
    }
}
