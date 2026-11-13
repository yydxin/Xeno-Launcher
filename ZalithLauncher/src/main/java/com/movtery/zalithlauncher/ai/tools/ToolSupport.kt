/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TOOL_GSON: Gson = Gson()
internal val TOOL_GSON_PRETTY: Gson = GsonBuilder().setPrettyPrinting().create()

private val LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

// ---------------------------------------------------------------------------- schemas

internal fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, JsonObject> = emptyMap()
): JsonObject = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        properties.forEach { (name, schema) -> add(name, schema) }
    })
    add("required", JsonArray().apply { required.forEach { add(it) } })
    addProperty("additionalProperties", false)
}

internal fun stringSchema(
    description: String,
    enumValues: List<String>? = null,
    default: String? = null
): JsonObject = JsonObject().apply {
    addProperty("type", "string")
    addProperty("description", description)
    enumValues?.let { values ->
        add("enum", JsonArray().apply { values.forEach { add(it) } })
    }
    default?.let { addProperty("default", it) }
}

internal fun integerSchema(
    description: String,
    minimum: Int? = null,
    maximum: Int? = null,
    default: Int? = null
): JsonObject = JsonObject().apply {
    addProperty("type", "integer")
    addProperty("description", description)
    minimum?.let { addProperty("minimum", it) }
    maximum?.let { addProperty("maximum", it) }
    default?.let { addProperty("default", it) }
}

internal fun booleanSchema(
    description: String,
    default: Boolean? = null
): JsonObject = JsonObject().apply {
    addProperty("type", "boolean")
    addProperty("description", description)
    default?.let { addProperty("default", it) }
}

// ---------------------------------------------------------------------------- json

internal fun JsonObject.optText(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }
        ?.let { runCatching { it.asString }.getOrNull() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun JsonObject.optInt(key: String): Int? =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }

internal fun JsonObject.optBoolean(key: String): Boolean? =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }

internal fun JsonObject.put(key: String, value: Any?) {
    when (value) {
        null -> add(key, JsonNull.INSTANCE)
        is JsonElement -> add(key, value)
        is Number -> addProperty(key, value)
        is Boolean -> addProperty(key, value)
        is String -> addProperty(key, value)
        else -> addProperty(key, value.toString())
    }
}

internal fun JsonArray.addAllStrings(values: Iterable<String>) {
    values.forEach { add(it) }
}

internal fun jsonArray(values: Iterable<String>): JsonArray = JsonArray().apply {
    values.forEach { add(it) }
}

internal fun okResult(summary: String, body: JsonObject = JsonObject()): ToolResult {
    body.addProperty("ok", true)
    return ToolResult(true, summary, TOOL_GSON.toJson(body))
}

internal fun failResult(message: String, body: JsonObject = JsonObject()): ToolResult {
    body.addProperty("ok", false)
    body.addProperty("error", message)
    return ToolResult(false, message, TOOL_GSON.toJson(body))
}

internal fun notFoundResult(name: String?): ToolResult {
    val message = if (name.isNullOrBlank()) {
        "当前没有选中的实例，请先选择实例，或传入 instance 参数。"
    } else {
        "找不到实例「$name」。请先调用 get_instances 确认实例名称。"
    }
    return failResult(message, JsonObject().apply { put("instance", name) })
}

// ---------------------------------------------------------------------------- versions

/** 版本列表；VersionsManager 尚未刷新时从磁盘兜底扫描 */
internal fun allVersions(): List<Version> {
    val loaded = runCatching { VersionsManager.versions.value }.getOrDefault(emptyList())
    if (loaded.isNotEmpty()) return loaded
    val home = runCatching { getGameHome() }.getOrNull() ?: return emptyList()
    val versionsFolder = runCatching { File(getVersionsHome(home)) }.getOrNull() ?: return emptyList()
    return versionsFolder.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { folder ->
            runCatching { VersionsManager.loadVersion(home, folder.name) }.getOrNull()
        }
        ?.sortedWith { a, b -> a.getVersionName().compareTo(b.getVersionName(), ignoreCase = true) }
        .orEmpty()
}

internal fun currentVersionName(): String? {
    val current = runCatching { VersionsManager.currentVersion.value }.getOrNull()
    if (current != null) return current.getVersionName()
    return runCatching { VersionsManager.gameInfo?.version }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * 按实例名解析版本。instance 为空时使用当前选中实例。
 */
internal fun resolveVersion(instance: String?): Version? {
    val all = allVersions()
    val requested = instance?.trim().orEmpty()
    if (requested.isEmpty()) {
        val currentName = currentVersionName()
        return runCatching { VersionsManager.currentVersion.value }.getOrNull()
            ?.takeIf { it.isValid() }
            ?: all.firstOrNull { it.getVersionName() == currentName && it.isValid() }
            ?: all.firstOrNull { it.isValid() }
    }
    return all.firstOrNull { it.getVersionName() == requested }
        ?: all.firstOrNull { it.getVersionName().equals(requested, ignoreCase = true) }
}

internal fun modsDir(version: Version): File =
    VersionFolders.MOD.getDir(version.getGameDir())

internal fun shadersDir(version: Version): File =
    VersionFolders.SHADERS.getDir(version.getGameDir())

internal fun resourcePacksDir(version: Version): File =
    VersionFolders.RESOURCE_PACK.getDir(version.getGameDir())

internal fun crashReportsDir(version: Version): File =
    File(version.getGameDir(), "crash-reports")

internal fun instanceLogPath(version: Version): File = version.getLatestLog()

internal fun instanceLabel(version: Version): String =
    version.getVersionInfo()?.getInfoString() ?: version.getVersionName()

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B"
    else String.format(java.util.Locale.US, "%.2f %s", value, units[unit])
}

internal fun formatTime(epochMillis: Long): String =
    runCatching { LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis)) }
        .getOrDefault(epochMillis.toString())

// ---------------------------------------------------------------------------- files

/**
 * 读取文件末尾若干字节。
 * @return Pair(内容, 是否因为文件过大而截断)
 */
internal fun readTextLimited(file: File, maxBytes: Long = 1_500_000L): Pair<String, Boolean> {
    if (!file.isFile) return "" to false
    val length = file.length()
    if (length <= maxBytes) {
        return runCatching { file.readText() }.getOrDefault("") to false
    }
    val text = runCatching {
        RandomAccessFile(file, "r").use { random ->
            val start = (length - maxBytes).coerceAtLeast(0)
            random.seek(start)
            val bytes = ByteArray((length - start).toInt().coerceAtMost(Int.MAX_VALUE))
            random.readFully(bytes)
            //尽量从完整行开始，避免第一行是半截
            val text = String(bytes, Charsets.UTF_8)
            val newline = text.indexOf('\n')
            if (newline in 0 until text.length - 1) text.substring(newline + 1) else text
        }
    }.getOrDefault("")
    val prefix = "[日志文件较大（${formatBytes(length)}），仅读取最后 ${formatBytes(maxBytes)}]\n"
    return prefix + text to true
}

internal fun safeTimestamp(): String =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        .format(Instant.now())

internal fun safeTimestampMillis(): String =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
        .format(Instant.now())

/** 将字符串变成可安全用于文件名的片段（保留中文） */
internal fun safeFileSegment(raw: String, fallback: String = "instance"): String {
    var value = raw.trim()
    if (value.isEmpty()) value = fallback
    value = value
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .replace("..", "_")
        .trim()
        .trim('.')
    if (value.isEmpty()) value = fallback
    return value.take(80)
}

/**
 * 校验用户提供的纯文件名：拒绝路径分隔符、`..`、空字符串和 Windows 盘符。
 * @return 安全文件名；不合法时返回 null
 */
internal fun simpleFileName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty()) return null
    if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
    if (Regex("""^[A-Za-z]:""").containsMatchIn(name)) return null
    if (name.any { it.code < 32 }) return null
    if (name == "." || name == "..") return null
    return name.take(160)
}

internal fun uniqueFile(dir: File, name: String): File {
    dir.mkdirs()
    val candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    for (index in 1..999) {
        val next = File(dir, "$base ($index)$ext")
        if (!next.exists()) return next
    }
    return File(dir, "$base-${System.currentTimeMillis()}$ext")
}

/** 仅允许 http/https，且必须有 host */
internal fun isSafeHttpUrl(url: String): Boolean = runCatching {
    val uri = URI(url.trim())
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank()
}.getOrDefault(false)

internal fun File.ensureDir(): Boolean = isDirectory || mkdirs()
