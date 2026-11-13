/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ai.AiClient
import com.movtery.zalithlauncher.ai.AiException
import com.movtery.zalithlauncher.ai.AiProviderConfig
import com.movtery.zalithlauncher.ai.AiSettingsStore
import com.movtery.zalithlauncher.ai.WebSearchConfig
import com.movtery.zalithlauncher.ai.WebSearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 设置页展示的一条提示。[textRes] 始终指向字符串资源，
 * 避免把界面文案硬编码在 Kotlin 里，也方便之后补充其它语言。
 */
data class AiSettingsMessage(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
    val isError: Boolean = false
)

/** 设置页当前正在执行的耗时操作 */
enum class AiSettingsBusy {
    SAVE_PROVIDER,
    DELETE_PROVIDER,
    FETCH_MODELS,
    TEST_CONNECTION,
    TEST_TOOLS,
    SAVE_SEARCH
}

/**
 * AI 服务设置页的 ViewModel。
 *
 * 这里只做三件事：
 * 1. 读写 [AiSettingsStore]（服务商、当前选择、联网搜索配置）；
 * 2. 直接调用 [AiClient] 的拉取模型/测试连接/测试工具能力；
 * 3. 把异常整理成可读的提示，界面只负责按资源 ID 显示。
 */
class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    var providers by mutableStateOf<List<AiProviderConfig>>(emptyList())
        private set

    var activeProvider by mutableStateOf<AiProviderConfig?>(null)
        private set

    var searchConfig by mutableStateOf(WebSearchConfig())
        private set

    /** 已经保存过 API Key 的服务商 id，仅用于界面提示，不暴露 Key 本身 */
    var savedKeyIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var searchKeySaved by mutableStateOf(false)
        private set

    var busyAction by mutableStateOf<AiSettingsBusy?>(null)
        private set

    val busy: Boolean get() = busyAction != null

    var message by mutableStateOf<AiSettingsMessage?>(null)
        private set

    /** 最近一次成功拉取到的模型，设置页用它回填编辑框 */
    var fetchedModels by mutableStateOf<List<String>>(emptyList())
        private set

    /** 即使连续两次拉到同样的模型，也需要让界面重新回填 */
    var fetchedModelsVersion by mutableStateOf(0)
        private set

    init {
        refresh()
    }

    fun refresh() {
        val list = AiSettingsStore.providers()
        providers = list
        activeProvider = AiSettingsStore.activeProvider()
        searchConfig = AiSettingsStore.searchConfig()
        savedKeyIds = list
            .filter { AiSettingsStore.hasKey(it.id) }
            .map { it.id }
            .toSet()
        searchKeySaved = AiSettingsStore.searchApiKey().isNotBlank()
    }

    /**
     * 保存服务商。
     * @param apiKey null 表示不修改已保存的 Key；空字符串表示清除。
     * @param onSaved 保存成功后在主线程回调，便于界面关闭编辑弹窗。
     */
    fun saveProvider(
        config: AiProviderConfig,
        apiKey: String?,
        onSaved: (() -> Unit)? = null
    ) {
        if (busy) return
        val normalized = normalizeProvider(config)
        validateProvider(normalized)?.let {
            message = it
            return
        }
        val toSave = if (normalized.id.isBlank()) {
            normalized.copy(id = AiSettingsStore.newProviderId())
        } else normalized
        busyAction = AiSettingsBusy.SAVE_PROVIDER
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AiSettingsStore.saveProvider(toSave, apiKey).getOrThrow()
                }
                refresh()
                message = AiSettingsMessage(
                    textRes = R.string.ai_settings_saved,
                    args = listOf(toSave.name)
                )
                onSaved?.invoke()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = errorMessage(e)
            } finally {
                busyAction = null
            }
        }
    }

    fun deleteProvider(id: String) {
        if (busy) return
        val name = providers.firstOrNull { it.id == id }?.name.orEmpty()
        busyAction = AiSettingsBusy.DELETE_PROVIDER
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AiSettingsStore.deleteProvider(id)
                }
                refresh()
                fetchedModels = emptyList()
                message = AiSettingsMessage(
                    textRes = R.string.ai_settings_delete_done,
                    args = listOf(name)
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = errorMessage(e)
            } finally {
                busyAction = null
            }
        }
    }

    fun setActive(providerId: String) {
        if (busy) return
        if (providers.none { it.id == providerId }) return
        AiSettingsStore.setActiveProvider(providerId)
        refresh()
    }

    /**
     * 拉取模型列表。成功时写入 [fetchedModels]，界面按 [fetchedModelsVersion] 回填编辑框。
     * @param apiKey 编辑框里刚输入但尚未保存的 Key；为 null/空时使用已保存的 Key。
     */
    fun fetchModels(config: AiProviderConfig, apiKey: String?) {
        if (busy) return
        val normalized = normalizeProvider(config)
        val urlError = validateBaseUrl(normalized)
        if (urlError != null) {
            message = urlError
            return
        }
        val key = resolveApiKey(normalized.id, apiKey)
        if (key.isBlank()) {
            message = AiSettingsMessage(R.string.ai_settings_error_key_missing, isError = true)
            return
        }

        fetchedModels = emptyList()
        busyAction = AiSettingsBusy.FETCH_MODELS
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    AiClient(
                        provider = normalized,
                        apiKey = key,
                        model = normalized.defaultModel.ifBlank {
                            normalized.models.firstOrNull().orEmpty()
                        }
                    ).fetchModels()
                }
                if (models.isEmpty()) {
                    message = AiSettingsMessage(
                        textRes = R.string.ai_settings_models_empty,
                        isError = false
                    )
                } else {
                    fetchedModels = models
                    fetchedModelsVersion++
                    message = AiSettingsMessage(
                        textRes = R.string.ai_settings_models_fetched,
                        args = listOf(models.size)
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = errorMessage(e)
            } finally {
                busyAction = null
            }
        }
    }

    /**
     * 测试连接。允许使用编辑框中尚未保存的配置与 Key。
     */
    fun testConnection(config: AiProviderConfig, apiKey: String?) {
        if (busy) return
        val normalized = normalizeProvider(config)
        val urlError = validateBaseUrl(normalized)
        if (urlError != null) {
            message = urlError
            return
        }
        val key = resolveApiKey(normalized.id, apiKey)
        if (key.isBlank()) {
            message = AiSettingsMessage(R.string.ai_settings_error_key_missing, isError = true)
            return
        }

        busyAction = AiSettingsBusy.TEST_CONNECTION
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AiClient(
                        provider = normalized,
                        apiKey = key,
                        model = normalized.defaultModel.ifBlank {
                            normalized.models.firstOrNull().orEmpty()
                        }
                    ).testConnection()
                }
                message = AiSettingsMessage(
                    textRes = R.string.ai_settings_test_connection_ok,
                    args = listOf(normalized.displayName())
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = errorMessage(e)
            } finally {
                busyAction = null
            }
        }
    }

    /**
     * 测试模型是否具备工具调用能力。
     */
    fun testToolCalling(config: AiProviderConfig, apiKey: String?) {
        if (busy) return
        val normalized = normalizeProvider(config)
        val urlError = validateBaseUrl(normalized)
        if (urlError != null) {
            message = urlError
            return
        }
        val key = resolveApiKey(normalized.id, apiKey)
        if (key.isBlank()) {
            message = AiSettingsMessage(R.string.ai_settings_error_key_missing, isError = true)
            return
        }

        busyAction = AiSettingsBusy.TEST_TOOLS
        viewModelScope.launch {
            try {
                val supported = withContext(Dispatchers.IO) {
                    AiClient(
                        provider = normalized,
                        apiKey = key,
                        model = normalized.defaultModel.ifBlank {
                            normalized.models.firstOrNull().orEmpty()
                        }
                    ).testToolCalling()
                }
                message = if (supported) {
                    AiSettingsMessage(
                        textRes = R.string.ai_settings_tool_test_ok,
                        args = listOf(normalized.displayName())
                    )
                } else {
                    AiSettingsMessage(
                        textRes = R.string.ai_settings_tool_test_unsupported,
                        args = listOf(normalized.displayName()),
                        isError = true
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = errorMessage(e)
            } finally {
                busyAction = null
            }
        }
    }

    /**
     * 保存联网搜索设置。
     * @param apiKey null 表示不修改；空字符串表示清除。
     */
    fun saveSearchConfig(
        config: WebSearchConfig,
        apiKey: String?,
        onSaved: (() -> Unit)? = null
    ) {
        if (busy) return
        val normalized = config.copy(
            endpoint = config.endpoint.trim(),
            resultCount = config.resultCount.coerceIn(1, 10)
        )
        if (normalized.provider == WebSearchProvider.CUSTOM && normalized.endpoint.isBlank()) {
            message = AiSettingsMessage(R.string.ai_settings_error_endpoint_empty, isError = true)
            return
        }
        busyAction = AiSettingsBusy.SAVE_SEARCH
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AiSettingsStore.saveSearchConfig(normalized, apiKey)
                }
                refresh()
                message = AiSettingsMessage(R.string.ai_settings_search_saved)
                onSaved?.invoke()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = errorMessage(e)
            } finally {
                busyAction = null
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    /** 打开编辑弹窗时清掉上一次的拉取结果，避免回填到别的服务商 */
    fun clearFetchedModels() {
        fetchedModels = emptyList()
        fetchedModelsVersion = 0
    }

    // ------------------------------------------------------------------ helpers

    private fun normalizeProvider(config: AiProviderConfig): AiProviderConfig {
        val models = config.models
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val defaultModel = config.defaultModel.trim().ifBlank { models.firstOrNull().orEmpty() }
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        return config.copy(
            name = config.name.trim(),
            baseUrl = baseUrl,
            models = models,
            defaultModel = defaultModel
        )
    }

    private fun validateProvider(config: AiProviderConfig): AiSettingsMessage? {
        if (config.name.isBlank()) {
            return AiSettingsMessage(R.string.ai_settings_error_name_empty, isError = true)
        }
        validateBaseUrl(config)?.let { return it }
        if (config.models.isEmpty()) {
            return AiSettingsMessage(R.string.ai_settings_error_models_empty, isError = true)
        }
        if (config.defaultModel.isBlank()) {
            return AiSettingsMessage(R.string.ai_settings_error_model_empty, isError = true)
        }
        return null
    }

    private fun validateBaseUrl(config: AiProviderConfig): AiSettingsMessage? {
        val url = config.baseUrl
        return when {
            url.isBlank() -> AiSettingsMessage(R.string.ai_settings_error_url_empty, isError = true)
            !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true) ->
                AiSettingsMessage(R.string.ai_settings_error_url_invalid, isError = true)
            else -> null
        }
    }

    private fun resolveApiKey(providerId: String, editingKey: String?): String {
        val typed = editingKey?.trim().orEmpty()
        if (typed.isNotBlank()) return typed
        if (providerId.isBlank()) return ""
        return AiSettingsStore.apiKey(providerId).trim()
    }

    private fun AiProviderConfig.displayName(): String =
        name.ifBlank { baseUrl.ifBlank { id } }

    private fun errorMessage(throwable: Throwable): AiSettingsMessage {
        val chain = generateSequence(throwable) { it.cause }.toList()

        if (throwable is AiException) {
            when (throwable.kind) {
                AiException.Kind.AUTH ->
                    return AiSettingsMessage(R.string.ai_settings_error_auth, isError = true)
                AiException.Kind.RATE_LIMIT ->
                    return AiSettingsMessage(R.string.ai_settings_error_rate_limit, isError = true)
                AiException.Kind.NETWORK ->
                    return networkMessage(chain, throwable)
                AiException.Kind.SERVER ->
                    return AiSettingsMessage(R.string.ai_settings_error_server, isError = true)
                AiException.Kind.MODEL_NOT_FOUND ->
                    return AiSettingsMessage(
                        textRes = R.string.ai_settings_error_not_found,
                        args = listOf(throwable.message.orEmpty()),
                        isError = true
                    )
                AiException.Kind.BAD_REQUEST ->
                    return AiSettingsMessage(
                        textRes = R.string.ai_settings_error_bad_request,
                        args = listOf(throwable.message.orEmpty()),
                        isError = true
                    )
                else -> Unit
            }
        }

        if (chain.any { it is SocketTimeoutException } ||
            throwable.message.orEmpty().contains("timeout", ignoreCase = true) ||
            throwable.message.orEmpty().contains("超时")
        ) {
            return AiSettingsMessage(R.string.ai_settings_error_timeout, isError = true)
        }
        if (chain.any { it is UnknownHostException || it is ConnectException }) {
            return AiSettingsMessage(R.string.ai_settings_error_offline, isError = true)
        }
        if (throwable is IOException) {
            return AiSettingsMessage(
                textRes = R.string.ai_settings_error_network,
                args = listOf(throwable.message.orEmpty()),
                isError = true
            )
        }
        return AiSettingsMessage(
            textRes = R.string.ai_settings_error_generic,
            args = listOf(throwable.message ?: throwable::class.java.simpleName),
            isError = true
        )
    }

    private fun networkMessage(
        chain: List<Throwable>,
        throwable: AiException
    ): AiSettingsMessage {
        if (chain.any { it is SocketTimeoutException } ||
            throwable.message.orEmpty().contains("timeout", ignoreCase = true) ||
            throwable.message.orEmpty().contains("超时")
        ) {
            return AiSettingsMessage(R.string.ai_settings_error_timeout, isError = true)
        }
        if (chain.any { it is UnknownHostException || it is ConnectException }) {
            return AiSettingsMessage(R.string.ai_settings_error_offline, isError = true)
        }
        return AiSettingsMessage(
            textRes = R.string.ai_settings_error_network,
            args = listOf(throwable.message.orEmpty()),
            isError = true
        )
    }
}
