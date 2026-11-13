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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.setting.launcherMMKV
import java.util.UUID

/** 内置的服务商填写模板（只带出名称/地址/格式，不含 Key） */
data class AiProviderTemplate(
    val key: String,
    val displayName: String,
    val baseUrl: String,
    val format: AiApiFormat,
    val models: List<String>
)

/** 联网搜索服务商 */
enum class WebSearchProvider(val displayName: String) {
    TAVILY("Tavily"),
    BRAVE("Brave Search"),
    SERPER("Serper (Google)"),
    CUSTOM("自定义（POST JSON）")
}

data class WebSearchConfig(
    val provider: WebSearchProvider = WebSearchProvider.TAVILY,
    val endpoint: String = "",
    val resultCount: Int = 5
)

/**
 * AI 配置存储（MMKV）。
 *
 * - 服务商基础信息、模型、当前选择：非敏感，明文存 MMKV；
 * - API Key（含搜索 Key）：[AiSecrets] 加密后存 MMKV，按服务商 id 分开；
 * - 所有读取失败都降级为空，绝不因为配置损坏让启动器崩溃。
 */
object AiSettingsStore {
    private const val KEY_PROVIDERS = "ai.providers.v2"
    private const val KEY_ACTIVE_PROVIDER = "ai.active.provider.v2"
    private const val KEY_MODEL_PREFIX = "ai.active.model."
    private const val KEY_SECRET_PREFIX = "ai.secret."
    private const val KEY_SEARCH_PROVIDER = "ai.search.provider"
    private const val KEY_SEARCH_ENDPOINT = "ai.search.endpoint"
    private const val KEY_SEARCH_COUNT = "ai.search.count"
    private const val KEY_SEARCH_SECRET = "ai.search.secret"

    val templates: List<AiProviderTemplate> = listOf(
        AiProviderTemplate(
            "deepseek", "DeepSeek", "https://api.deepseek.com", AiApiFormat.OPENAI,
            listOf("deepseek-flash", "deepseek-v4-pro")
        ),
        AiProviderTemplate(
            "openai", "OpenAI", "https://api.openai.com/v1", AiApiFormat.OPENAI,
            listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")
        ),
        AiProviderTemplate(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", AiApiFormat.ANTHROPIC,
            listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022")
        ),
        AiProviderTemplate(
            "custom-openai", "自定义（OpenAI 兼容）", "", AiApiFormat.OPENAI, emptyList()
        ),
        AiProviderTemplate(
            "custom-anthropic", "自定义（Anthropic 兼容）", "", AiApiFormat.ANTHROPIC, emptyList()
        )
    )

    fun providers(): List<AiProviderConfig> {
        val text = launcherMMKV().decodeString(KEY_PROVIDERS) ?: return emptyList()
        return runCatching {
            JsonParser.parseString(text).asJsonArray.mapNotNull { element ->
                runCatching { parseProvider(element.asJsonObject) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseProvider(obj: JsonObject): AiProviderConfig {
        fun str(key: String): String = obj.get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
        val models = obj.getAsJsonArray("models")?.mapNotNull {
            runCatching { it.asString }.getOrNull()
        }.orEmpty()
        val format = runCatching { AiApiFormat.valueOf(str("format")) }.getOrDefault(AiApiFormat.OPENAI)
        return AiProviderConfig(
            id = str("id"),
            name = str("name"),
            baseUrl = str("baseUrl"),
            format = format,
            models = models,
            defaultModel = str("defaultModel"),
            supportsTools = obj.get("supportsTools")?.asBoolean ?: true
        )
    }

    fun saveProviders(list: List<AiProviderConfig>) {
        val array = JsonArray()
        list.forEach { provider ->
            array.add(JsonObject().apply {
                addProperty("id", provider.id)
                addProperty("name", provider.name)
                addProperty("baseUrl", provider.baseUrl)
                addProperty("format", provider.format.name)
                addProperty("defaultModel", provider.defaultModel)
                addProperty("supportsTools", provider.supportsTools)
                add("models", JsonArray().apply { provider.models.forEach { add(it) } })
            })
        }
        launcherMMKV().encode(KEY_PROVIDERS, array.toString())
    }

    fun newProviderId(): String = UUID.randomUUID().toString()

    /**
     * 保存服务商。
     * @param apiKey null 表示不修改已保存的 Key；空字符串表示清除。
     */
    fun saveProvider(provider: AiProviderConfig, apiKey: String?): Result<Unit> = runCatching {
        require(provider.id.isNotBlank()) { "服务商 ID 不能为空" }
        require(provider.name.isNotBlank()) { "服务商名称不能为空" }
        val current = providers().toMutableList()
        val index = current.indexOfFirst { it.id == provider.id }
        if (index >= 0) current[index] = provider else current.add(provider)

        if (apiKey != null) {
            val plain = apiKey.trim()
            if (plain.isEmpty()) {
                launcherMMKV().removeValueForKey(secretKey(provider.id))
            } else {
                val cipher = AiSecrets.encryptOrThrow(plain)
                check(AiSecrets.decryptOrThrow(cipher) == plain) { "API Key 加密回读不一致" }
                launcherMMKV().encode(secretKey(provider.id), cipher)
            }
        }
        saveProviders(current)
        val saved = providers().firstOrNull { it.id == provider.id }
            ?: error("服务商保存后回读失败")
        check(saved.name == provider.name && saved.baseUrl == provider.baseUrl) {
            "服务商保存后回读不一致"
        }
        if (!apiKey.isNullOrBlank()) {
            check(apiKey(provider.id) == apiKey.trim()) { "API Key 保存后回读失败" }
        }
        if (activeProviderId().isBlank()) setActiveProvider(provider.id)
    }

    fun deleteProvider(id: String) {
        val list = providers().filterNot { it.id == id }
        saveProviders(list)
        launcherMMKV().removeValueForKey(secretKey(id))
        if (activeProviderId() == id) {
            launcherMMKV().removeValueForKey(KEY_ACTIVE_PROVIDER)
            launcherMMKV().removeValueForKey(KEY_MODEL_PREFIX + id)
        }
    }

    fun apiKey(providerId: String): String {
        val cipher = launcherMMKV().decodeString(secretKey(providerId)) ?: return ""
        return AiSecrets.decrypt(cipher)
    }

    /** 用于界面提示，不暴露 Key 本身 */
    fun hasKey(providerId: String): Boolean = apiKey(providerId).isNotBlank()

    fun activeProviderId(): String =
        launcherMMKV().decodeString(KEY_ACTIVE_PROVIDER).orEmpty()

    fun setActiveProvider(id: String) {
        launcherMMKV().encode(KEY_ACTIVE_PROVIDER, id)
    }

    fun activeProvider(): AiProviderConfig? {
        val list = providers()
        return list.firstOrNull { it.id == activeProviderId() } ?: list.firstOrNull()
    }

    fun setActiveModel(providerId: String, model: String) {
        launcherMMKV().encode(KEY_MODEL_PREFIX + providerId, model.trim())
    }

    fun activeModel(provider: AiProviderConfig? = activeProvider()): String {
        provider ?: return ""
        val saved = launcherMMKV().decodeString(KEY_MODEL_PREFIX + provider.id).orEmpty()
        return saved.takeIf { it.isNotBlank() }
            ?: provider.defaultModel.takeIf { it.isNotBlank() }
            ?: provider.usableModels().firstOrNull()
            ?: ""
    }

    fun searchConfig(): WebSearchConfig {
        val provider = runCatching {
            WebSearchProvider.valueOf(
                launcherMMKV().decodeString(KEY_SEARCH_PROVIDER) ?: WebSearchProvider.TAVILY.name
            )
        }.getOrDefault(WebSearchProvider.TAVILY)
        return WebSearchConfig(
            provider = provider,
            endpoint = launcherMMKV().decodeString(KEY_SEARCH_ENDPOINT).orEmpty(),
            resultCount = launcherMMKV().decodeInt(KEY_SEARCH_COUNT, 5).coerceIn(1, 10)
        )
    }

    fun saveSearchConfig(config: WebSearchConfig, apiKey: String?) {
        launcherMMKV().encode(KEY_SEARCH_PROVIDER, config.provider.name)
        launcherMMKV().encode(KEY_SEARCH_ENDPOINT, config.endpoint.trim())
        launcherMMKV().encode(KEY_SEARCH_COUNT, config.resultCount.coerceIn(1, 10))
        if (apiKey != null) {
            if (apiKey.isBlank()) {
                launcherMMKV().removeValueForKey(KEY_SEARCH_SECRET)
            } else {
                launcherMMKV().encode(KEY_SEARCH_SECRET, AiSecrets.encrypt(apiKey.trim()))
            }
        }
    }

    fun searchApiKey(): String {
        val cipher = launcherMMKV().decodeString(KEY_SEARCH_SECRET) ?: return ""
        return AiSecrets.decrypt(cipher)
    }

    private fun secretKey(providerId: String) = KEY_SECRET_PREFIX + providerId
}
