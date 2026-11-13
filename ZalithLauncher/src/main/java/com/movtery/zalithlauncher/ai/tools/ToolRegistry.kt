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

import com.movtery.zalithlauncher.ai.ToolSpec

/**
 * 工具注册表。所有工具在启动时注册；禁用的工具不会出现在模型声明里。
 */
class ToolRegistry {
    private val tools = linkedMapOf<String, AgentTool>()

    /** 用户在设置里关闭的工具（仅影响本次运行，不持久化） */
    val disabled: MutableSet<String> = linkedSetOf()

    fun register(tool: AgentTool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    fun registerAll(list: Iterable<AgentTool>): ToolRegistry {
        list.forEach { register(it) }
        return this
    }

    fun find(name: String): AgentTool? = tools[name]

    fun names(): List<String> = tools.keys.toList()

    fun specs(): List<ToolSpec> = tools.values
        .filterNot { it.name in disabled }
        .map { it.toSpec() }

    fun all(): List<AgentTool> = tools.values.toList()

    /** 判断工具是否启用（关闭后的工具即使被模型调用也不执行） */
    fun isEnabled(name: String): Boolean = tools.containsKey(name) && name !in disabled
}
