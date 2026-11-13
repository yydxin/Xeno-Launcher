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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "GlassConfig"

/**
 * 液态玻璃的渲染参数。
 *
 * 所有参数都会原样交给 `io.github.kyant0:backdrop` 的效果链：
 * `blur` → `lens`（折射/色散）→ 染色 → 高光/阴影。
 * 单位说明：`blurRadius`/`refractionHeight`/`shadowRadius`/`cornerRadius`/`borderWidth` 为 dp 语义，
 * `refractionAmount` 是 0..1 的强度，`highlightIntensity` 是 0..2 的倍率。
 */
data class GlassConfig(
    val blurRadius: Float = 18f,
    val refractionHeight: Float = 26f,
    val refractionAmount: Float = 0.55f,
    val chromaticAberration: Boolean = true,
    val depthEffect: Boolean = true,
    val tintColor: Long = 0x33FFFFFF,
    val tintAlpha: Float = 0.22f,
    val highlightIntensity: Float = 0.8f,
    val shadowRadius: Float = 14f,
    val cornerRadius: Float = 28f,
    val borderWidth: Float = 1f,
    val pressScale: Float = 0.96f,
    /** 滚动时是否降低 backdrop 刷新频率（省电） */
    val throttleWhileScrolling: Boolean = true
) {
    /**
     * 单项收敛：非法值只修该项，其余字段保持原样。
     * 这是"设置不许被整份重置"的底线。
     */
    fun sanitized(): GlassConfig = copy(
        blurRadius = blurRadius.coerceIn(0f, 60f),
        refractionHeight = refractionHeight.coerceIn(0f, 80f),
        refractionAmount = refractionAmount.coerceIn(0f, 1f),
        tintAlpha = tintAlpha.coerceIn(0f, 1f),
        highlightIntensity = highlightIntensity.coerceIn(0f, 2f),
        shadowRadius = shadowRadius.coerceIn(0f, 48f),
        cornerRadius = cornerRadius.coerceIn(0f, 64f),
        borderWidth = borderWidth.coerceIn(0f, 6f),
        pressScale = pressScale.coerceIn(0.8f, 1f)
    )

    companion object {
        val Default = GlassConfig()

        /**
         * 解析 JSON。逐字段读取：某个字段类型不对/越界时只回落该项，其它字段照常保留，
         * 未知字段原样保留在 [raw] 里，保存时写回，避免"读改写"丢字段。
         */
        fun fromJson(json: String, fallback: GlassConfig = Default): GlassConfig {
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return fallback
            return GlassConfig(
                blurRadius = root.floatOr("blurRadius", fallback.blurRadius),
                refractionHeight = root.floatOr("refractionHeight", fallback.refractionHeight),
                refractionAmount = root.floatOr("refractionAmount", fallback.refractionAmount),
                chromaticAberration = root.boolOr("chromaticAberration", fallback.chromaticAberration),
                depthEffect = root.boolOr("depthEffect", fallback.depthEffect),
                tintColor = root.longOr("tintColor", fallback.tintColor),
                tintAlpha = root.floatOr("tintAlpha", fallback.tintAlpha),
                highlightIntensity = root.floatOr("highlightIntensity", fallback.highlightIntensity),
                shadowRadius = root.floatOr("shadowRadius", fallback.shadowRadius),
                cornerRadius = root.floatOr("cornerRadius", fallback.cornerRadius),
                borderWidth = root.floatOr("borderWidth", fallback.borderWidth),
                pressScale = root.floatOr("pressScale", fallback.pressScale),
                throttleWhileScrolling = root.boolOr("throttleWhileScrolling", fallback.throttleWhileScrolling)
            ).sanitized()
        }

        /**
         * 序列化：保留调用方提供的未知字段（[extra]），再覆盖已知字段。
         */
        fun toJson(config: GlassConfig, extra: JsonObject? = null): String {
            val c = config.sanitized()
            val root = JsonObject()
            extra?.entrySet()?.forEach { (k, v) -> root.add(k, v) }
            root.addProperty("blurRadius", c.blurRadius)
            root.addProperty("refractionHeight", c.refractionHeight)
            root.addProperty("refractionAmount", c.refractionAmount)
            root.addProperty("chromaticAberration", c.chromaticAberration)
            root.addProperty("depthEffect", c.depthEffect)
            root.addProperty("tintColor", c.tintColor)
            root.addProperty("tintAlpha", c.tintAlpha)
            root.addProperty("highlightIntensity", c.highlightIntensity)
            root.addProperty("shadowRadius", c.shadowRadius)
            root.addProperty("cornerRadius", c.cornerRadius)
            root.addProperty("borderWidth", c.borderWidth)
            root.addProperty("pressScale", c.pressScale)
            root.addProperty("throttleWhileScrolling", c.throttleWhileScrolling)
            return GSON.toJson(root)
        }

        fun parseUnknown(json: String): JsonObject? = runCatching {
            JsonParser.parseString(json).asJsonObject
        }.onFailure { Logger.warning(TAG, "Failed to parse glass config json", it) }.getOrNull()
    }
}

private fun JsonObject.floatOr(key: String, def: Float): Float {
    val e = get(key) ?: return def
    if (!e.isJsonPrimitive) return def
    val p = e.asJsonPrimitive
    return when {
        p.isNumber -> runCatching { p.asFloat }.getOrDefault(def)
        p.isString -> p.asString.trim().toFloatOrNull() ?: def
        else -> def
    }
}

private fun JsonObject.longOr(key: String, def: Long): Long {
    val e = get(key) ?: return def
    if (!e.isJsonPrimitive) return def
    val p = e.asJsonPrimitive
    return when {
        p.isNumber -> runCatching { p.asLong }.getOrDefault(def)
        p.isString -> runCatching {
            val s = p.asString.trim()
            if (s.startsWith("0x", true)) s.substring(2).toLong(16) else s.toLong()
        }.getOrDefault(def)
        else -> def
    }
}

private fun JsonObject.boolOr(key: String, def: Boolean): Boolean {
    val e = get(key) ?: return def
    if (!e.isJsonPrimitive) return def
    val p = e.asJsonPrimitive
    return when {
        p.isBoolean -> runCatching { p.asBoolean }.getOrDefault(def)
        p.isString -> p.asString.trim().toBooleanStrictOrNull() ?: def
        else -> def
    }
}
