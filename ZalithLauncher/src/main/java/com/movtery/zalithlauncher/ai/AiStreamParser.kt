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

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** OpenAI 流式片段里的工具调用分片 */
data class ToolCallFragment(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String?
)

/** OpenAI 单个 data: 行的解析结果 */
data class OpenAiDelta(
    val content: String? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallFragment> = emptyList(),
    val usage: TokenUsage? = null,
    val finishReason: String? = null
)

/** Anthropic 单个 data: 行的解析结果 */
sealed interface AnthropicEvent {
    data class MessageStart(val promptTokens: Long) : AnthropicEvent
    data class BlockStart(
        val index: Int,
        val type: String,
        val id: String?,
        val name: String?,
        val text: String?
    ) : AnthropicEvent
    data class BlockDelta(
        val index: Int,
        val type: String,
        val text: String?,
        val thinking: String?,
        val partialJson: String?
    ) : AnthropicEvent
    data class MessageDelta(val stopReason: String?, val outputTokens: Long?) : AnthropicEvent
    data class ErrorEvent(val message: String) : AnthropicEvent
}

/**
 * 纯函数解析器，便于 JVM 单测覆盖服务商各种“脏”响应。
 * 解析失败返回 null，绝不抛类型转换异常。
 */
object OpenAiEventParser {
    fun parse(data: String): OpenAiDelta? {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]" || trimmed.startsWith(":")) return null
        val chunk = parseJsonObjectOrNull(trimmed) ?: return null

        val usage = chunk.objOrNull("usage")?.let {
            TokenUsage(
                prompt = it.longOrNull("prompt_tokens") ?: 0,
                completion = it.longOrNull("completion_tokens") ?: 0
            )
        }
        val choices = chunk.arrOrNull("choices")
        val choice = choices?.takeIf { it.size() > 0 }
            ?.get(0)?.objOrNull()
        val finish = choice?.strOrNull("finish_reason")
        val delta = choice?.objOrNull("delta")

        val content = delta?.strOrNull("content")
        val reasoning = delta?.strOrNull("reasoning_content") ?: delta?.strOrNull("reasoning")
        val fragments = delta?.arrOrNull("tool_calls")?.mapNotNull { element ->
            val item = element.objOrNull() ?: return@mapNotNull null
            val function = item.objOrNull("function")
            ToolCallFragment(
                index = item.intOrNull("index") ?: 0,
                id = item.strOrNull("id"),
                name = function?.strOrNull("name"),
                arguments = function?.strOrNull("arguments")
            )
        }.orEmpty()

        if (content == null && reasoning == null && fragments.isEmpty() && usage == null && finish == null) {
            return null
        }
        return OpenAiDelta(
            content = content,
            reasoning = reasoning,
            toolCalls = fragments,
            usage = usage,
            finishReason = finish
        )
    }
}

object AnthropicEventParser {
    fun parse(data: String): AnthropicEvent? {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        val event: JsonObject = parseJsonObjectOrNull(trimmed) ?: return null
        return when (event.strOrNull("type")) {
            "message_start" -> {
                val usage = event.objOrNull("message")?.objOrNull("usage")
                AnthropicEvent.MessageStart(usage?.longOrNull("input_tokens") ?: 0)
            }
            "content_block_start" -> {
                val block = event.objOrNull("content_block") ?: return null
                val type = block.strOrNull("type") ?: return null
                AnthropicEvent.BlockStart(
                    index = event.intOrNull("index") ?: 0,
                    type = type,
                    id = block.strOrNull("id"),
                    name = block.strOrNull("name"),
                    text = block.strOrNull("text")
                )
            }
            "content_block_delta" -> {
                val delta = event.objOrNull("delta") ?: return null
                AnthropicEvent.BlockDelta(
                    index = event.intOrNull("index") ?: 0,
                    type = delta.strOrNull("type") ?: return null,
                    text = delta.strOrNull("text"),
                    thinking = delta.strOrNull("thinking"),
                    partialJson = delta.strOrNull("partial_json")
                )
            }
            "message_delta" -> {
                val delta = event.objOrNull("delta")
                val usage = event.objOrNull("usage")
                AnthropicEvent.MessageDelta(
                    stopReason = delta?.strOrNull("stop_reason"),
                    outputTokens = usage?.longOrNull("output_tokens")
                )
            }
            "error" -> {
                val message = event.objOrNull("error")?.strOrNull("message")
                    ?: event.strOrNull("message")
                    ?: "服务商返回错误"
                AnthropicEvent.ErrorEvent(message)
            }
            else -> null
        }
    }
}
