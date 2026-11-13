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
import com.movtery.zalithlauncher.ai.ToolSpec

/**
 * 工具风险等级。
 * - READ_ONLY：只读，自动执行；
 * - LOW_RISK_WRITE：只写启动器管理目录下的新文件（临时目录/资源包/报告），路径校验后执行；
 * - HIGH_RISK_WRITE：可能覆盖/删除/安装/启动，必须由用户确认具体影响。
 */
enum class RiskLevel { READ_ONLY, LOW_RISK_WRITE, HIGH_RISK_WRITE }

data class ToolResult(
    val ok: Boolean,
    val summary: String,
    /** 回传给模型的文本（建议 JSON），执行器会限长 */
    val content: String
)

/**
 * 所有 AI 工具统一接口。名称固定，方便人工调试和日志检索。
 */
interface AgentTool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val riskLevel: RiskLevel

    suspend fun execute(args: JsonObject): ToolResult

    /** 高危操作确认时展示的具体影响（默认用描述） */
    fun confirmText(args: JsonObject): String = description

    /** 高危操作会影响到的文件路径（用于确认弹窗展示） */
    fun affectedPaths(args: JsonObject): List<String> = emptyList()
}

/**
 * 给模型用的工具声明。工具名、描述、JSON Schema 一一对应。
 */
fun AgentTool.toSpec(): ToolSpec = ToolSpec(
    name = name,
    description = description,
    parameters = parametersSchema
)
