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

import com.movtery.zalithlauncher.path.PathManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 工具层硬性安全：
 * - 所有路径先规范化（canonical），必须落在启动器管理目录内；
 * - 存档/用户数据目录直接拒绝写，不依赖提示词约束。
 */
object LauncherPathGuard {

    /** 任何层级下都不允许写的目录名 */
    val PROTECTED_DIRS = setOf(
        "saves", "screenshots", "logs", "crash-reports",
        "world", "world_nether", "world_the_end", "playerdata"
    )

    /** 任何层级下都不允许写的文件名 */
    val PROTECTED_FILES = setOf("options.txt", "servers.dat")

    /**
     * 判断相对路径是否受保护。
     * 兼容 `/` 与 `\`、前后斜杠、以及 backups 里的相对路径。
     */
    fun isProtectedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim().trimStart('/')
        if (normalized.isEmpty()) return true
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        val fileName = parts.last().lowercase()
        if (fileName in PROTECTED_FILES) return true
        // 路径中任意一段是受保护目录，都拒绝
        val dirParts = parts.dropLast(1).map { it.lowercase() }
        return dirParts.any { it in PROTECTED_DIRS }
    }

    /**
     * 把用户/模型提供的相对路径解析到 [base] 下。
     * 绝对路径、`..` 穿越、空路径一律返回 null。
     */
    fun resolveInside(base: File, relative: String): File? {
        val raw = relative.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("/") || raw.startsWith("\\")) return null
        if (raw.contains("..")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(raw)) return null
        val candidate = File(base, raw)
        val canonicalBase = runCatching { base.canonicalFile }.getOrElse { return null }
        val canonicalTarget = runCatching { candidate.canonicalFile }.getOrElse { return null }
        val basePath = canonicalBase.path + File.separator
        if (canonicalTarget.path != canonicalBase.path && !canonicalTarget.path.startsWith(basePath)) {
            return null
        }
        if (isProtectedPath(canonicalTarget.relativeTo(canonicalBase).path)) return null
        return canonicalTarget
    }

    /** 是否在启动器管理的目录内（游戏目录 / 应用私有目录 / 外部私有目录） */
    fun isManaged(file: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val roots = listOfNotNull(
            runCatching { PathManager.DIR_GAME }.getOrNull(),
            runCatching { PathManager.DIR_FILES_PRIVATE }.getOrNull(),
            runCatching { PathManager.DIR_FILES_EXTERNAL }.getOrNull()
        )
        return roots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            target.path == canonicalRoot.path ||
                target.path.startsWith(canonicalRoot.path + File.separator)
        }
    }

    /** 删除/覆盖前的统一检查 */
    fun canModify(file: File, instanceDir: File): Boolean {
        if (!isManaged(file)) return false
        val relative = runCatching { file.canonicalFile.relativeTo(instanceDir.canonicalFile).path }
            .getOrElse { return false }
        return !isProtectedPath(relative)
    }
}

/**
 * 工具审计日志：只记录工具名、参数（脱敏）、结果摘要，不写 API Key。
 */
object AiAuditLog {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val file: File by lazy {
        File(PathManager.DIR_FILES_PRIVATE, "ai-audit.log").apply {
            parentFile?.mkdirs()
        }
    }

    fun append(
        tool: String,
        risk: RiskLevel,
        args: String,
        decision: String,
        result: String,
        ok: Boolean
    ) {
        runCatching {
            val line = buildString {
                append(LocalDateTime.now().format(formatter))
                append(" | ")
                append(tool)
                append(" | ")
                append(risk.name)
                append(" | ")
                append(decision)
                append(" | ")
                append(if (ok) "OK" else "FAIL")
                append(" | args=")
                append(redact(args).take(600))
                append(" | result=")
                append(result.replace('\n', ' ').take(300))
                append('\n')
            }
            file.appendText(line)
        }
    }

    private fun redact(text: String): String {
        var out = text
        listOf("apiKey", "api_key", "key", "token", "secret", "password", "authorization")
            .forEach { keyword ->
                out = Regex("(\"$keyword\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE)
                    .replace(out, "$1***$2")
            }
        return out
    }
}

/**
 * 工具运行时的进度通道。翻译等长任务通过它向界面汇报，不依赖工具返回值。
 */
object ToolRuntime {
    private val _progress = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 32)
    val progress: kotlinx.coroutines.flow.SharedFlow<String> = _progress

    fun emit(message: String) {
        _progress.tryEmit(message)
    }
}
