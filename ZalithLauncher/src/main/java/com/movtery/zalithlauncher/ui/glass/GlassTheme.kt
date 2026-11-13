/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.glass

import android.os.Build
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.security.MessageDigest

private const val TAG = "GlassPresets"

/**
 * 当前设备能跑的玻璃档位。
 *
 * 与 Kyant backdrop 的能力一一对应（源码级核对）：
 * - [AGSL]：API 33+，`RuntimeShader` 可用 → 完整折射 + 色散 + 深度
 * - [RENDER_EFFECT]：API 31–32 → 只有 `RenderEffect` 模糊，折射调用会被库直接忽略
 * - [FROST]：API 26–30 → 库的效果全部 no-op，回退到"磨砂渐变 + 描边"
 */
enum class GlassTier {
    AGSL,
    RENDER_EFFECT,
    FROST;

    val displayName: String
        get() = when (this) {
            AGSL -> "AGSL（完整折射）"
            RENDER_EFFECT -> "RenderEffect（模糊）"
            FROST -> "磨砂降级"
        }
}

/** 档位覆盖（调试用）：自动 / 强制某档 */
enum class GlassTierOverride {
    AUTO, AGSL, RENDER_EFFECT, FROST;

    fun resolve(): GlassTier = when (this) {
        AUTO -> deviceTier()
        AGSL -> deviceTier()
        RENDER_EFFECT -> if (deviceTier() == GlassTier.FROST) GlassTier.FROST else GlassTier.RENDER_EFFECT
        FROST -> GlassTier.FROST
    }
}

/** 设备本身支持的档位 */
fun deviceTier(): GlassTier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassTier.AGSL
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlassTier.RENDER_EFFECT
    else -> GlassTier.FROST
}

/** 一个可保存/导出的玻璃预设 */
data class GlassPreset(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val config: GlassConfig
)

/** 预设文件的 schema 版本，升级时用于迁移 */
const val GLASS_PRESET_SCHEMA_VERSION = 1

object GlassPresets {
    /** 内置预设 */
    val builtIn: List<GlassPreset> = listOf(
        GlassPreset("builtin-clear", "清透", true, GlassConfig(
            blurRadius = 12f, refractionHeight = 20f, refractionAmount = 0.35f,
            chromaticAberration = false, depthEffect = true,
            tintAlpha = 0.12f, highlightIntensity = 1.0f, shadowRadius = 10f
        )),
        GlassPreset("builtin-frost", "磨砂", true, GlassConfig(
            blurRadius = 30f, refractionHeight = 0f, refractionAmount = 0f,
            chromaticAberration = false, depthEffect = false,
            tintAlpha = 0.32f, highlightIntensity = 0.3f, shadowRadius = 8f
        )),
        GlassPreset("builtin-refract", "强折射", true, GlassConfig(
            blurRadius = 10f, refractionHeight = 40f, refractionAmount = 0.9f,
            chromaticAberration = true, depthEffect = true,
            tintAlpha = 0.08f, highlightIntensity = 1.4f, shadowRadius = 18f
        )),
        GlassPreset("builtin-minimal", "极简", true, GlassConfig(
            blurRadius = 8f, refractionHeight = 0f, refractionAmount = 0f,
            chromaticAberration = false, depthEffect = false,
            tintAlpha = 0.06f, highlightIntensity = 0.2f, shadowRadius = 0f,
            borderWidth = 0f
        ))
    )

    private fun dir(): File = File(PathManager.DIR_FILES_PRIVATE, "glass_presets")

    fun userPresets(): List<GlassPreset> {
        val files = runCatching { dir().listFiles { f -> f.isFile && f.extension == "json" } }
            .getOrNull() ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { readPreset(file) }
                .onFailure { Logger.warning(TAG, "Skip broken preset: ${file.name}", it) }
                .getOrNull()
        }.sortedBy { it.name }
    }

    private fun readPreset(file: File): GlassPreset? {
        val text = file.readText(Charsets.UTF_8)
        val root = JsonParser.parseString(text).asJsonObject
        val schema = root.get("schemaVersion")?.asInt ?: 0
        if (schema > GLASS_PRESET_SCHEMA_VERSION) {
            Logger.warning(TAG, "Preset schema $schema is newer than supported, skipped: ${file.name}")
            return null
        }
        val id = root.stringOr("id", file.nameWithoutExtension)
        val name = root.stringOr("name", id)
        val configJson = root.getAsJsonObject("config")?.let { GSON.toJson(it) } ?: "{}"
        val checksum = root.stringOr("checksum", "")
        if (checksum.isNotEmpty() && checksum != sha256(configJson + "|" + name)) {
            Logger.warning(TAG, "Preset checksum mismatch, repair by re-saving: ${file.name}")
        }
        return GlassPreset(id = id, name = name, builtIn = false, config = GlassConfig.fromJson(configJson))
    }

    /** 保存（原子写入）。同 id 覆盖，同 name 去重。 */
    fun save(preset: GlassPreset): Boolean {
        return runCatching {
            val d = dir().apply { mkdirs() }
            val file = File(d, "${preset.id}.json")
            val configJson = GlassConfig.toJson(preset.config)
            val root = JsonObject().apply {
                addProperty("schemaVersion", GLASS_PRESET_SCHEMA_VERSION)
                addProperty("id", preset.id)
                addProperty("name", preset.name)
                add("config", JsonParser.parseString(configJson))
                addProperty("checksum", sha256(configJson + "|" + preset.name))
            }
            val temp = File(d, "${preset.id}.json.tmp")
            temp.writeText(GSON.toJson(root), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    Logger.warning(TAG, "Failed to move preset into place: ${file.absolutePath}")
                    return false
                }
            }
            true
        }.getOrElse {
            Logger.warning(TAG, "Failed to save glass preset", it)
            false
        }
    }

    fun delete(id: String): Boolean {
        return runCatching { File(dir(), "$id.json").delete() }.getOrDefault(false)
    }

    fun exportText(preset: GlassPreset): String {
        val configJson = GlassConfig.toJson(preset.config)
        val root = JsonObject().apply {
            addProperty("schemaVersion", GLASS_PRESET_SCHEMA_VERSION)
            addProperty("id", preset.id)
            addProperty("name", preset.name)
            add("config", JsonParser.parseString(configJson))
            addProperty("checksum", sha256(configJson + "|" + preset.name))
        }
        return GSON.toJson(root)
    }

    /** 从文本导入；名字重复时自动加后缀，id 重新生成 */
    fun importText(text: String, existingNames: Set<String>): GlassPreset? {
        return runCatching {
            val root = JsonParser.parseString(text).asJsonObject
            val schema = root.get("schemaVersion")?.asInt ?: GLASS_PRESET_SCHEMA_VERSION
            if (schema > GLASS_PRESET_SCHEMA_VERSION) return null
            val baseName = root.stringOr("name", "导入的预设")
            var name = baseName
            var i = 2
            while (name in existingNames) { name = "$baseName ($i)"; i++ }
            val configJson = root.getAsJsonObject("config")?.let { GSON.toJson(it) } ?: return null
            GlassPreset(
                id = "user-" + System.currentTimeMillis().toString(36) + "-" + (100..999).random(),
                name = name,
                builtIn = false,
                config = GlassConfig.fromJson(configJson)
            )
        }.onFailure { Logger.warning(TAG, "Failed to import preset text", it) }.getOrNull()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.stringOr(key: String, def: String): String {
        val e = get(key) ?: return def
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else def
    }
}

/** 当前生效的玻璃 backdrop；null 表示没有可用背景（不启用玻璃） */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 玻璃是否启用（主题 + 游戏运行态 + 档位综合判断后的结果） */
val LocalGlassEnabled = compositionLocalOf { false }

/** 当前生效的玻璃参数 */
val LocalGlassConfig = compositionLocalOf { GlassConfig.Default }
