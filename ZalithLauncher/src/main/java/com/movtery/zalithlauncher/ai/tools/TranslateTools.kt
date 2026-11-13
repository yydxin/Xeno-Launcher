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
import com.movtery.zalithlauncher.ai.translate.ModTranslator
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import java.io.File

/**
 * 汉化单个实例的模组：读取 jar 内 en_us.json，调用已配置的 AI 翻译，生成资源包。
 * 只写 resourcepacks/Xeno汉化包；绝不修改原 jar。
 */
object TranslateModsTool : AgentTool {
    private const val UNSUPPORTED_WRITE_BACK =
        "当前版本不支持写回 jar，请使用生成资源包（resourcepacks/Xeno汉化包）。"

    override val name: String = "translate_mods"
    override val description: String = "扫描指定实例（默认当前实例）mods/*.jar，读取 assets/<modid>/lang/en_us.json，" +
            "调用当前 AI 服务商分批翻译，并生成 resourcepacks/Xeno汉化包。已有 zh_cn.json 的模组会跳过；" +
            "旧版 en_US.lang 不支持，会跳过；只写资源包，绝不修改原 jar。write_back=true 会明确返回不支持。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance"),
        properties = mapOf(
            "instance" to stringSchema("实例名称"),
            "write_back" to booleanSchema("是否写回 jar；当前版本不支持，传 true 只会返回明确错误", false)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.LOW_RISK_WRITE

    override fun confirmText(args: JsonObject): String = buildString {
        appendLine("将翻译实例「${args.optText("instance").orEmpty()}」中缺少中文的模组。")
        appendLine("只写入 resourcepacks/Xeno汉化包，不修改原 jar，也不会写入 saves/。")
        appendLine("已有的同名资源包会先备份到 <instance>/XingLan/ai-backup/<时间戳>/。")
    }

    override fun affectedPaths(args: JsonObject): List<String> {
        val version = resolveVersion(args.optText("instance")) ?: return emptyList()
        return listOf(
            File(
                VersionFolders.RESOURCE_PACK.getDir(version.getGameDir()),
                "Xeno汉化包"
            ).absolutePath
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        if (args.optBoolean("write_back") == true) {
            return failResult(UNSUPPORTED_WRITE_BACK)
        }
        val outcome = try {
            ModTranslator.translate(version, writeBack = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("翻译失败：${e.message ?: e::class.java.simpleName}")
        }
        val body = outcome.summary.toJson()
        return if (outcome.ok) {
            okResult(outcome.message, body)
        } else {
            failResult(outcome.message, body)
        }
    }
}
