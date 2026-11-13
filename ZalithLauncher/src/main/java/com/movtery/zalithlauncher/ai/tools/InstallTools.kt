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
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.engine.Fetcher
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.file.calculateFileSha1
import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.URI
import java.net.URLDecoder

/**
 * 只读预检：解析安装目标、依赖、文件名、URL、大小并缓存。
 * install_mod 必须先用它生成计划，确认弹窗才能列出具体影响。
 */
object PlanModInstallTool : AgentTool {
    override val name: String = "plan_mod_install"
    override val description: String = "只读预检：解析从 Modrinth/CurseForge 安装模组、光影或资源包所需的文件、" +
            "required 依赖（最多 3 层）、下载 URL 与大小，并缓存计划。install_mod 必须先用它生成计划；" +
            "本工具不下载、不写入任何文件。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance", "platform", "kind"),
        properties = mapOf(
            "instance" to stringSchema("目标实例名称"),
            "platform" to stringSchema("资源平台", listOf("modrinth", "curseforge")),
            "kind" to stringSchema("安装类型", listOf("mod", "shader", "resourcepack"), "mod"),
            "project_id" to stringSchema("平台项目 ID（与 query 二选一，优先使用）"),
            "query" to stringSchema("搜索关键词（没有 project_id 时使用）"),
            "version_id" to stringSchema("指定平台版本 ID；省略则选择与实例 MC 版本、加载器兼容的最新稳定版")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        return when (val outcome = buildInstallPlan(args)) {
            is PlanOutcome.Failure -> outcome.result
            is PlanOutcome.Success -> {
                val plan = outcome.plan
                val totalBytes = plan.files.sumOf { if (it.size > 0) it.size else 0L }
                val body = PlatformSupport.planToJson(plan, includeUrls = true).apply {
                    put("fileCount", plan.files.size)
                    put("downloadBytes", totalBytes)
                    put("downloadHuman", formatBytes(totalBytes))
                    put("requiresConfirm", true)
                }
                okResult(
                    "安装计划已生成：${plan.files.size} 个文件，约 ${formatBytes(totalBytes)}；" +
                            "请让用户确认后再调用 install_mod。",
                    body
                )
            }
        }
    }
}

/**
 * 安装模组/光影/资源包。必须在 plan_mod_install 之后调用。
 */
object InstallModTool : AgentTool {
    override val name: String = "install_mod"
    override val description: String = "把 Modrinth/CurseForge 项目安装到实例的 mods、shaderpacks 或 resourcepacks。" +
            "必须先调用 plan_mod_install 生成计划，本工具只会执行已缓存计划中的文件；会校验 SHA-1、" +
            "失败重试 3 次，同名文件先备份到 <instance>/XingLan/ai-backup/<时间戳>/。" +
            "禁止写入 saves/。"
    override val parametersSchema: JsonObject = PlanModInstallTool.parametersSchema
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String {
        val plan = cachedPlan(args)
            ?: return "未生成安装计划：请先调用 plan_mod_install 解析将下载的文件、依赖、大小与 URL。\n" +
                    "出于安全考虑，未预览的安装不会执行。"
        return PlatformSupport.planToConfirmText(plan)
    }

    override fun affectedPaths(args: JsonObject): List<String> {
        val plan = cachedPlan(args) ?: return emptyList()
        return PlatformSupport.planAffectedPaths(plan)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val cacheKey = cacheKey(args)
        val plan = InstallPlanCache.get(cacheKey)
            ?: return failResult(
                "没有找到有效的安装计划。请先调用 plan_mod_install 生成计划，确认后 10 分钟内再执行安装。"
            )
        if (plan.instanceName != version.getVersionName()) {
            return failResult("安装计划与当前实例不匹配，已中止。请重新生成计划。")
        }
        for (file in plan.files) {
            if (!isSafeHttpUrl(file.url)) {
                return failResult("计划中的下载链接不合法，已中止：${file.fileName}")
            }
            if (!LauncherPathGuard.canModify(file.target, version.getGameDir())) {
                return failResult("目标文件未通过启动器路径校验，已中止：${file.target.absolutePath}")
            }
        }

        val backupRoot = File(
            version.getVersionPath(),
            "XingLan/ai-backup/${safeTimestampMillis()}"
        )
        if (!LauncherPathGuard.canModify(backupRoot, version.getVersionPath())) {
            return failResult("备份目录未通过启动器路径校验，已中止：${backupRoot.absolutePath}")
        }

        val installed = mutableListOf<JsonObject>()
        val skipped = mutableListOf<JsonObject>()
        val backedUp = mutableListOf<String>()
        var downloadedBytes = 0L

        for (file in plan.files) {
            val expected = file.sha1?.takeIf { it.isNotBlank() }
            if (file.target.isFile && expected != null) {
                val existingSha1 = runCatching { calculateFileSha1(file.target) }.getOrNull()
                if (existingSha1 != null && existingSha1.equals(expected, ignoreCase = true)) {
                    skipped.add(file.toJson("哈希相同，已存在"))
                    continue
                }
            }

            if (file.target.exists()) {
                val relative = runCatching {
                    file.target.relativeTo(version.getGameDir()).invariantSeparatorsPath
                }.getOrNull() ?: file.target.name
                val backup = File(backupRoot, relative)
                if (!LauncherPathGuard.canModify(backup, backupRoot)) {
                    return failResult("备份路径未通过启动器路径校验，已中止：${backup.absolutePath}")
                }
                val backupResult = runCatching {
                    backup.parentFile?.mkdirs()
                    file.target.copyTo(backup, overwrite = true)
                }
                backupResult.exceptionOrNull()?.let { e ->
                    return failResult(
                        "备份同名文件失败（${file.fileName}）：${e.message ?: e::class.java.simpleName}。" +
                                "为避免覆盖，安装已中止。"
                    )
                }
                backedUp.add(backup.absolutePath)
            }

            ToolRuntime.emit("正在下载 ${file.fileName}（${if (file.size > 0) formatBytes(file.size) else "大小未知"}）")
            var received = 0L
            var lastReport = 0L
            try {
                Fetcher.downloadFile(
                    urls = listOf(file.url),
                    targetFile = file.target,
                    sha1 = expected,
                    retry = 3
                ) { delta ->
                    received += delta
                    if (received - lastReport >= 2 * 1024 * 1024) {
                        lastReport = received
                        ToolRuntime.emit("正在下载 ${file.fileName}：${formatBytes(received)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return failResult(
                    "下载失败（${file.fileName}）：${e.message ?: e::class.java.simpleName}。" +
                            if (backedUp.isEmpty()) "尚未覆盖任何文件。"
                            else "已备份原文件到 ${backupRoot.absolutePath}。",
                    JsonObject().apply {
                        put("instance", version.getVersionName())
                        put("failedFile", file.fileName)
                        put("url", file.url)
                        put("backupDir", backupRoot.absolutePath)
                        put("backedUp", jsonArray(backedUp))
                        add("installed", JsonArray().apply { installed.forEach { add(it) } })
                    }
                )
            }

            downloadedBytes += runCatching { file.target.length() }.getOrDefault(0L)
            val actualSha1 = runCatching { calculateFileSha1(file.target) }.getOrNull()
            if (expected != null && !expected.equals(actualSha1, ignoreCase = true)) {
                return failResult(
                    "文件哈希校验失败（${file.fileName}）：期望 $expected，实际 ${actualSha1 ?: "无法计算"}。" +
                            "原文件已备份到 ${backupRoot.absolutePath}。"
                )
            }
            if (file.size >= 0 && file.target.length() != file.size) {
                ToolRuntime.emit(
                    "提示：${file.fileName} 下载后大小 ${formatBytes(file.target.length())} 与平台标注 " +
                            "${formatBytes(file.size)} 不一致，已按实际文件保留。"
                )
            }
            installed.add(file.toJson("已安装"))
        }

        InstallPlanCache.invalidate(cacheKey)
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("kind", plan.kind)
            put("platform", plan.platform.name.lowercase())
            put("installedCount", installed.size)
            put("skippedCount", skipped.size)
            put("downloadedBytes", downloadedBytes)
            put("downloadedHuman", formatBytes(downloadedBytes))
            put("backupDir", if (backedUp.isEmpty()) "" else backupRoot.absolutePath)
            add("installed", JsonArray().apply { installed.forEach { add(it) } })
            add("skipped", JsonArray().apply { skipped.forEach { add(it) } })
            add("backedUp", jsonArray(backedUp))
        }
        return okResult(
            "安装完成：写入 ${installed.size} 个文件，跳过 ${skipped.size} 个" +
                    if (backedUp.isEmpty()) "，无需备份。" else "，备份在 ${backupRoot.absolutePath}。",
            body
        )
    }

    private fun cachedPlan(args: JsonObject): InstallPlan? =
        InstallPlanCache.get(cacheKey(args))

    private fun cacheKey(args: JsonObject): String = InstallPlanCache.key(
        instance = args.optText("instance").orEmpty(),
        platform = args.optText("platform").orEmpty(),
        kind = args.optText("kind") ?: "mod",
        projectId = args.optText("project_id"),
        query = args.optText("query"),
        versionId = args.optText("version_id")
    )

    private fun PlannedFile.toJson(message: String): JsonObject = JsonObject().apply {
        put("fileName", fileName)
        put("targetPath", target.absolutePath)
        put("size", runCatching { target.length() }.getOrDefault(0L))
        put("sha1", sha1.orEmpty())
        put("dependency", dependency)
        put("message", message)
    }
}

/** 把任意 http/https 链接下载到缓存临时目录，不写入实例 */
object DownloadToTempTool : AgentTool {
    override val name: String = "download_to_temp"
    override val description: String = "把单个 http/https 链接下载到缓存目录 cache/ai_downloads/，可选 SHA-1 校验。" +
            "只用于临时获取文件，不会写入实例、mods 或存档；file_name 只能是纯文件名。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("url"),
        properties = mapOf(
            "url" to stringSchema("http 或 https 下载链接"),
            "file_name" to stringSchema("保存文件名（可选，只能是纯文件名，不能包含路径）"),
            "sha1" to stringSchema("可选的 SHA-1 校验值（40 位十六进制）")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.LOW_RISK_WRITE

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args.optText("url") ?: return failResult("缺少必填参数：url")
        if (!isSafeHttpUrl(url)) {
            return failResult("只允许 http/https 链接，且必须是完整 URL：$url")
        }
        val expectedSha1 = args.optText("sha1")?.lowercase()?.takeIf { it.isNotBlank() }
        if (expectedSha1 != null && !Regex("""[0-9a-f]{40}""").matches(expectedSha1)) {
            return failResult("sha1 必须是 40 位十六进制字符串。")
        }

        val requestedName = args.optText("file_name")
        val derivedName = requestedName ?: runCatching {
            val path = URI(url).path.orEmpty()
            URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        }.getOrNull()
        val safeName = simpleFileName(derivedName.orEmpty())
            ?: return failResult(
                "file_name 不合法。只允许纯文件名，不能包含 /、\\ 或 ..；未提供时也无法从链接推断文件名。"
            )

        val dir = runCatching {
            File(PathManager.DIR_CACHE, "ai_downloads").apply { mkdirs() }
        }.getOrNull() ?: return failResult("无法访问缓存目录，下载未开始。")
        val target = LauncherPathGuard.resolveInside(dir, safeName)
            ?: return failResult("文件名未通过启动器路径校验，已拒绝：$safeName")
        val unique = if (target.exists()) uniqueFile(dir, safeName) else target

        var received = 0L
        var lastReport = 0L
        try {
            Fetcher.downloadFile(
                urls = listOf(url),
                targetFile = unique,
                sha1 = expectedSha1,
                retry = 3
            ) { delta ->
                received += delta
                if (received - lastReport >= 2 * 1024 * 1024) {
                    lastReport = received
                    ToolRuntime.emit("正在下载 ${unique.name}：${formatBytes(received)}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("下载失败：${e.message ?: e::class.java.simpleName}")
        }

        val actualSha1 = runCatching { calculateFileSha1(unique) }.getOrNull()
        val matched = expectedSha1?.let { it.equals(actualSha1, ignoreCase = true) }
        val body = JsonObject().apply {
            put("path", unique.absolutePath)
            put("fileName", unique.name)
            put("size", runCatching { unique.length() }.getOrDefault(0L))
            put("sizeHuman", formatBytes(runCatching { unique.length() }.getOrDefault(0L)))
            put("sha1", actualSha1.orEmpty())
            put("sha1Expected", expectedSha1.orEmpty())
            put("sha1Matched", matched)
        }
        return if (matched == false) {
            failResult("SHA-1 校验失败：期望 $expectedSha1，实际 ${actualSha1 ?: "无法计算"}", body)
        } else {
            okResult("已下载到 ${unique.absolutePath}（${formatBytes(unique.length())}）", body)
        }
    }
}

private sealed interface PlanOutcome {
    data class Success(val plan: InstallPlan, val cacheKey: String) : PlanOutcome
    data class Failure(val result: ToolResult) : PlanOutcome
}

private suspend fun buildInstallPlan(args: JsonObject): PlanOutcome {
    val requested = args.optText("instance")
        ?: return PlanOutcome.Failure(failResult("缺少必填参数：instance"))
    val version = resolveVersion(requested)
        ?: return PlanOutcome.Failure(notFoundResult(requested))
    val platformText = args.optText("platform")
        ?: return PlanOutcome.Failure(failResult("缺少必填参数：platform"))
    val platform = PlatformSupport.platformOf(platformText)
        ?: return PlanOutcome.Failure(failResult("platform 只能是 modrinth 或 curseforge。"))
    val kind = args.optText("kind")?.lowercase() ?: "mod"
    if (PlatformSupport.classesOf(kind) == null || kind !in setOf("mod", "shader", "resourcepack")) {
        return PlanOutcome.Failure(failResult("kind 只能是 mod、shader 或 resourcepack。"))
    }
    val projectId = args.optText("project_id")
    val query = args.optText("query")
    if (projectId.isNullOrBlank() && query.isNullOrBlank()) {
        return PlanOutcome.Failure(failResult("project_id 和 query 至少提供一个。"))
    }
    val versionId = args.optText("version_id")
    val minecraftVersion = version.getVersionInfo()?.minecraftVersion.orEmpty()
    if (minecraftVersion.isBlank()) {
        return PlanOutcome.Failure(failResult("无法确定实例「${version.getVersionName()}」的 Minecraft 版本，无法选择兼容文件。"))
    }
    val loaderName = version.getVersionInfo()?.primaryLoader?.loader?.displayName

    val project = try {
        PlatformSupport.resolveProject(platform, projectId, query, kind)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return PlanOutcome.Failure(failResult("解析项目失败：${e.message ?: e::class.java.simpleName}"))
    }

    val rootVersion = try {
        PlatformSupport.findCompatibleVersion(
            platform = project.platform,
            projectId = project.id,
            kind = kind,
            minecraftVersion = minecraftVersion,
            loaderName = loaderName,
            versionId = versionId
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return PlanOutcome.Failure(failResult("查询项目版本失败：${e.message ?: e::class.java.simpleName}"))
    } ?: return PlanOutcome.Failure(
        failResult(
            "没有找到与 Minecraft $minecraftVersion" +
                    (loaderName?.let { " + $it" } ?: "") +
                    " 兼容的 ${project.title} 版本。"
        )
    )

    val dependencies: List<com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion>
    val dependencyWarnings: List<String>
    try {
        val resolved = PlatformSupport.resolveRequiredDependencies(
            root = rootVersion,
            platform = project.platform,
            kind = kind,
            minecraftVersion = minecraftVersion,
            loaderName = loaderName
        )
        dependencies = resolved.first
        dependencyWarnings = resolved.second
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return PlanOutcome.Failure(failResult("解析 required 依赖失败：${e.message ?: e::class.java.simpleName}"))
    }

    val plan = try {
        PlatformSupport.buildPlan(
            version = version,
            kind = kind,
            platform = project.platform,
            projectId = project.id,
            query = query,
            versionId = versionId,
            rootProject = project,
            rootVersion = rootVersion,
            dependencyVersions = dependencies,
            warnings = dependencyWarnings
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return PlanOutcome.Failure(failResult("生成安装计划失败：${e.message ?: e::class.java.simpleName}"))
    }

    val cacheKey = InstallPlanCache.key(
        instance = version.getVersionName(),
        platform = platform.name,
        kind = kind,
        projectId = projectId,
        query = query,
        versionId = versionId
    )
    InstallPlanCache.put(cacheKey, plan)
    return PlanOutcome.Success(plan, cacheKey)
}
