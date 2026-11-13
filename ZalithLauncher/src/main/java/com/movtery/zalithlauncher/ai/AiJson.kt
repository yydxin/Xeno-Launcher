/*
 * XenoLauncher - unofficial fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Gson 安全读取工具。
 *
 * 服务商流式响应里常见 `"usage": null`、`"delta": null`、`"tool_calls": null`、
 * 空 `choices`、错误体、HTML/空响应。直接 `getAsJsonObject`/`asJsonObject`
 * 会在这些情况下抛 ClassCastException，这里统一先判类型再取值。
 */
fun JsonObject.objOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.arrOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.strOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

fun JsonObject.longOrNull(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

fun JsonObject.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

fun JsonObject.boolOrNull(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

fun JsonElement?.objOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonElement?.arrOrNull(): JsonArray? = this?.takeIf { it.isJsonArray }?.asJsonArray

fun parseJsonObjectOrNull(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return runCatching { JsonParser.parseString(text) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject
}

/** 从错误响应体提取可读信息（不包含 Key）。 */
fun extractApiErrorMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val obj = parseJsonObjectOrNull(body)
    val error = obj?.objOrNull("error")
    val message = error?.strOrNull("message")
        ?: obj?.strOrNull("message")
        ?: error?.strOrNull("type")
    if (!message.isNullOrBlank()) return message
    // 非 JSON（HTML/纯文本）时截取前 200 字，避免把整页塞进提示
    return body.replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim().take(200).ifBlank { null }
}

/**
 * 拼接 API 地址，兼容 Base URL 带/不带 `/v1`、带/不带尾部斜杠，
 * 并处理用户误粘贴完整 endpoint 或重复 `/v1` 的情况。
 */
fun joinApiUrl(baseUrl: String, path: String): String {
    var base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return path.trimStart('/')
    // 去掉用户误填的完整 endpoint
    base = base.removeSuffix("/chat/completions")
        .removeSuffix("/messages")
        .trimEnd('/')
    // 处理 /v1/v1 这类重复
    while (base.endsWith("/v1/v1")) base = base.removeSuffix("/v1")
    // 防止协议后的 // 被误伤，只折叠路径里的重复斜杠
    val schemeEnd = base.indexOf("://")
    if (schemeEnd >= 0) {
        val prefix = base.substring(0, schemeEnd + 3)
        val rest = base.substring(schemeEnd + 3).replace(Regex("/{2,}"), "/")
        base = prefix + rest
    }
    return "$base/${path.trimStart('/')}"
}
