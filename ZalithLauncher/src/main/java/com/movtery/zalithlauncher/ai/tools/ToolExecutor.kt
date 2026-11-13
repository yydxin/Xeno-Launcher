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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.ai.ToolCall

/** 高危操作确认请求：界面必须展示具体将做什么、影响哪些文件 */
data class ToolConfirmRequest(
    val toolName: String,
    val title: String,
    val details: String,
    val affectedPaths: List<String>,
    val risk: RiskLevel
)

/**
 * 工具执行器：校验参数 → 检查工具存在 → 高危确认 → 执行 → 限长 → 审计。
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val confirm: suspend (ToolConfirmRequest) -> Boolean
) {
    suspend fun execute(call: ToolCall): ToolResult {
        val tool = registry.find(call.name)
            ?: return fail("未知工具：${call.name}")
        if (!registry.isEnabled(call.name)) {
            return fail("工具已被用户关闭：${call.name}")
        }

        val args = runCatching {
            JsonParser.parseString(call.arguments.ifBlank { "{}" }).asJsonObject
        }.getOrElse {
            return fail("工具参数不是合法 JSON：${call.name}")
        }

        validate(tool, args)?.let { problem ->
            return fail(problem)
        }

        if (tool.riskLevel == RiskLevel.HIGH_RISK_WRITE) {
            val approved = runCatching {
                confirm(
                    ToolConfirmRequest(
                        toolName = tool.name,
                        title = "AI 想要执行：${tool.name}",
                        details = tool.confirmText(args),
                        affectedPaths = tool.affectedPaths(args),
                        risk = tool.riskLevel
                    )
                )
            }.getOrDefault(false)
            if (!approved) {
                AiAuditLog.append(tool.name, tool.riskLevel, args.toString(), "denied", "用户拒绝", false)
                return ToolResult(false, "用户拒绝了这次操作", """{"ok":false,"cancelled":true}""")
            }
            AiAuditLog.append(tool.name, tool.riskLevel, args.toString(), "approved", "", true)
        }

        val result = runCatching { tool.execute(args) }
            .getOrElse { throwable ->
                ToolResult(false, "执行失败：${throwable.message ?: throwable::class.java.simpleName}", errorJson(throwable.message))
            }
        val limited = truncate(result)
        AiAuditLog.append(tool.name, tool.riskLevel, args.toString(), "executed", limited.summary, limited.ok)
        return limited
    }

    private fun validate(tool: AgentTool, args: JsonObject): String? {
        val schema = tool.parametersSchema
        val required = schema.getAsJsonArray("required") ?: return null
        val properties = schema.getAsJsonObject("properties")
        required.forEach { element ->
            val key = runCatching { element.asString }.getOrNull() ?: return@forEach
            val value = args.get(key)
            if (value == null || value.isJsonNull) return "缺少必填参数：$key"
            val expectedType = properties?.getAsJsonObject(key)?.get("type")?.asString
            when (expectedType) {
                "string" -> if (!value.isJsonPrimitive) return "参数 $key 类型应为字符串"
                "integer", "number" -> if (!value.isJsonPrimitive || value.asJsonPrimitive.isString) {
                    return "参数 $key 类型应为数字"
                }
                "boolean" -> if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
                    return "参数 $key 类型应为布尔值"
                }
            }
        }
        return null
    }

    private fun truncate(result: ToolResult): ToolResult {
        if (result.content.length <= MAX_RESULT_CHARS) return result
        val cut = result.content.take(MAX_RESULT_CHARS)
        return result.copy(
            content = cut + "\n\n[结果过长，已截断；原始长度 ${result.content.length} 字符]"
        )
    }

    private fun fail(message: String) = ToolResult(false, message, errorJson(message))

    private fun errorJson(message: String?): String {
        val text = (message ?: "unknown error").replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"ok":false,"error":"$text"}"""
    }

    companion object {
        private const val MAX_RESULT_CHARS = 8000
    }
}
