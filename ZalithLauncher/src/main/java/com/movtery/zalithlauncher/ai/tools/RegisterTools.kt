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

import android.content.Context

/**
 * 构建启动器 AI 工具注册表。
 *
 * 只在这里做装配：工具描述、JSON Schema、风险等级都在各自文件里定义。
 * 所有工具共用同一份注册表；用户禁用的工具不会出现在模型声明里。
 */
fun buildLauncherTools(context: Context): ToolRegistry {
    val appContext = context.applicationContext
    val tools: List<AgentTool> = listOf(
        // 只读：实例/日志/模组/资源
        GetInstancesTool,
        GetSelectedInstanceTool(appContext),
        ReadLatestLogTool,
        ReadCrashReportTool,
        ListModsTool,
        ListShadersTool,
        ListResourcePacksTool,

        // 只读：版本清单、加载器版本、平台搜索、整合包版本、更新检查、联网搜索
        GetMinecraftVersionsTool,
        GetLoaderVersionsTool,
        SearchModsTool,
        SearchModpacksTool,
        GetModpackVersionsTool,
        CheckModpackUpdatesTool,
        CheckLoaderUpdatesTool,
        WebSearchTool,

        // 写：诊断报告、临时下载、整合包计划
        SaveDiagnosticReportTool,
        DownloadToTempTool,
        PlanModpackUpdateTool,

        // 预检 + 高风险安装
        PlanModInstallTool,
        InstallModTool,

        // 高风险：创建实例/安装整合包/整合包更新/加载器/实例配置/启动
        CreateInstanceTool(appContext),
        InstallModpackTool(appContext),
        UpdateModpackTool,
        UpdateLoaderTool(appContext),
        SetInstanceConfigTool(appContext),
        LaunchGameTool(appContext),

        // 模组批量启停（可逆重命名）与界面跳转
        SetModsEnabledTool,
        OpenScreenTool,

        // 汉化资源包（低风险写入）
        TranslateModsTool
    )
    return ToolRegistry().registerAll(tools)
}
