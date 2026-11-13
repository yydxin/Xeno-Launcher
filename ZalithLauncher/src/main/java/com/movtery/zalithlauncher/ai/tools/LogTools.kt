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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.ai.log.LaunchRecordStore
import com.movtery.zalithlauncher.ai.log.LogPreprocessor
import com.movtery.zalithlauncher.path.PathManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 读取最近一次游戏日志并做异常压缩 */
object ReadLatestLogTool : AgentTool {
    override val name: String = "read_latest_log"
    override val description: String = "读取指定实例（默认当前实例）最近一次游戏日志。优先使用启动记录中的日志路径，" +
            "其次使用实例的 latest_game.log，最后回退到 logs/latest.log。日志会保留异常首行、Caused by、" +
            "依赖缺失等关键行和末尾内容，并去除连续重复行，避免把整份日志塞给模型。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例"),
            "tail" to integerSchema(
                "额外保留的末尾行数，默认 200",
                minimum = 1,
                maximum = 2000,
                default = 200
            )
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val tail = args.optInt("tail") ?: 200
        if (tail !in 1..2000) {
            return failResult("tail 必须在 1..2000 之间")
        }

        val instanceName = version.getVersionName()
        val checked = mutableListOf<String>()
        var source = ""
        var recordExitCode: Int? = null
        var logFile: File? = null

        // 1) 启动记录里与本实例匹配的最近一条
        val record = LaunchRecordStore.recent(30)
            .firstOrNull { it.instanceName == instanceName && it.logPath.isNotBlank() }
        if (record != null) {
            val candidate = File(record.logPath)
            checked.add(record.logPath)
            if (candidate.isFile && runCatching { LauncherPathGuard.isManaged(candidate) }.getOrDefault(false)) {
                logFile = candidate
                source = "launch-record"
                recordExitCode = record.exitCode
            }
        }

        // 2) 实例的最近一次启动日志
        if (logFile == null) {
            val candidate = instanceLogPath(version)
            checked.add(candidate.absolutePath)
            if (candidate.isFile && runCatching { LauncherPathGuard.isManaged(candidate) }.getOrDefault(false)) {
                logFile = candidate
                source = "latest-log"
            }
        }

        // 3) 游戏自身写入的 logs/latest.log
        if (logFile == null) {
            val candidate = File(version.getGameDir(), "logs/latest.log")
            checked.add(candidate.absolutePath)
            if (candidate.isFile && runCatching { LauncherPathGuard.isManaged(candidate) }.getOrDefault(false)) {
                logFile = candidate
                source = "logs-latest"
            }
        }

        val file = logFile ?: return failResult(
            "没有找到实例「$instanceName」的最近日志。已检查：${checked.distinct().joinToString("；")}",
            JsonObject().apply {
                put("instance", instanceName)
                put("checkedPaths", jsonArray(checked.distinct()))
            }
        )

        val (rawText, fileTruncated) = readTextLimited(file, 1_500_000L)
        if (rawText.isBlank()) {
            return failResult(
                "日志文件为空：${file.absolutePath}",
                JsonObject().apply {
                    put("instance", instanceName)
                    put("logPath", file.absolutePath)
                    put("source", source)
                }
            )
        }
        val processed = LogPreprocessor.preprocess(rawText, tailLines = tail, maxChars = 5000)
        val problems = LogPreprocessor.collectProblems(rawText)

        val body = JsonObject().apply {
            put("instance", instanceName)
            put("logPath", file.absolutePath)
            put("source", source)
            put("logModified", runCatching { file.lastModified() }.getOrDefault(0L))
            put("logModifiedText", formatTime(runCatching { file.lastModified() }.getOrDefault(0L)))
            put("fileTruncated", fileTruncated)
            put("tail", tail)
            put("problemLines", jsonArray(problems.take(10).map { it.take(300) }))
            put("content", processed)
            if (recordExitCode != null) put("exitCode", recordExitCode)
        }
        return okResult("已读取实例「$instanceName」的日志（${file.name}，保留末尾 $tail 行）", body)
    }
}

/** 读取最新崩溃报告 */
object ReadCrashReportTool : AgentTool {
    override val name: String = "read_crash_report"
    override val description: String = "读取指定实例（默认当前实例）crash-reports 目录中最新的 .txt 崩溃报告，并提取异常首行、" +
            "Caused by、依赖缺失等关键行。没有崩溃报告时会明确说明，不会编造内容。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val dir = crashReportsDir(version)
        if (!dir.isDirectory) {
            return failResult(
                "实例「${version.getVersionName()}」没有 crash-reports 目录，因此没有崩溃报告。",
                JsonObject().apply {
                    put("instance", version.getVersionName())
                    put("crashReportsDir", dir.absolutePath)
                    put("found", false)
                }
            )
        }
        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { runCatching { it.lastModified() }.getOrDefault(0L) }
            .orEmpty()
        if (files.isEmpty()) {
            return failResult(
                "实例「${version.getVersionName()}」的 crash-reports 目录里没有 .txt 崩溃报告。",
                JsonObject().apply {
                    put("instance", version.getVersionName())
                    put("crashReportsDir", dir.absolutePath)
                    put("found", false)
                }
            )
        }

        val report = files.first()
        if (!runCatching { LauncherPathGuard.isManaged(report) }.getOrDefault(false)) {
            return failResult("崩溃报告不在启动器管理的目录内，已拒绝读取：${report.absolutePath}")
        }
        val (rawText, fileTruncated) = readTextLimited(report, 1_500_000L)
        if (rawText.isBlank()) {
            return failResult("崩溃报告为空：${report.absolutePath}")
        }
        val processed = LogPreprocessor.preprocess(rawText, tailLines = 300, maxChars = 5200)
        val problems = LogPreprocessor.collectProblems(rawText)
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("reportPath", report.absolutePath)
            put("reportModified", runCatching { report.lastModified() }.getOrDefault(0L))
            put("reportModifiedText", formatTime(runCatching { report.lastModified() }.getOrDefault(0L)))
            put("fileTruncated", fileTruncated)
            put("problemLines", jsonArray(problems.take(10).map { it.take(300) }))
            put("content", processed)
            put("otherReports", jsonArray(files.drop(1).take(10).map { it.name }))
        }
        return okResult("已读取实例「${version.getVersionName()}」的最新崩溃报告：${report.name}", body)
    }
}

/** 把用户/模型整理的诊断内容写入私有报告目录 */
object SaveDiagnosticReportTool : AgentTool {
    private const val MAX_CONTENT_CHARS = 200_000

    override val name: String = "save_diagnostic_report"
    override val description: String = "把一段诊断报告/排查结论保存到启动器私有目录 ai/reports/ 下，文件名由时间和实例名组成。" +
            "只写入私有目录，不会修改游戏实例、存档或配置。content 最长 200000 字符。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance", "content"),
        properties = mapOf(
            "instance" to stringSchema("实例名称，必须与 get_instances 返回的名称一致"),
            "content" to stringSchema("要保存的文本内容（诊断报告、日志摘要等）")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.LOW_RISK_WRITE

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val content = args.optText("content")?.takeIf { it.isNotBlank() }
            ?: return failResult("缺少或为空的参数：content")
        if (content.length > MAX_CONTENT_CHARS) {
            return failResult("content 过长（${content.length} 字符），上限为 $MAX_CONTENT_CHARS 字符。")
        }
        val version = resolveVersion(requested) ?: return notFoundResult(requested)

        val reportsDir = runCatching {
            File(PathManager.DIR_FILES_PRIVATE, "ai/reports").apply { mkdirs() }
        }.getOrNull() ?: return failResult("无法访问启动器私有目录，报告未保存。")

        val fileName = "${safeTimestampMillis()}-${safeFileSegment(version.getVersionName())}.txt"
        val target = LauncherPathGuard.resolveInside(reportsDir, fileName)
            ?: return failResult("报告文件名不安全，已拒绝写入：$fileName")
        if (!LauncherPathGuard.canModify(target, reportsDir)) {
            return failResult("报告路径未通过启动器路径校验，已拒绝写入：${target.absolutePath}")
        }
        val unique = if (target.exists()) uniqueFile(reportsDir, fileName) else target
        if (!LauncherPathGuard.canModify(unique, reportsDir)) {
            return failResult("报告路径未通过启动器路径校验，已拒绝写入：${unique.absolutePath}")
        }

        val result = runCatching { writeTextAtomic(unique, content) }
        result.exceptionOrNull()?.let { e ->
            return failResult("保存诊断报告失败：${e.message ?: e::class.java.simpleName}")
        }

        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("path", unique.absolutePath)
            put("size", runCatching { unique.length() }.getOrDefault(content.toByteArray().size.toLong()))
            put("chars", content.length)
        }
        return okResult("诊断报告已保存到 ${unique.absolutePath}", body)
    }
}

private fun writeTextAtomic(file: File, text: String) {
    val parent = file.parentFile ?: error("Missing parent directory")
    if (!parent.exists() && !parent.mkdirs()) error("Cannot create directory: ${parent.absolutePath}")
    val temp = File(parent, "${file.name}.tmp")
    temp.writeText(text)
    try {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        runCatching { if (temp.exists()) temp.delete() }
    }
}
