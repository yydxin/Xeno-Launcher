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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * OpenAI 兼容 / Anthropic 的流式客户端。
 *
 * 设计要点：
 * - 只依赖项目已有的 OkHttp（不引入第二套网络栈）；
 * - API Key 只进请求头，异常信息中绝不出现；
 * - 支持协程取消（停止生成会 cancel OkHttp Call）；
 * - 429 退避重试，401/403 明确报鉴权失败，5xx/网络错误有限重试。
 */
class AiClient(
    private val provider: AiProviderConfig,
    private val apiKey: String,
    private val model: String
) {
    private val gson = com.google.gson.Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25L, TimeUnit.SECONDS)
            .readTimeout(240L, TimeUnit.SECONDS)
            .writeTimeout(30L, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl: String get() = provider.normalizedBaseUrl()

    /**
     * 流式对话。
     * @param onDelta 正文增量（从 IO 线程回调，界面需自行切主线程或写 StateFlow）
     * @param onReasoning 思考增量
     */
    suspend fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {}
    ): AssistantTurn {
        if (apiKey.isBlank()) throw AiException("还没有配置 API Key", kind = AiException.Kind.AUTH)
        if (model.isBlank()) throw AiException("还没有选择模型", kind = AiException.Kind.BAD_REQUEST)
        if (baseUrl.isBlank()) throw AiException("还没有配置 Base URL", kind = AiException.Kind.BAD_REQUEST)

        val body = when (provider.format) {
            AiApiFormat.OPENAI -> openAiBody(messages, tools, stream = true)
            AiApiFormat.ANTHROPIC -> anthropicBody(messages, tools, stream = true)
        }
        val url = when (provider.format) {
            AiApiFormat.OPENAI -> joinApiUrl(baseUrl, "chat/completions")
            AiApiFormat.ANTHROPIC -> joinApiUrl(baseUrl, "messages")
        }

        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val request = buildRequest(url, body)
                return withContext(Dispatchers.IO) { executeStream(request, onDelta, onReasoning) }
            } catch (e: AiException) {
                val retryable = e.kind == AiException.Kind.RATE_LIMIT ||
                    e.kind == AiException.Kind.SERVER ||
                    e.kind == AiException.Kind.NETWORK
                if (!retryable || attempt >= MAX_RETRIES) throw e
                val waitMs = when (e.kind) {
                    AiException.Kind.RATE_LIMIT -> 1500L * (attempt + 1)
                    else -> 800L * (attempt + 1)
                }
                attempt++
                delay(waitMs)
            }
        }
    }

    /** 拉取服务商模型列表 */
    suspend fun fetchModels(): List<String> = withContext(Dispatchers.IO) {
        val url = joinApiUrl(baseUrl, "models")
        val request = buildRequest(url, null, method = "GET")
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, text)
            val root = parseJsonObjectOrNull(text)
                ?: throw AiException("模型列表返回的不是 JSON")
            val data = root.arrOrNull("data") ?: root.arrOrNull("models")
                ?: throw AiException("模型列表格式无法识别")
            data.mapNotNull { element ->
                val obj = element.objOrNull() ?: return@mapNotNull null
                obj.strOrNull("id") ?: obj.strOrNull("name")
            }.distinct()
        }
    }

    /** 连接测试：能列出模型或完成一次最小请求即视为成功 */
    suspend fun testConnection(): String {
        runCatching { fetchModels() }.onSuccess { models ->
            if (models.isNotEmpty()) return "连接成功，可获取 ${models.size} 个模型"
        }
        // 部分服务商不开放 /models，退化为一次极小的非流式请求
        val turn = chatOnce(
            listOf(ChatMessage(role = "user", content = "ping")),
            emptyList(),
            maxTokens = 16
        )
        return if (turn.content.isNotBlank() || turn.toolCalls.isNotEmpty()) "连接成功" else "连接成功（返回为空）"
    }

    /** 工具调用能力测试：模型返回 tool_call 即视为支持 */
    suspend fun testToolCalling(): Boolean {
        val tool = ToolSpec(
            name = "ping_tool",
            description = "仅用于测试工具调用能力，请调用它并传入 message=ping。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("message", JsonObject().apply { addProperty("type", "string") })
                })
                add("required", JsonArray().apply { add("message") })
            }
        )
        return runCatching {
            val turn = chatOnce(
                listOf(ChatMessage(role = "user", content = "请调用 ping_tool 工具，参数 message=ping。")),
                listOf(tool),
                maxTokens = 128
            )
            turn.toolCalls.any { it.name == "ping_tool" }
        }.getOrDefault(false)
    }

    /**
     * 非流式补全，供汉化、摘要等只需要最终文本的功能使用。
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        maxTokens: Int = 4096
    ): AssistantTurn = chatOnce(messages, emptyList(), maxTokens)

    private suspend fun chatOnce(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        maxTokens: Int
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val body = when (provider.format) {
            AiApiFormat.OPENAI -> openAiBody(messages, tools, stream = false)
            AiApiFormat.ANTHROPIC -> anthropicBody(messages, tools, stream = false, maxTokens = maxTokens)
        }
        val url = when (provider.format) {
            AiApiFormat.OPENAI -> joinApiUrl(baseUrl, "chat/completions")
            AiApiFormat.ANTHROPIC -> joinApiUrl(baseUrl, "messages")
        }
        client.newCall(buildRequest(url, body)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError(response.code, text)
            parseNonStream(text)
        }
    }

    private fun buildRequest(url: String, body: String?, method: String = "POST"): Request {
        val builder = Request.Builder().url(url)
            .header("Accept", "text/event-stream, application/json")
        when (provider.format) {
            AiApiFormat.OPENAI -> builder.header("Authorization", "Bearer $apiKey")
            AiApiFormat.ANTHROPIC -> {
                builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
        }
        return if (method == "GET") {
            builder.get().build()
        } else {
            builder.post((body ?: "{}").toRequestBody("application/json".toMediaType())).build()
        }
    }

    // ------------------------------------------------------------------ request bodies

    private fun openAiBody(messages: List<ChatMessage>, tools: List<ToolSpec>, stream: Boolean): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("stream", stream)
        if (stream) {
            root.add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
        }
        root.add("messages", JsonArray().apply {
            messages.forEach { message ->
                add(JsonObject().apply {
                    addProperty("role", message.role)
                    when (message.role) {
                        "assistant" -> {
                            if (message.content.isNotBlank()) addProperty("content", message.content)
                            // DeepSeek 思考模型要求回传 reasoning_content；其它服务商不接受未知字段
                            if (provider.isDeepSeek() && message.reasoning.isNotBlank()) {
                                addProperty("reasoning_content", message.reasoning)
                            }
                            if (message.toolCalls.isNotEmpty()) {
                                add("tool_calls", JsonArray().apply {
                                    message.toolCalls.forEach { call ->
                                        add(JsonObject().apply {
                                            addProperty("id", call.id)
                                            addProperty("type", "function")
                                            add("function", JsonObject().apply {
                                                addProperty("name", call.name)
                                                addProperty("arguments", call.arguments.ifBlank { "{}" })
                                            })
                                        })
                                    }
                                })
                            }
                        }
                        "tool" -> {
                            addProperty("content", message.content)
                            message.toolCallId?.let { addProperty("tool_call_id", it) }
                        }
                        else -> addProperty("content", message.content)
                    }
                })
            }
        })
        if (tools.isNotEmpty()) {
            root.add("tools", JsonArray().apply {
                tools.forEach { spec ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", spec.name)
                            addProperty("description", spec.description)
                            add("parameters", spec.parameters)
                        })
                    })
                }
            })
            root.addProperty("tool_choice", "auto")
        }
        return gson.toJson(root)
    }

    private fun anthropicBody(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        stream: Boolean,
        maxTokens: Int = 8192
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("max_tokens", maxTokens)
        root.addProperty("stream", stream)
        val system = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
        system?.let { root.addProperty("system", it) }

        root.add("messages", JsonArray().apply {
            messages.filter { it.role != "system" }.forEach { message ->
                when (message.role) {
                    "tool" -> {
                        // 工具结果必须放在 user 消息的 tool_result 块里
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            add("content", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "tool_result")
                                    addProperty("tool_use_id", message.toolCallId.orEmpty())
                                    addProperty("content", message.content)
                                })
                            })
                        })
                    }
                    "assistant" -> {
                        add(JsonObject().apply {
                            addProperty("role", "assistant")
                            add("content", JsonArray().apply {
                                if (message.content.isNotBlank()) {
                                    add(JsonObject().apply {
                                        addProperty("type", "text")
                                        addProperty("text", message.content)
                                    })
                                }
                                message.toolCalls.forEach { call ->
                                    add(JsonObject().apply {
                                        addProperty("type", "tool_use")
                                        addProperty("id", call.id)
                                        addProperty("name", call.name)
                                        add("input", parseJsonObject(call.arguments))
                                    })
                                }
                            })
                        })
                    }
                    else -> {
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            add("content", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", message.content)
                                })
                            })
                        })
                    }
                }
            }
        })
        if (tools.isNotEmpty()) {
            root.add("tools", JsonArray().apply {
                tools.forEach { spec ->
                    add(JsonObject().apply {
                        addProperty("name", spec.name)
                        addProperty("description", spec.description)
                        add("input_schema", spec.parameters)
                    })
                }
            })
        }
        return gson.toJson(root)
    }

    // ------------------------------------------------------------------ streaming

    private suspend fun executeStream(
        request: Request,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): AssistantTurn {
        val call = client.newCall(request)
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty().take(600)
                    throw httpError(response.code, text)
                }
                val source = response.body?.source() ?: throw AiException("服务商返回了空响应")
                return when (provider.format) {
                    AiApiFormat.OPENAI -> parseOpenAiStream(source, onDelta, onReasoning)
                    AiApiFormat.ANTHROPIC -> parseAnthropicStream(source, onDelta, onReasoning)
                }
            }
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("已停止生成")
            throw AiException("网络错误：${e.message ?: "连接中断"}", e, AiException.Kind.NETWORK)
        } finally {
            handle?.dispose()
        }
    }

    private fun parseOpenAiStream(
        source: okio.BufferedSource,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): AssistantTurn {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var usage = TokenUsage()
        var finish = ""
        var sawData = false
        val calls = sortedMapOf<Int, MutableCall>()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            val delta = OpenAiEventParser.parse(data) ?: continue
            sawData = true
            delta.usage?.let { usage = it }
            delta.finishReason?.let { finish = it }
            delta.content?.takeIf { it.isNotEmpty() }?.let {
                content.append(it)
                onDelta(it)
            }
            delta.reasoning?.takeIf { it.isNotEmpty() }?.let {
                reasoning.append(it)
                onReasoning(it)
            }
            delta.toolCalls.forEach { fragment ->
                val call = calls.getOrPut(fragment.index) { MutableCall() }
                fragment.id?.let { call.id = it }
                fragment.name?.let { call.name += it }
                fragment.arguments?.let { call.arguments += it }
            }
        }
        if (!sawData && content.isEmpty() && calls.isEmpty()) {
            throw AiException("服务商返回了无法识别的内容（不是 SSE 数据），请检查 Base URL 与接口格式")
        }
        return AssistantTurn(
            content = content.toString(),
            reasoning = reasoning.toString(),
            toolCalls = calls.values.filter { it.name.isNotBlank() }.map {
                ToolCall(
                    id = it.id.ifBlank { "call_${System.nanoTime()}" },
                    name = it.name,
                    arguments = it.arguments.ifBlank { "{}" }
                )
            },
            usage = usage,
            finishReason = finish
        )
    }

    private fun parseAnthropicStream(
        source: okio.BufferedSource,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): AssistantTurn {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var usage = TokenUsage()
        var finish = ""
        var sawData = false
        val blocks = sortedMapOf<Int, MutableBlock>()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val event = AnthropicEventParser.parse(data) ?: continue
            sawData = true
            when (event) {
                is AnthropicEvent.MessageStart -> {
                    usage = usage.copy(prompt = event.promptTokens)
                }
                is AnthropicEvent.BlockStart -> {
                    val block = blocks.getOrPut(event.index) { MutableBlock() }
                    block.type = event.type
                    block.id = event.id.orEmpty()
                    block.name = event.name.orEmpty()
                    if (event.type == "text") {
                        event.text?.takeIf { it.isNotBlank() }?.let {
                            content.append(it)
                            onDelta(it)
                        }
                    }
                }
                is AnthropicEvent.BlockDelta -> {
                    when (event.type) {
                        "text_delta" -> event.text?.let {
                            content.append(it)
                            onDelta(it)
                        }
                        "thinking_delta" -> event.thinking?.let {
                            reasoning.append(it)
                            onReasoning(it)
                        }
                        "input_json_delta" -> {
                            blocks.getOrPut(event.index) { MutableBlock() }
                                .arguments.append(event.partialJson.orEmpty())
                        }
                    }
                }
                is AnthropicEvent.MessageDelta -> {
                    event.stopReason?.let { finish = it }
                    event.outputTokens?.let { usage = usage.copy(completion = it) }
                }
                is AnthropicEvent.ErrorEvent -> throw AiException(event.message)
            }
        }
        if (!sawData && content.isEmpty() && blocks.isEmpty()) {
            throw AiException("服务商返回了无法识别的内容（不是 SSE 数据），请检查 Base URL 与接口格式")
        }
        return AssistantTurn(
            content = content.toString(),
            reasoning = reasoning.toString(),
            toolCalls = blocks.values.filter { it.type == "tool_use" && it.name.isNotBlank() }.map {
                ToolCall(
                    id = it.id.ifBlank { "call_${System.nanoTime()}" },
                    name = it.name,
                    arguments = it.arguments.toString().ifBlank { "{}" }
                )
            },
            usage = usage,
            finishReason = finish
        )
    }

    private fun parseNonStream(text: String): AssistantTurn {
        val root = parseJsonObjectOrNull(text)
        if (root != null) {
            root.objOrNull("error")?.let { error ->
                throw AiException(error.strOrNull("message") ?: "服务商返回错误")
            }
        }
        return when (provider.format) {
            AiApiFormat.OPENAI -> {
                val choices = root?.arrOrNull("choices")
                if (choices == null || choices.size() == 0) {
                    throw AiException("服务商返回格式异常：缺少 choices")
                }
                val message = choices.get(0).objOrNull()?.objOrNull("message")
                    ?: throw AiException("服务商返回格式异常：缺少 message")
                val calls = message.arrOrNull("tool_calls")?.mapNotNull { element ->
                    val obj = element.objOrNull() ?: return@mapNotNull null
                    val function = obj.objOrNull("function") ?: return@mapNotNull null
                    ToolCall(
                        id = obj.strOrNull("id") ?: "call_${System.nanoTime()}",
                        name = function.strOrNull("name").orEmpty(),
                        arguments = function.strOrNull("arguments") ?: "{}"
                    )
                }.orEmpty()
                val usage = root.objOrNull("usage")
                AssistantTurn(
                    content = message.strOrNull("content").orEmpty(),
                    reasoning = message.strOrNull("reasoning_content").orEmpty(),
                    toolCalls = calls,
                    usage = TokenUsage(
                        prompt = usage?.longOrNull("prompt_tokens") ?: 0,
                        completion = usage?.longOrNull("completion_tokens") ?: 0
                    )
                )
            }
            AiApiFormat.ANTHROPIC -> {
                val blocks = root?.arrOrNull("content") ?: JsonArray()
                val textBuilder = StringBuilder()
                val calls = mutableListOf<ToolCall>()
                blocks.forEach { element ->
                    val obj = element.objOrNull() ?: return@forEach
                    when (obj.strOrNull("type")) {
                        "text" -> textBuilder.append(obj.strOrNull("text").orEmpty())
                        "tool_use" -> calls.add(
                            ToolCall(
                                id = obj.strOrNull("id").orEmpty(),
                                name = obj.strOrNull("name").orEmpty(),
                                arguments = obj.get("input")?.toString() ?: "{}"
                            )
                        )
                    }
                }
                val usage = root?.objOrNull("usage")
                AssistantTurn(
                    content = textBuilder.toString(),
                    reasoning = "",
                    toolCalls = calls,
                    usage = TokenUsage(
                        prompt = usage?.longOrNull("input_tokens") ?: 0,
                        completion = usage?.longOrNull("output_tokens") ?: 0
                    )
                )
            }
        }
    }

    private fun httpError(code: Int, body: String): AiException {
        val brief = extractApiErrorMessage(body) ?: "服务端未返回可读错误信息"
        val kind = when (code) {
            401, 403 -> AiException.Kind.AUTH
            429 -> AiException.Kind.RATE_LIMIT
            404 -> AiException.Kind.MODEL_NOT_FOUND
            400, 422 -> AiException.Kind.BAD_REQUEST
            in 500..599 -> AiException.Kind.SERVER
            else -> AiException.Kind.OTHER
        }
        val message = when (kind) {
            AiException.Kind.AUTH -> "API Key 无效或无权限（HTTP $code）：$brief"
            AiException.Kind.RATE_LIMIT -> "请求过于频繁，已被限流（HTTP 429）"
            AiException.Kind.MODEL_NOT_FOUND -> "接口或模型不存在（HTTP 404）：$brief"
            AiException.Kind.BAD_REQUEST -> "请求参数被拒绝（HTTP $code）：$brief"
            AiException.Kind.SERVER -> "服务商内部错误（HTTP $code）：$brief"
            else -> "AI 接口返回 HTTP $code：$brief"
        }
        return AiException(message, kind = kind)
    }

    private fun parseJsonObject(text: String): JsonObject =
        runCatching { JsonParser.parseString(text.ifBlank { "{}" }).asJsonObject }
            .getOrElse { JsonObject() }

    private fun AiProviderConfig.isDeepSeek(): Boolean =
        baseUrl.contains("deepseek", ignoreCase = true) || name.contains("deepseek", ignoreCase = true)

    private data class MutableCall(
        var id: String = "",
        var name: String = "",
        var arguments: String = ""
    )

    private data class MutableBlock(
        var type: String = "",
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    companion object {
        private const val MAX_RETRIES = 2
    }
}
