/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 会话本地持久化：每个会话一个 JSON 文件，重启后仍在。
 * 只保存对话内容与用量，不保存 API Key。
 */
class ChatStore(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val messageListType = object : TypeToken<List<ChatMessage>>() {}.type
    private val dir: File = File(context.filesDir, "ai-chat/sessions").apply { mkdirs() }
    private val pref = context.getSharedPreferences("xzlc_ai_chat", Context.MODE_PRIVATE)

    fun loadAll(): List<ChatSession> =
        dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { load(file) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun load(id: String): ChatSession? {
        val file = File(dir, "$id.json").takeIf { it.isFile } ?: return null
        return load(file)
    }

    private fun load(file: File): ChatSession {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        fun str(key: String): String = root.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        fun long(key: String): Long = root.get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val messages: MutableList<ChatMessage> = root.getAsJsonArray("messages")
            ?.let { array ->
                runCatching {
                    gson.fromJson<List<ChatMessage>>(array, messageListType).toMutableList()
                }.getOrNull()
            }
            ?: mutableListOf()
        return ChatSession(
            id = str("id").ifBlank { file.nameWithoutExtension },
            title = str("title").ifBlank { "新对话" },
            createdAt = long("createdAt").takeIf { it > 0 } ?: file.lastModified(),
            updatedAt = long("updatedAt").takeIf { it > 0 } ?: file.lastModified(),
            providerId = str("providerId"),
            providerName = str("providerName"),
            model = str("model"),
            promptTokens = long("promptTokens"),
            completionTokens = long("completionTokens"),
            messages = messages
        )
    }

    fun save(session: ChatSession) {
        session.updatedAt = System.currentTimeMillis()
        val root = JsonObject().apply {
            addProperty("id", session.id)
            addProperty("title", session.title)
            addProperty("createdAt", session.createdAt)
            addProperty("updatedAt", session.updatedAt)
            addProperty("providerId", session.providerId)
            addProperty("providerName", session.providerName)
            addProperty("model", session.model)
            addProperty("promptTokens", session.promptTokens)
            addProperty("completionTokens", session.completionTokens)
            add("messages", gson.toJsonTree(session.messages, messageListType))
        }
        File(dir, "${session.id}.json").writeText(gson.toJson(root))
        if (currentId() == session.id) pref.edit().putString("current", session.id).apply()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        if (currentId() == id) pref.edit().remove("current").apply()
    }

    fun currentId(): String? = pref.getString("current", null)

    fun setCurrent(id: String) {
        pref.edit().putString("current", id).apply()
    }

    fun search(query: String): List<ChatSession> {
        if (query.isBlank()) return loadAll()
        val q = query.lowercase()
        return loadAll().filter { session ->
            session.title.lowercase().contains(q) ||
                session.messages.any { it.content.lowercase().contains(q) }
        }
    }
}
