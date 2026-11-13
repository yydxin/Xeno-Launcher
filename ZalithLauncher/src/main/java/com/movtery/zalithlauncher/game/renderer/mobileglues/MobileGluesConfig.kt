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

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.movtery.zalithlauncher.path.PathManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * MobileGlues 2.0.0 `config.json` 的 Kotlin 映射。
 *
 * 注意事项（均来自 MobileGlues 2.0.0 源码，字段名必须逐字一致）：
 * - 只包含 2.0.0 仍然读取的键；`multidrawMode`、`multidrawDisableBackends` 已废弃，不再写入。
 * - `customGLVersion` 的 0 表示 MobileGlues 默认的 4.0；有效值为 32..46，
 *   其中 34..39 会被 MobileGlues 源码夹取为 33。
 * - `maxGlslCacheSize` 的单位是 MB，只有大于 0 时才启用缓存。
 * - 按入口的 5 个 multidraw 顺序键为空时表示“未设置”（沿用全局顺序），
 *   因此保存时会省略这些键，而不是写入空字符串。
 */
data class MobileGluesConfig(
    /** ANGLE 后端策略：0 DisableIfPossible（默认）、1 EnableIfPossible、2 ForceDisable、3 ForceEnable */
    @SerializedName("enableANGLE")
    val enableANGLE: Int = 0,

    /** 错误检查：0 Auto（默认）、1 Disable、2 Level1、3 Level2 */
    @SerializedName("enableNoError")
    val enableNoError: Int = 0,

    /** 是否启用 GL_EXT_compute_shader，0/1 */
    @SerializedName("enableExtComputeShader")
    val enableExtComputeShader: Int = 0,

    /** 是否启用 GL_EXT_disjoint_timer_query，0/1 */
    @SerializedName("enableExtTimerQuery")
    val enableExtTimerQuery: Int = 0,

    /** 是否启用 GL_EXT_direct_state_access，0/1 */
    @SerializedName("enableExtDirectStateAccess")
    val enableExtDirectStateAccess: Int = 0,

    /** ANGLE 深度清除修正：0 Disabled、1 Mode1、2 Mode2 */
    @SerializedName("angleDepthClearFixMode")
    val angleDepthClearFixMode: Int = 0,

    /** 向游戏报告的 GL 版本：0 = 默认（4.0），有效 32..46，34..39 会被夹取为 33 */
    @SerializedName("customGLVersion")
    val customGLVersion: Int = 0,

    /** FSR 1.0：0 Disabled、1 UltraQuality、2 Quality、3 Balanced、4 Performance */
    @SerializedName("fsr1Setting")
    val fsr1Setting: Int = 0,

    /** 隐藏 MG 环境变量的等级：0 Disabled、1 Level1 */
    @SerializedName("hideMGEnvLevel")
    val hideMGEnvLevel: Int = 0,

    /** GLSL 缓存上限，单位 MB；0 表示不启用 */
    @SerializedName("maxGlslCacheSize")
    val maxGlslCacheSize: Int = 0,

    /** 全局 multidraw 后端顺序，逗号分隔，可包含 native */
    @SerializedName("multidrawOrder")
    val multidrawOrder: String = DEFAULT_MULTIDRAW_ORDER,

    /** glMultiDrawArrays 的后端顺序，留空表示沿用全局顺序 */
    @SerializedName("multidrawOrderArrays")
    val multidrawOrderArrays: String = "",

    /** glMultiDrawElements 的后端顺序，留空表示沿用全局顺序 */
    @SerializedName("multidrawOrderElements")
    val multidrawOrderElements: String = "",

    /** glMultiDrawElementsBaseVertex 的后端顺序，留空表示沿用全局顺序 */
    @SerializedName("multidrawOrderElementsBaseVertex")
    val multidrawOrderElementsBaseVertex: String = "",

    /** glMultiDrawArraysIndirect 的后端顺序，留空表示沿用全局顺序 */
    @SerializedName("multidrawOrderArraysIndirect")
    val multidrawOrderArraysIndirect: String = "",

    /** glMultiDrawElementsIndirect 的后端顺序，留空表示沿用全局顺序 */
    @SerializedName("multidrawOrderElementsIndirect")
    val multidrawOrderElementsIndirect: String = "",
) {
    /**
     * 把所有字段收敛到 MobileGlues 2.0.0 认可的范围：
     * 枚举值夹取到合法范围，GLSL 缓存固定夹取到 0..1024 MB，multidraw 文本去除首尾空白。
     */
    fun normalized(): MobileGluesConfig = copy(
        enableANGLE = enableANGLE.coerceIn(0, 3),
        enableNoError = enableNoError.coerceIn(0, 3),
        enableExtComputeShader = enableExtComputeShader.coerceIn(0, 1),
        enableExtTimerQuery = enableExtTimerQuery.coerceIn(0, 1),
        enableExtDirectStateAccess = enableExtDirectStateAccess.coerceIn(0, 1),
        angleDepthClearFixMode = angleDepthClearFixMode.coerceIn(0, 2),
        customGLVersion = normalizeGLVersion(customGLVersion),
        fsr1Setting = fsr1Setting.coerceIn(0, 4),
        hideMGEnvLevel = hideMGEnvLevel.coerceIn(0, 1),
        maxGlslCacheSize = MobileGluesGlslCachePolicy.normalize(maxGlslCacheSize),
        multidrawOrder = multidrawOrder.trim().ifEmpty { DEFAULT_MULTIDRAW_ORDER },
        multidrawOrderArrays = multidrawOrderArrays.trim(),
        multidrawOrderElements = multidrawOrderElements.trim(),
        multidrawOrderElementsBaseVertex = multidrawOrderElementsBaseVertex.trim(),
        multidrawOrderArraysIndirect = multidrawOrderArraysIndirect.trim(),
        multidrawOrderElementsIndirect = multidrawOrderElementsIndirect.trim(),
    )

    companion object {
        private val ioLock = Any()
        // Preserve explicit nulls in unknown fields too; no app/MMKV initialization needed.
        private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        /** Fixed limits in MB. 0 is a valid, disabled cache, not a missing value. */
        const val GLSL_CACHE_MIN_MB = MobileGluesGlslCachePolicy.MIN_MB
        const val GLSL_CACHE_MAX_MB = MobileGluesGlslCachePolicy.MAX_MB

        /** 配置目录名，位于 [PathManager.DIR_FILES_EXTERNAL] 下 */
        const val DIRECTORY_NAME = "mobileglues"

        const val FILE_NAME_CONFIG = "config.json"
        const val FILE_NAME_LOG = "latest.log"
        const val FILE_NAME_STATS = "stats.json"

        /** 保存失败时保留的坏配置副本名 */
        private const val FILE_NAME_BROKEN_CONFIG = "config.json.broken"

        /** MobileGlues 默认的 multidraw 顺序 */
        const val DEFAULT_MULTIDRAW_ORDER = "native"

        /** enableANGLE 取值 */
        const val ANGLE_DISABLE_IF_POSSIBLE = 0
        const val ANGLE_ENABLE_IF_POSSIBLE = 1
        const val ANGLE_FORCE_DISABLE = 2
        const val ANGLE_FORCE_ENABLE = 3

        /** enableNoError 取值 */
        const val NO_ERROR_AUTO = 0
        const val NO_ERROR_DISABLE = 1
        const val NO_ERROR_LEVEL_1 = 2
        const val NO_ERROR_LEVEL_2 = 3

        /** angleDepthClearFixMode 取值 */
        const val DEPTH_CLEAR_DISABLED = 0
        const val DEPTH_CLEAR_MODE_1 = 1
        const val DEPTH_CLEAR_MODE_2 = 2

        /** fsr1Setting 取值 */
        const val FSR_DISABLED = 0
        const val FSR_ULTRA_QUALITY = 1
        const val FSR_QUALITY = 2
        const val FSR_BALANCED = 3
        const val FSR_PERFORMANCE = 4

        /** hideMGEnvLevel 取值 */
        const val HIDE_ENV_DISABLED = 0
        const val HIDE_ENV_LEVEL_1 = 1

        /** customGLVersion 的默认值（MobileGlues 内部按 4.0 处理） */
        const val GL_VERSION_DEFAULT = 0
        const val GL_VERSION_MIN = 32
        const val GL_VERSION_MAX = 46

        /** MobileGlues 读取配置的目录，通过环境变量 MG_DIR_PATH 传给子进程 */
        val configDir: File
            get() = File(PathManager.DIR_FILES_EXTERNAL, DIRECTORY_NAME)

        /** MobileGlues 的 config.json */
        val configFile: File
            get() = File(configDir, FILE_NAME_CONFIG)

        /** MobileGlues 的运行日志 latest.log */
        val logFile: File
            get() = File(configDir, FILE_NAME_LOG)

        /** MobileGlues 的统计文件 stats.json */
        val statsFile: File
            get() = File(configDir, FILE_NAME_STATS)

        /** Read-only check; malformed known fields are not silently replaced with defaults. */
        val isConfigured: Boolean
            get() = try {
                configFile.isFile && runCatching {
                    parseConfig(parseRoot(configFile.readText(Charsets.UTF_8)), strict = true)
                }.isSuccess
            } catch (e: Exception) {
                MobileGluesConfigDebugLog.error(configDir, "configuration check failed", e)
                false
            }

        /** Compatible fallback API. UI editors should use [loadStrict] instead. */
        fun load(): MobileGluesConfig = loadStrict().getOrElse { MobileGluesConfig() }

        /**
         * Missing file = successful defaults. Malformed/unreadable existing file = failure.
         * Only an out-of-range/noncanonical cache field is repaired atomically on read (no user-save notification).
         * Valid/missing configs are read-only. On failure UI must not display/save defaults.
         */
        fun loadStrict(): Result<MobileGluesConfig> = loadStrict(configDir)

        internal fun loadStrict(directory: File): Result<MobileGluesConfig> = synchronized(ioLock) {
            runCatching {
                val file = File(directory, FILE_NAME_CONFIG)
                if (Files.notExists(file.toPath())) {
                    MobileGluesConfigDebugLog.debug(directory, "read missing path=${file.absolutePath}; defaults")
                    MobileGluesConfig()
                } else {
                    val raw = file.readText(Charsets.UTF_8)
                    MobileGluesConfigDebugLog.debug(directory, "read path=${file.absolutePath} raw=$raw")
                    val root = parseRoot(raw)
                    val normalized = parseConfig(root, strict = true).normalized()
                    root.get("maxGlslCacheSize")?.let { cache ->
                        val requested = cache.asString.trim().toBigDecimal()
                        val canonicalInteger = cache.asJsonPrimitive.isNumber &&
                            cache.asString.matches(Regex("0|[1-9][0-9]*"))
                        if (!canonicalInteger || requested < GLSL_CACHE_MIN_MB.toBigDecimal() || requested > GLSL_CACHE_MAX_MB.toBigDecimal()) {
                            // Repair exactly this field; preserve all other keys/values, even unknown ones.
                            val corrected = root.deepCopy().apply { addProperty("maxGlslCacheSize", normalized.maxGlslCacheSize) }
                            var staged: File? = null
                            try {
                                val repairFile = File.createTempFile(".config-repair-", ".tmp", directory)
                                staged = repairFile
                                FileOutputStream(repairFile).use { stream ->
                                    stream.write(GSON.toJson(corrected).toByteArray(Charsets.UTF_8))
                                    stream.fd.sync()
                                }
                                replaceAtomically(repairFile, file)
                                staged = null
                                check(parseRoot(file.readText(Charsets.UTF_8)) == corrected) { "Range repair readback mismatch" }
                                MobileGluesConfigDebugLog.debug(directory, "read cache repair raw=$requested canonicalInput=$canonicalInteger result=${normalized.maxGlslCacheSize}; verified")
                            } finally {
                                staged?.delete()
                            }
                        }
                    }
                    MobileGluesConfigDebugLog.debug(directory, "read normalized=${toJson(normalized)}")
                    normalized
                }
            }.onFailure { MobileGluesConfigDebugLog.error(directory, "read failed path=${File(directory, FILE_NAME_CONFIG).absolutePath}", it) }
        }

        /**
         * Merge unknown keys, fsync a unique same-directory temp, atomically replace,
         * then immediately read and verify the whole JSON and normalized model.
         * No delete-original fallback. Unsupported atomic replacement is a failure.
         * A malformed existing file is never overwritten by a normal UI save.
         */
        fun save(config: MobileGluesConfig): Boolean = save(config, configDir)

        internal fun save(
            config: MobileGluesConfig,
            directory: File,
            atomicReplace: (File, File) -> Unit = ::replaceAtomically
        ): Boolean = synchronized(ioLock) {
            saveLocked(config, directory, allowDamaged = false, atomicReplace = atomicReplace)
        }

        private fun replaceAtomically(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun saveLocked(
            config: MobileGluesConfig,
            directory: File,
            allowDamaged: Boolean,
            atomicReplace: (File, File) -> Unit = ::replaceAtomically
        ): Boolean {
            var temp: File? = null
            return try {
                require(config.maxGlslCacheSize in GLSL_CACHE_MIN_MB..GLSL_CACHE_MAX_MB) {
                    "Cache writes must contain a legal integer MB value; range repair is read-only"
                }
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
                val file = File(directory, FILE_NAME_CONFIG)
                val previousRaw = if (Files.notExists(file.toPath())) null else file.readText(Charsets.UTF_8)
                val existing = if (previousRaw == null) JsonObject() else try {
                    parseRoot(previousRaw).also { parseConfig(it, strict = true) }
                } catch (e: Exception) {
                    MobileGluesConfigDebugLog.error(directory, "write found damaged config path=${file.absolutePath}", e)
                    if (!allowDamaged) throw e
                    // Still preserve every parseable unknown key during explicit recovery.
                    runCatching { parseRoot(previousRaw) }.getOrElse { JsonObject() }
                }
                val normalized = config.normalized()
                val expected = mergeJson(existing, normalized)
                val text = GSON.toJson(expected)
                MobileGluesConfigDebugLog.debug(directory, "write path=${file.absolutePath} raw=${toJsonUnnormalized(config)} normalized=$text previous=$previousRaw")
                val staged = File.createTempFile(".config-", ".tmp", directory)
                temp = staged
                FileOutputStream(staged).use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                // Fail rather than guessing a non-atomic/delete-then-rename fallback.
                atomicReplace(staged, file)
                temp = null
                val readBack = file.readText(Charsets.UTF_8)
                MobileGluesConfigDebugLog.debug(directory, "write readback path=${file.absolutePath} raw=$readBack")
                val actual = parseRoot(readBack)
                check(actual == expected) { "Config JSON readback mismatch" }
                check(parseConfig(actual, strict = true).normalized() == normalized) { "Config value readback mismatch" }
                MobileGluesConfigDebugLog.debug(directory, "write verified normalized=${toJson(normalized)}")
                true
            } catch (e: Exception) {
                MobileGluesConfigDebugLog.error(directory, "write failed path=${File(directory, FILE_NAME_CONFIG).absolutePath}", e)
                false
            } finally {
                temp?.let {
                    try {
                        if (it.exists() && !it.delete()) MobileGluesConfigDebugLog.error(directory, "temporary cleanup failed path=${it.absolutePath}")
                    } catch (e: Exception) {
                        MobileGluesConfigDebugLog.error(directory, "temporary cleanup failed path=${it.absolutePath}", e)
                    }
                }
            }
        }

        /**
         * Launch-time compatibility recovery only, not an editor loading API.
         * Damaged data is backed up before replacement; a backup failure prevents replacement.
         * A returned fallback after I/O failure is not proof that MG read that value.
         */
        fun ensureValid(): MobileGluesConfig = ensureValid(configDir)

        internal fun ensureValid(directory: File): MobileGluesConfig = synchronized(ioLock) {
            val file = File(directory, FILE_NAME_CONFIG)
            MobileGluesConfigDebugLog.debug(directory, "MG launch read config MG_DIR_PATH=${directory.absolutePath} path=${file.absolutePath}")
            try {
                val loaded = loadStrict(directory)
                if (loaded.isFailure) {
                    // Copy, never rename/delete the only original. Do not destroy it if backup fails.
                    file.copyTo(File(directory, FILE_NAME_BROKEN_CONFIG), overwrite = true)
                    val recovered = try {
                        parseConfig(parseRoot(file.readText(Charsets.UTF_8)), strict = false).normalized()
                    } catch (e: Exception) {
                        MobileGluesConfigDebugLog.error(directory, "MG launch cannot salvage damaged JSON; using defaults", e)
                        MobileGluesConfig()
                    }
                    saveLocked(recovered, directory, allowDamaged = true)
                    recovered
                } else {
                    val current = loaded.getOrThrow()
                    val root = if (file.exists()) parseRoot(file.readText(Charsets.UTF_8)) else JsonObject()
                    if (!file.exists() || mergeJson(root, current) != root) {
                        saveLocked(current, directory, allowDamaged = false)
                    }
                    current
                }
            } catch (e: Exception) {
                MobileGluesConfigDebugLog.error(directory, "MG launch config preparation failed path=${file.absolutePath}", e)
                MobileGluesConfig()
            }
        }

        /** Explicit reset; preserves unknown keys and backs up malformed existing data. */
        fun resetToDefaults(): MobileGluesConfig = synchronized(ioLock) {
            val defaults = MobileGluesConfig()
            try {
                if (configFile.exists() && loadStrict().isFailure) {
                    configFile.copyTo(File(configDir, FILE_NAME_BROKEN_CONFIG), overwrite = true)
                }
                saveLocked(defaults, configDir, allowDamaged = true)
            } catch (e: Exception) {
                MobileGluesConfigDebugLog.error(configDir, "reset failed", e)
            }
            defaults
        }

        /**
         * 把 JSON 文本解析为配置对象。
         * @throws com.google.gson.JsonParseException 内容不是合法 JSON 对象时抛出
         */
        fun fromJson(json: String): MobileGluesConfig = parseConfig(parseRoot(json), strict = false)

        private fun parseConfig(root: JsonObject, strict: Boolean): MobileGluesConfig {
            if (strict) validateKnownFields(root)
            val defaults = MobileGluesConfig()
            return MobileGluesConfig(
                enableANGLE = root.intOr("enableANGLE", defaults.enableANGLE),
                enableNoError = root.intOr("enableNoError", defaults.enableNoError),
                enableExtComputeShader = root.intOr("enableExtComputeShader", defaults.enableExtComputeShader),
                enableExtTimerQuery = root.intOr("enableExtTimerQuery", defaults.enableExtTimerQuery),
                enableExtDirectStateAccess = root.intOr("enableExtDirectStateAccess", defaults.enableExtDirectStateAccess),
                angleDepthClearFixMode = root.intOr("angleDepthClearFixMode", defaults.angleDepthClearFixMode),
                customGLVersion = root.intOr("customGLVersion", defaults.customGLVersion),
                fsr1Setting = root.intOr("fsr1Setting", defaults.fsr1Setting),
                hideMGEnvLevel = root.intOr("hideMGEnvLevel", defaults.hideMGEnvLevel),
                maxGlslCacheSize = root.get("maxGlslCacheSize")?.let { element ->
                    runCatching { MobileGluesGlslCachePolicy.parse(element.asString) }
                        .getOrDefault(defaults.maxGlslCacheSize)
                } ?: defaults.maxGlslCacheSize,
                multidrawOrder = root.stringOr("multidrawOrder", defaults.multidrawOrder),
                multidrawOrderArrays = root.stringOr("multidrawOrderArrays", defaults.multidrawOrderArrays),
                multidrawOrderElements = root.stringOr("multidrawOrderElements", defaults.multidrawOrderElements),
                multidrawOrderElementsBaseVertex = root.stringOr("multidrawOrderElementsBaseVertex", defaults.multidrawOrderElementsBaseVertex),
                multidrawOrderArraysIndirect = root.stringOr("multidrawOrderArraysIndirect", defaults.multidrawOrderArraysIndirect),
                multidrawOrderElementsIndirect = root.stringOr("multidrawOrderElementsIndirect", defaults.multidrawOrderElementsIndirect),
            )
        }

        /**
         * 把配置序列化为 MobileGlues 2.0.0 的 config.json 文本。
         * 空的按入口 multidraw 键会被省略，其余键始终写出。
         */
        fun toJson(config: MobileGluesConfig): String {
            val normalized = config.normalized()
            val root = JsonObject().apply {
                addProperty("enableANGLE", normalized.enableANGLE)
                addProperty("enableNoError", normalized.enableNoError)
                addProperty("enableExtComputeShader", normalized.enableExtComputeShader)
                addProperty("enableExtTimerQuery", normalized.enableExtTimerQuery)
                addProperty("enableExtDirectStateAccess", normalized.enableExtDirectStateAccess)
                addProperty("angleDepthClearFixMode", normalized.angleDepthClearFixMode)
                addProperty("customGLVersion", normalized.customGLVersion)
                addProperty("fsr1Setting", normalized.fsr1Setting)
                addProperty("hideMGEnvLevel", normalized.hideMGEnvLevel)
                addProperty("maxGlslCacheSize", normalized.maxGlslCacheSize)
                addProperty("multidrawOrder", normalized.multidrawOrder)
                normalized.multidrawOrderArrays
                    .takeIf { it.isNotEmpty() }
                    ?.let { addProperty("multidrawOrderArrays", it) }
                normalized.multidrawOrderElements
                    .takeIf { it.isNotEmpty() }
                    ?.let { addProperty("multidrawOrderElements", it) }
                normalized.multidrawOrderElementsBaseVertex
                    .takeIf { it.isNotEmpty() }
                    ?.let { addProperty("multidrawOrderElementsBaseVertex", it) }
                normalized.multidrawOrderArraysIndirect
                    .takeIf { it.isNotEmpty() }
                    ?.let { addProperty("multidrawOrderArraysIndirect", it) }
                normalized.multidrawOrderElementsIndirect
                    .takeIf { it.isNotEmpty() }
                    ?.let { addProperty("multidrawOrderElementsIndirect", it) }
            }
            return GSON.toJson(root)
        }

        private fun toJsonUnnormalized(config: MobileGluesConfig): String = GSON.toJson(config)

        /** Overlay known fields only; remove optional known keys explicitly when cleared. */
        private fun mergeJson(existing: JsonObject, config: MobileGluesConfig): JsonObject {
            val merged = existing.deepCopy()
            val known = parseRoot(toJson(config))
            OPTIONAL_ORDER_KEYS.forEach { merged.remove(it) }
            known.entrySet().forEach { (key, value) -> merged.add(key, value) }
            return merged
        }

        private val OPTIONAL_ORDER_KEYS = listOf(
            "multidrawOrderArrays", "multidrawOrderElements", "multidrawOrderElementsBaseVertex",
            "multidrawOrderArraysIndirect", "multidrawOrderElementsIndirect"
        )
        private val INTEGER_KEYS = listOf(
            "enableANGLE", "enableNoError", "enableExtComputeShader", "enableExtTimerQuery",
            "enableExtDirectStateAccess", "angleDepthClearFixMode", "customGLVersion",
            "fsr1Setting", "hideMGEnvLevel", "maxGlslCacheSize"
        )

        private fun validateKnownFields(root: JsonObject) {
            INTEGER_KEYS.forEach { key ->
                root.get(key)?.let { element ->
                    require(element.isJsonPrimitive &&
                        (element.asJsonPrimitive.isNumber || element.asJsonPrimitive.isString)) {
                        "$key must be an integer"
                    }
                    if (key == "maxGlslCacheSize") MobileGluesGlslCachePolicy.parse(element.asString)
                    else element.asString.trim().toBigDecimal().intValueExact()
                }
            }
            (OPTIONAL_ORDER_KEYS + "multidrawOrder").forEach { key ->
                root.get(key)?.let { element ->
                    require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$key must be a string" }
                }
            }
        }

        private fun parseRoot(json: String): JsonObject = JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val root = JsonParser.parseReader(reader)
            require(root.isJsonObject) { "MobileGlues config must be a JSON object" }
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing data in MobileGlues config" }
            root.asJsonObject
        }
    }
}

/**
 * customGLVersion 的合法性收敛：
 * 0 表示默认；34..39 被 MobileGlues 内部夹取为 33；小于 32 视为未设置；大于 46 收敛到 46。
 */
private fun normalizeGLVersion(value: Int): Int = when {
    value == MobileGluesConfig.GL_VERSION_DEFAULT -> MobileGluesConfig.GL_VERSION_DEFAULT
    value in 34..39 -> 33
    value in MobileGluesConfig.GL_VERSION_MIN..MobileGluesConfig.GL_VERSION_MAX -> value
    value < MobileGluesConfig.GL_VERSION_MIN -> MobileGluesConfig.GL_VERSION_DEFAULT
    else -> MobileGluesConfig.GL_VERSION_MAX
}

/**
 * 宽容地读取整数：类型不对或缺失时使用默认值，不抛异常。
 */
private fun JsonObject.intOr(key: String, defaultValue: Int): Int {
    val element = get(key) ?: return defaultValue
    if (!element.isJsonPrimitive) return defaultValue
    val primitive = element.asJsonPrimitive
    return if (primitive.isNumber || primitive.isString) {
        runCatching { primitive.asString.trim().toBigDecimal().intValueExact() }.getOrDefault(defaultValue)
    } else defaultValue
}

/**
 * 宽容地读取字符串：类型不对或缺失时使用默认值，不抛异常。
 */
private fun JsonObject.stringOr(key: String, defaultValue: String): String {
    val element = get(key) ?: return defaultValue
    if (!element.isJsonPrimitive) return defaultValue
    val primitive = element.asJsonPrimitive
    return if (primitive.isString) primitive.asString else defaultValue
}
