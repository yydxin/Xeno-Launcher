/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.agent

import com.movtery.zalithlauncher.ai.AiClient
import com.movtery.zalithlauncher.ai.ChatMode
import com.movtery.zalithlauncher.ai.ChatMessage
import com.movtery.zalithlauncher.ai.TokenUsage
import com.movtery.zalithlauncher.ai.prompt.AgentSystemPrompt
import com.movtery.zalithlauncher.ai.tools.RiskLevel
import com.movtery.zalithlauncher.ai.tools.ToolExecutor
import com.movtery.zalithlauncher.ai.tools.ToolRegistry
import kotlinx.coroutines.CancellationException

/** Agent 事件：界面只消费这些事件，不直接操作网络 */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class RoundStarted(val round: Int) : AgentEvent
    data class ToolStarted(val name: String, val arguments: String, val risk: RiskLevel) : AgentEvent
    data class ToolFinished(val name: String, val ok: Boolean, val summary: String) : AgentEvent
    data class Usage(val usage: TokenUsage) : AgentEvent
    data class Completed(val answer: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Stopped : AgentEvent
}

/**
 * Agent 循环：模型 → tool_call → 执行 → 回传 → 继续，直到模型给出最终回答。
 * 最大连续工具调用轮数由 [maxRounds] 控制，防止死循环。
 */
class AiAgent(
    private val client: AiClient,
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val maxRounds: Int = 12,
    private val mode: ChatMode = ChatMode.AUTO,
    private val allowedTools: Set<String>? = null
) {
    /**
     * @param history 不含 system 的历史消息
     * @return 本次新增的消息（assistant / tool），由调用方持久化
     */
    suspend fun run(
        history: List<ChatMessage>,
        onEvent: (AgentEvent) -> Unit
    ): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(history.size + 8)
        messages += ChatMessage(
            role = "system",
            content = AgentSystemPrompt.build(
                registry.names().filter { registry.isEnabled(it) && (allowedTools == null || it in allowedTools) },
                mode
            )
        )
        messages += history.filterNot { it.role == "system" }
        val produced = mutableListOf<ChatMessage>()

        try {
            repeat(maxRounds) { index ->
                onEvent(AgentEvent.RoundStarted(index + 1))
                val turn = client.streamChat(
                    messages = messages,
                    tools = registry.specs().filter { allowedTools == null || it.name in allowedTools },
                    onDelta = { onEvent(AgentEvent.TextDelta(it)) },
                    onReasoning = { onEvent(AgentEvent.ReasoningDelta(it)) }
                )
                val assistant = ChatMessage(
                    role = "assistant",
                    content = turn.content,
                    reasoning = turn.reasoning,
                    toolCalls = turn.toolCalls
                )
                messages += assistant
                produced += assistant
                if (turn.usage.total > 0) onEvent(AgentEvent.Usage(turn.usage))

                if (turn.toolCalls.isEmpty()) {
                    onEvent(AgentEvent.Completed(turn.content))
                    return produced
                }

                turn.toolCalls.forEach { call ->
                    val tool = registry.find(call.name)
                    onEvent(AgentEvent.ToolStarted(call.name, call.arguments, tool?.riskLevel ?: RiskLevel.READ_ONLY))
                    val result = if (allowedTools != null && call.name !in allowedTools) {
                        com.movtery.zalithlauncher.ai.tools.ToolResult(
                            ok = false,
                            summary = "当前模式未开放工具：${call.name}",
                            content = """{"ok":false,"error":"tool not allowed in current mode"}"""
                        )
                    } else {
                        executor.execute(call)
                    }
                    onEvent(AgentEvent.ToolFinished(call.name, result.ok, result.summary))
                    val toolMessage = ChatMessage(
                        role = "tool",
                        content = result.content,
                        toolCallId = call.id,
                        toolName = call.name,
                        toolSummary = result.summary,
                        toolOk = result.ok
                    )
                    messages += toolMessage
                    produced += toolMessage
                }
            }

            val message = "工具调用轮次超过上限（$maxRounds 轮），已停止。请缩小任务范围后重试。"
            onEvent(AgentEvent.Failed(message))
            return produced
        } catch (e: CancellationException) {
            onEvent(AgentEvent.Stopped)
            throw e
        } catch (e: Throwable) {
            onEvent(AgentEvent.Failed(e.message ?: e::class.java.simpleName))
            return produced
        }
    }
}
