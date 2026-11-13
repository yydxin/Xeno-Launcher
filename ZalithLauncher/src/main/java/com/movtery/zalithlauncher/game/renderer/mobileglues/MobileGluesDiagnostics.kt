/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.renderer.mobileglues

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.movtery.zalithlauncher.game.renderer.renderers.MobileGluesRenderer
import com.movtery.zalithlauncher.path.PathManager
import java.io.File

/**
 * MobileGlues 运行前诊断结果。
 *
 * 这里只读取公开信息（APK 解压出的 native 库目录、系统 ABI/GLES 版本、应用外部目录下的配置与日志），
 * 所有检查都做了异常降级，读取失败时对应字段保持“未知/缺失”，不会向调用方抛异常。
 *
 * @property libraryPath libmobileglues.so 在 nativeLibraryDir 中的绝对路径
 * @property libraryPresent libmobileglues.so 是否存在
 * @property librarySizeBytes 库文件大小，缺失时为 0
 * @property requiredAbi 当前内置库唯一支持的 ABI
 * @property deviceAbis 设备支持的 ABI 列表
 * @property abiSupported 设备是否支持 [requiredAbi]
 * @property glesVersion 系统 GLES 版本，形如 "3.2"；读取失败时为空串
 * @property glesVersionCode ActivityManager 返回的原始 reqGlEsVersion；读取失败时为 0
 * @property glesSupported 系统 GLES 是否至少为 3.0
 * @property configDirectoryPath config.json 所在目录
 * @property configPath config.json 的绝对路径
 * @property configExists config.json 是否存在
 * @property configValid config.json 是否存在且可以被解析
 * @property configError 解析失败时的技术细节（仅用于排查，不直接作为界面文案）
 * @property logPath latest.log 的绝对路径
 * @property logExists latest.log 是否存在
 * @property logSizeBytes latest.log 的大小，缺失时为 0
 * @property logLastModified latest.log 的最后修改时间戳，缺失时为 0
 * @property checkedAt 本次检查的时间戳
 * @property issues 检查中发现的问题，按严重程度排列
 */
data class MobileGluesDiagnostics(
    val libraryPath: String,
    val libraryPresent: Boolean,
    val librarySizeBytes: Long,
    val requiredAbi: String,
    val deviceAbis: List<String>,
    val abiSupported: Boolean,
    val glesVersion: String,
    val glesVersionCode: Int,
    val glesSupported: Boolean,
    val configDirectoryPath: String,
    val configPath: String,
    val configExists: Boolean,
    val configValid: Boolean,
    val configError: String?,
    val logPath: String,
    val logExists: Boolean,
    val logSizeBytes: Long,
    val logLastModified: Long,
    val checkedAt: Long,
    val issues: List<Issue>,
) {
    /**
     * 没有发现任何问题时为 true。
     * 注意：日志缺失只说明游戏尚未用 MG 启动过，不影响运行。
     */
    val isHealthy: Boolean
        get() = issues.isEmpty()

    enum class Issue {
        /** libmobileglues.so 不在 nativeLibraryDir 中（未打进 APK，或 ABI 不匹配） */
        LIBRARY_MISSING,

        /** 设备不支持 arm64-v8a */
        ABI_UNSUPPORTED,

        /** 系统 GLES 读取失败 */
        GLES_UNKNOWN,

        /** 系统 GLES 低于 3.0，MobileGlues 无法工作 */
        GLES_TOO_LOW,

        /** config.json 尚未生成，启动游戏时会自动创建 */
        CONFIG_MISSING,

        /** config.json 存在但不是合法 JSON */
        CONFIG_INVALID,

        /** latest.log 尚未生成，通常表示游戏还没用 MG 启动过 */
        LOG_MISSING,

        /** 诊断过程本身失败（例如 PathManager 尚未初始化） */
        INSPECTION_FAILED,
    }

    companion object {
        /** 当前只随 arm64-v8a 提供预编译库 */
        const val REQUIRED_ABI = "arm64-v8a"

        /**
         * 执行一次诊断；任何异常都会返回一个可读的降级结果。
         */
        fun inspect(context: Context): MobileGluesDiagnostics {
            return try {
                inspectInternal(context)
            } catch (e: Exception) {
                fallback(e)
            }
        }

        private fun inspectInternal(context: Context): MobileGluesDiagnostics {
            val library = File(PathManager.DIR_NATIVE_LIB, MobileGluesRenderer.LIBRARY_NAME)
            val libraryPresent = runCatching { library.isFile }.getOrDefault(false)
            val librarySizeBytes = if (libraryPresent) {
                runCatching { library.length() }.getOrDefault(0L)
            } else {
                0L
            }

            val deviceAbis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
            val abiSupported = deviceAbis.contains(REQUIRED_ABI)

            val glesVersionCode = runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.deviceConfigurationInfo
                    ?.reqGlEsVersion
                    ?: 0
            }.getOrDefault(0)

            //reqGlEsVersion 的高 16 位是主版本，低 16 位是次版本
            val glesMajor = glesVersionCode shr 16
            val glesMinor = glesVersionCode and 0xFFFF
            val glesVersion = if (glesVersionCode > 0) "$glesMajor.$glesMinor" else ""
            val glesSupported = glesVersionCode > 0 && glesMajor >= 3

            val configDirectory = MobileGluesConfig.configDir
            val configFile = MobileGluesConfig.configFile
            val logFile = MobileGluesConfig.logFile

            val configExists = runCatching { configFile.isFile }.getOrDefault(false)
            var configValid = false
            var configError: String? = null
            if (configExists) {
                runCatching { MobileGluesConfig.fromJson(configFile.readText()) }
                    .onSuccess { configValid = true }
                    .onFailure { throwable ->
                        configError = throwable.message ?: throwable.javaClass.simpleName
                    }
            }

            val logExists = runCatching { logFile.isFile }.getOrDefault(false)
            val logSizeBytes = if (logExists) {
                runCatching { logFile.length() }.getOrDefault(0L)
            } else {
                0L
            }
            val logLastModified = if (logExists) {
                runCatching { logFile.lastModified() }.getOrDefault(0L)
            } else {
                0L
            }

            val issues = buildList {
                if (!libraryPresent) add(Issue.LIBRARY_MISSING)
                if (!abiSupported) add(Issue.ABI_UNSUPPORTED)
                if (glesVersionCode == 0) add(Issue.GLES_UNKNOWN) else if (!glesSupported) add(Issue.GLES_TOO_LOW)
                if (!configExists) add(Issue.CONFIG_MISSING) else if (!configValid) add(Issue.CONFIG_INVALID)
                if (!logExists) add(Issue.LOG_MISSING)
            }

            return MobileGluesDiagnostics(
                libraryPath = library.absolutePath,
                libraryPresent = libraryPresent,
                librarySizeBytes = librarySizeBytes,
                requiredAbi = REQUIRED_ABI,
                deviceAbis = deviceAbis,
                abiSupported = abiSupported,
                glesVersion = glesVersion,
                glesVersionCode = glesVersionCode,
                glesSupported = glesSupported,
                configDirectoryPath = configDirectory.absolutePath,
                configPath = configFile.absolutePath,
                configExists = configExists,
                configValid = configValid,
                configError = configError,
                logPath = logFile.absolutePath,
                logExists = logExists,
                logSizeBytes = logSizeBytes,
                logLastModified = logLastModified,
                checkedAt = System.currentTimeMillis(),
                issues = issues,
            )
        }

        /**
         * 诊断本身失败时的兜底结果：不读取任何路径，只报告失败。
         */
        private fun fallback(throwable: Throwable): MobileGluesDiagnostics {
            return MobileGluesDiagnostics(
                libraryPath = "",
                libraryPresent = false,
                librarySizeBytes = 0L,
                requiredAbi = REQUIRED_ABI,
                deviceAbis = emptyList(),
                abiSupported = false,
                glesVersion = "",
                glesVersionCode = 0,
                glesSupported = false,
                configDirectoryPath = "",
                configPath = "",
                configExists = false,
                configValid = false,
                configError = throwable.message ?: throwable.javaClass.simpleName,
                logPath = "",
                logExists = false,
                logSizeBytes = 0L,
                logLastModified = 0L,
                checkedAt = System.currentTimeMillis(),
                issues = listOf(Issue.INSPECTION_FAILED),
            )
        }
    }
}
