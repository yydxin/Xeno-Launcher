/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ai

import androidx.annotation.Keep
import com.google.gson.JsonObject

/**
 * 服务商接口协议。两种主流格式，均使用流式 SSE。
 */
/**
 * 对话模式：
 * - CHAT：普通聊天，隐藏 web_search；
 * - WEB：联网搜索，鼓励先搜索并引用来源；
 * - AUTO：自动判断是否调用工具（默认）。
 */
enum class ChatMode { CHAT, WEB, AUTO }

enum class AiApiFormat {
    /** OpenAI 兼容：/chat/completions、tools/tool_calls */
    OPENAI,
    /** Anthropic：/messages、tools + tool_use/tool_result 内容块 */
    ANTHROPIC
}

/**
 * 一个 AI 服务商配置。API Key 不在这里，单独加密存储（见 [AiSettingsStore]）。
 */
@Keep
data class AiProviderConfig(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val format: AiApiFormat = AiApiFormat.OPENAI,
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val supportsTools: Boolean = true
) {
    /** 可用模型列表（至少包含默认模型） */
    fun usableModels(): List<String> {
        val all = LinkedHashSet<String>()
        models.filter { it.isNotBlank() }.forEach { all += it }
        defaultModel.takeIf { it.isNotBlank() }?.let { all += it }
        return all.toList()
    }

    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')
}

/** 模型函数调用 */
@Keep
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * 单条消息（本地统一表示）。
 * role: system / user / assistant / tool
 */
@Keep
data class ChatMessage(
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** 工具卡片摘要，便于重启后还原聊天记录 */
    val toolSummary: String = "",
    val toolOk: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/** 给模型看的工具声明 */
@Keep
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Keep
data class TokenUsage(val prompt: Long = 0, val completion: Long = 0) {
    val total: Long get() = prompt + completion
}

/** 一轮模型输出 */
data class AssistantTurn(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val usage: TokenUsage,
    val finishReason: String = ""
)

/** AI 调用异常：界面和服务层据此给出可读提示 */
class AiException(
    message: String,
    cause: Throwable? = null,
    val kind: Kind = Kind.OTHER
) : Exception(message, cause) {
    enum class Kind {
        AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_REQUEST, MODEL_NOT_FOUND, CANCELLED, OTHER
    }
}

/** 会话（持久化用） */
@Keep
data class ChatSession(
    val id: String,
    var title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var providerId: String = "",
    var providerName: String = "",
    var model: String = "",
    var promptTokens: Long = 0,
    var completionTokens: Long = 0,
    var messages: MutableList<ChatMessage> = mutableListOf()
) {
    fun preview(): String = messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
        ?.content?.replace('\n', ' ')?.take(48).orEmpty()
}
