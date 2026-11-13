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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.ai.AiClient
import com.movtery.zalithlauncher.ai.AiException
import com.movtery.zalithlauncher.ai.AiProviderConfig
import com.movtery.zalithlauncher.ai.AiSettingsStore
import com.movtery.zalithlauncher.ai.ChatMessage
import com.movtery.zalithlauncher.ai.ChatMode
import com.movtery.zalithlauncher.ai.ChatSession
import com.movtery.zalithlauncher.ai.ChatStore
import com.movtery.zalithlauncher.ai.TokenUsage
import com.movtery.zalithlauncher.ai.agent.AgentEvent
import com.movtery.zalithlauncher.ai.agent.AiAgent
import com.movtery.zalithlauncher.ai.tools.RiskLevel
import com.movtery.zalithlauncher.ai.tools.ToolConfirmRequest
import com.movtery.zalithlauncher.ai.tools.ToolExecutor
import com.movtery.zalithlauncher.ai.tools.ToolRegistry
import com.movtery.zalithlauncher.ai.tools.ToolRuntime
import com.movtery.zalithlauncher.ai.tools.ToolResult
import com.movtery.zalithlauncher.ai.tools.buildLauncherTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * AI 聊天页状态与 Agent 调度。
 *
 * 线程模型：网络在 IO 线程回调事件，这里只写 Compose State（线程安全快照），
 * 界面通过读取这些状态刷新。
 */
class AiChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ChatStore(application)
    private val registry: ToolRegistry = buildLauncherTools(application.applicationContext)

    var sessions by mutableStateOf<List<ChatSession>>(emptyList())
        private set
    var current by mutableStateOf<ChatSession?>(null)
        private set
    var messages = mutableStateListOf<ChatMessage>()
        private set

    /** 正在生成的正文/思考（实时） */
    var streamingText by mutableStateOf("")
        private set
    var streamingReasoning by mutableStateOf("")
        private set
    var generating by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var toolProgress by mutableStateOf<String?>(null)
        private set
    var round by mutableStateOf(0)
        private set

    /** 当前工具调用的临时卡片（生成结束后会替换为真实消息） */
    var liveTools = mutableStateListOf<LiveTool>()
        private set

    var confirmRequest by mutableStateOf<ToolConfirmRequest?>(null)
        private set
    private var confirmDeferred: CompletableDeferred<Boolean>? = null

    var activeProvider by mutableStateOf<AiProviderConfig?>(null)
        private set
    var activeModel by mutableStateOf("")
        private set

    /** 对话模式：普通 / 联网搜索 / 自动 */
    var mode by mutableStateOf(ChatMode.AUTO)
        private set

    private var job: Job? = null

    data class LiveTool(
        val id: String,
        val name: String,
        val arguments: String,
        val risk: RiskLevel,
        var summary: String = "正在执行…",
        var ok: Boolean? = null
    )

    init {
        viewModelScope.launch {
            ToolRuntime.progress.collect { text ->
                toolProgress = text
            }
        }
        refreshProviders()
        sessions = store.loadAll()
        val currentId = store.currentId()
        val loaded = currentId?.let { store.load(it) } ?: sessions.firstOrNull()
        if (loaded != null) selectSession(loaded) else newSession()
    }

    fun refreshProviders() {
        activeProvider = AiSettingsStore.activeProvider()
        activeProvider?.let { activeModel = AiSettingsStore.activeModel(it) }
    }

    fun newSession() {
        if (generating) stop()
        val provider = AiSettingsStore.activeProvider()
        val model = provider?.let { AiSettingsStore.activeModel(it) }.orEmpty()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            providerId = provider?.id.orEmpty(),
            providerName = provider?.name.orEmpty(),
            model = model
        )
        store.save(session)
        store.setCurrent(session.id)
        sessions = store.loadAll()
        selectSession(session)
    }

    fun selectSession(session: ChatSession) {
        if (generating) stop()
        current = session
        store.setCurrent(session.id)
        messages.clear()
        messages.addAll(session.messages)
        streamingText = ""
        streamingReasoning = ""
        error = null
        liveTools.clear()
        toolProgress = null
        round = 0
        refreshProviders()
        if (session.providerId.isNotBlank() && session.providerId != activeProvider?.id) {
            AiSettingsStore.setActiveProvider(session.providerId)
            refreshProviders()
        }
        if (session.model.isNotBlank()) {
            activeModel = session.model
        }
        sessions = store.loadAll()
    }

    fun deleteSession(session: ChatSession) {
        if (generating && current?.id == session.id) stop()
        store.delete(session.id)
        sessions = store.loadAll()
        if (current?.id == session.id) {
            sessions.firstOrNull()?.let { selectSession(it) } ?: newSession()
        }
    }

    fun selectProvider(providerId: String) {
        AiSettingsStore.setActiveProvider(providerId)
        refreshProviders()
        activeModel = AiSettingsStore.activeModel(activeProvider)
        current?.let { session ->
            session.providerId = providerId
            session.providerName = activeProvider?.name.orEmpty()
            session.model = activeModel
            store.save(session)
        }
    }

    fun selectMode(newMode: ChatMode) {
        mode = newMode
    }

    fun selectModel(model: String) {
        val provider = activeProvider ?: return
        activeModel = model
        AiSettingsStore.setActiveModel(provider.id, model)
        current?.let { session ->
            session.model = model
            store.save(session)
        }
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || generating) return
        val session = current ?: newSession().let { current!! }
        val provider = AiSettingsStore.activeProvider()
        if (provider == null) {
            error = "还没有配置 AI 服务商。请先在「设置 → AI 服务」中添加服务商和 API Key。"
            return
        }
        val apiKey = AiSettingsStore.apiKey(provider.id)
        if (apiKey.isBlank()) {
            error = "服务商「${provider.name}」还没有填写 API Key，请到「设置 → AI 服务」补充。"
            return
        }
        val model = activeModel.ifBlank { AiSettingsStore.activeModel(provider) }
        if (model.isBlank()) {
            error = "服务商「${provider.name}」还没有选择模型，请到「设置 → AI 服务」补充。"
            return
        }
        val client = AiClient(provider, apiKey, model)
        if (job?.isActive == true) return

        val userMessage = ChatMessage(role = "user", content = content)
        messages += userMessage
        session.messages += userMessage
        if (session.title == "新对话") session.title = content.take(24)
        session.providerId = provider.id
        session.providerName = provider.name
        session.model = model
        store.save(session)
        sessions = store.loadAll()

        error = null
        streamingText = ""
        streamingReasoning = ""
        liveTools.clear()
        round = 0
        generating = true

        job = viewModelScope.launch {
            val executor = ToolExecutor(registry) { request -> awaitConfirmation(request) }
            val modeAtSend = mode
            val allowedTools = if (modeAtSend == ChatMode.CHAT) {
                registry.names().filterNot { it == "web_search" }.toSet()
            } else null
            val agent = AiAgent(
                client = client,
                registry = registry,
                executor = executor,
                maxRounds = 12,
                mode = modeAtSend,
                allowedTools = allowedTools
            )
            val events = kotlinx.coroutines.channels.Channel<AgentEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val collector = launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                for (event in events) handleEvent(event)
            }
            var produced: List<ChatMessage> = emptyList()
            try {
                produced = agent.run(session.messages.toList()) { event -> events.trySend(event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = friendlyError(e)
            } finally {
                events.close()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    collector.join()
                }
            }
            if (produced.isEmpty() && streamingText.isNotBlank()) {
                produced = listOf(ChatMessage(role = "assistant", content = streamingText))
            }

            if (produced.isNotEmpty()) {
                session.messages.addAll(produced)
            }
            session.updatedAt = System.currentTimeMillis()
            store.save(session)
            sessions = store.loadAll()
            current = session
            messages.clear()
            messages.addAll(session.messages)

            streamingText = ""
            streamingReasoning = ""
            liveTools.clear()
            toolProgress = null
            generating = false
            confirmRequest = null
            confirmDeferred?.complete(false)
            confirmDeferred = null
        }
    }

    /** 重新生成最后一条回答 */
    fun regenerate() {
        if (generating) return
        val session = current ?: return
        val index = session.messages.indexOfLast { it.role == "assistant" }
        if (index < 0) return
        val cut = session.messages.subList(index, session.messages.size).toList()
        session.messages.removeAll(cut)
        store.save(session)
        messages.clear()
        messages.addAll(session.messages)
        // 用最后一条用户消息重新发起
        val lastUser = session.messages.lastOrNull { it.role == "user" } ?: return
        session.messages.remove(lastUser)
        messages.remove(lastUser)
        store.save(session)
        send(lastUser.content)
    }

    fun stop() {
        job?.cancel()
        job = null
        generating = false
        streamingText = ""
        streamingReasoning = ""
        liveTools.clear()
        toolProgress = null
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> streamingText += event.text
            is AgentEvent.ReasoningDelta -> streamingReasoning += event.text
            is AgentEvent.RoundStarted -> round = event.round
            is AgentEvent.ToolStarted -> liveTools += LiveTool(
                id = "${event.name}_${System.nanoTime()}",
                name = event.name,
                arguments = event.arguments,
                risk = event.risk
            )
            is AgentEvent.ToolFinished -> liveTools.lastOrNull { it.name == event.name && it.ok == null }?.apply {
                summary = event.summary
                ok = event.ok
            }
            is AgentEvent.Usage -> current?.let { session ->
                session.promptTokens += event.usage.prompt
                session.completionTokens += event.usage.completion
            }
            is AgentEvent.Completed -> Unit
            is AgentEvent.Failed -> error = event.message
            AgentEvent.Stopped -> Unit
        }
    }

    private suspend fun awaitConfirmation(request: ToolConfirmRequest): Boolean {
        confirmRequest = request
        val deferred = CompletableDeferred<Boolean>()
        confirmDeferred = deferred
        val result = deferred.await()
        confirmRequest = null
        confirmDeferred = null
        return result
    }

    fun answerConfirmation(allow: Boolean) {
        confirmDeferred?.complete(allow)
        confirmRequest = null
    }

    fun clearError() {
        error = null
    }

    fun dismissToolProgress() {
        toolProgress = null
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is AiException -> when (t.kind) {
            AiException.Kind.AUTH -> "API Key 无效或无权限，请到「设置 → AI 服务」检查密钥。"
            AiException.Kind.RATE_LIMIT -> "请求被服务商限流，自动重试后仍未成功，请稍后再试。"
            AiException.Kind.NETWORK -> "网络连接失败：${t.message ?: "请检查网络或代理"}。"
            AiException.Kind.MODEL_NOT_FOUND -> "接口或模型不存在：${t.message}"
            AiException.Kind.SERVER -> "服务商暂时不可用：${t.message}"
            else -> t.message ?: "AI 请求失败"
        }
        else -> t.message ?: "AI 请求失败：${t::class.java.simpleName}"
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }
}
