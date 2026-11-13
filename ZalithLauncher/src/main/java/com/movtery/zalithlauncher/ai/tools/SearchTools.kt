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
import com.movtery.zalithlauncher.ai.search.WebSearchClient
import com.movtery.zalithlauncher.ai.search.WebSearchException
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.download.modpack.update.ModpackDiff
import com.movtery.zalithlauncher.game.download.modpack.update.ModpackUpdateService
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import kotlinx.coroutines.CancellationException

/** 搜索 Modrinth / CurseForge 资源 */
object SearchModsTool : AgentTool {
    override val name: String = "search_mods"
    override val description: String = "搜索 Modrinth 和/或 CurseForge 的模组、整合包、光影或资源包。" +
            "返回统一结果：title、slug、id、platform、downloads、description、versions。" +
            "平台数据来自现有搜索实现与镜像配置；不会伪造结果。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("query"),
        properties = mapOf(
            "query" to stringSchema("搜索关键词"),
            "platform" to stringSchema("平台；all 表示同时搜索两个平台", listOf("all", "modrinth", "curseforge"), "all"),
            "kind" to stringSchema("资源类型", listOf("mod", "modpack", "shader", "resourcepack"), "mod"),
            "mc_version" to stringSchema("可选的 Minecraft 版本过滤，如 1.20.1"),
            "loader" to stringSchema("可选的加载器过滤，如 fabric、forge、neoforge、quilt"),
            "limit" to integerSchema("返回结果数量上限", minimum = 1, maximum = 20, default = 8)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optText("query") ?: return failResult("缺少必填参数：query")
        val platformText = args.optText("platform")?.lowercase() ?: "all"
        val kind = args.optText("kind")?.lowercase() ?: "mod"
        if (PlatformSupport.classesOf(kind) == null) {
            return failResult("kind 只能是 mod、modpack、shader 或 resourcepack。")
        }
        val limit = args.optInt("limit") ?: 8
        if (limit !in 1..20) return failResult("limit 必须在 1..20 之间。")
        val mcVersion = args.optText("mc_version")
        val loader = args.optText("loader")

        val platforms = when (platformText) {
            "modrinth" -> listOf(Platform.MODRINTH)
            "curseforge" -> listOf(Platform.CURSEFORGE)
            "all" -> listOf(Platform.MODRINTH, Platform.CURSEFORGE)
            else -> return failResult("platform 只能是 all、modrinth 或 curseforge。")
        }

        val items = mutableListOf<SearchItem>()
        val warnings = mutableListOf<String>()
        for (platform in platforms) {
            try {
                val perPlatformLimit = if (platforms.size > 1) PlatformSupport.sourceLimit(limit) else limit
                items += PlatformSupport.search(
                    platform = platform,
                    query = query,
                    kind = kind,
                    minecraftVersion = mcVersion,
                    loader = loader,
                    limit = perPlatformLimit
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                warnings += "${platform.displayName} 搜索失败：${e.message ?: e::class.java.simpleName}"
            }
        }

        if (items.isEmpty() && warnings.size == platforms.size) {
            return failResult("搜索失败：${warnings.joinToString("；")}")
        }
        val limited = PlatformSupport.limitSearchItems(items, limit)
        val body = JsonObject().apply {
            put("query", query)
            put("platform", platformText)
            put("kind", kind)
            put("count", limited.size)
            add("results", JsonArray().apply { limited.forEach { add(it.toJson()) } })
            add("warnings", jsonArray(warnings))
            if (limited.isEmpty()) put("note", "没有找到匹配结果")
        }
        return okResult(
            "找到 ${limited.size} 个结果" + if (warnings.isEmpty()) "" else "（有 ${warnings.size} 条平台警告）",
            body
        )
    }
}

/** 检查所有整合包实例的在线更新 */
object CheckModpackUpdatesTool : AgentTool {
    override val name: String = "check_modpack_updates"
    override val description: String = "检查所有整合包实例是否有新版：当前/最新版本、加载器更新、" +
            "无法识别的实例及原因。只读，不会下载或修改文件。"
    override val parametersSchema: JsonObject = objectSchema()
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val updates = try {
            ModpackUpdateService.checkAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("检查整合包更新失败：${e.message ?: e::class.java.simpleName}")
        }
        val array = JsonArray()
        var updatable = 0
        updates.forEach { info ->
            if (info.hasUpdate) updatable++
            array.add(info.toJson())
        }
        val body = JsonObject().apply {
            put("count", updates.size)
            put("updatableCount", updatable)
            add("updates", array)
        }
        return okResult("检查了 ${updates.size} 个整合包实例，$updatable 个可更新", body)
    }
}

/** 检查实例加载器更新 */
object CheckLoaderUpdatesTool : AgentTool {
    override val name: String = "check_loader_updates"
    override val description: String = "查询指定实例（默认当前实例）同 Minecraft 版本的加载器最新稳定版，" +
            "返回每个已安装加载器的 current/latest。默认排除 beta/alpha；如果当前版本本身就是 beta，则允许 beta。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val info = version.getVersionInfo()
        if (info == null) {
            return failResult("实例「${version.getVersionName()}」的版本信息不可解析，无法检查加载器更新。")
        }
        if (info.loaderInfos.isEmpty()) {
            return okResult(
                "实例「${version.getVersionName()}」是原版，没有安装模组加载器。",
                JsonObject().apply {
                    put("instance", version.getVersionName())
                    put("minecraftVersion", info.minecraftVersion)
                    put("count", 0)
                    add("loaders", JsonArray())
                }
            )
        }
        val checks = try {
            LoaderSupport.check(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("检查加载器更新失败：${e.message ?: e::class.java.simpleName}")
        }
        val array = JsonArray()
        checks.forEach { check ->
            array.add(JsonObject().apply {
                put("loader", check.loader.displayName)
                put("loaderId", check.loader.name.lowercase())
                put("current", check.currentVersion)
                put("latest", check.latestVersion)
                put("hasUpdate", check.hasUpdate)
                put("error", check.error)
            })
        }
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("minecraftVersion", info.minecraftVersion)
            put("count", checks.size)
            add("loaders", array)
        }
        return okResult(
            "实例「${version.getVersionName()}」：${checks.count { it.hasUpdate }} 个加载器有更新",
            body
        )
    }
}

/** 生成整合包更新计划，不写入 */
object PlanModpackUpdateTool : AgentTool {
    override val name: String = "plan_modpack_update"
    override val description: String = "调用项目整合包更新器生成指定实例的变更计划：新增/修改/删除文件、" +
            "下载字节数、受保护而跳过的文件与保留的用户文件数。只读，不写入实例。后续 update_modpack 会使用本计划。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance"),
        properties = mapOf(
            "instance" to stringSchema("整合包实例名称")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.LOW_RISK_WRITE

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val meta = runCatching { ModpackMeta.loadValid(version.getVersionPath()) }.getOrNull()
            ?: return failResult(
                "实例「${version.getVersionName()}」不是可更新的整合包：缺少有效的 modpack_meta.json。"
            )
        val info = try {
            ModpackUpdateService.check(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("检查整合包更新失败：${e.message ?: e::class.java.simpleName}")
        }
        if (!info.hasUpdate && info.reason.isNotBlank()) {
            return failResult(
                "无法识别或无法更新实例「${version.getVersionName()}」：${info.reason}",
                info.toJson()
            )
        }
        val plan = try {
            ModpackUpdateService.plan(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("生成整合包更新计划失败：${e.message ?: e::class.java.simpleName}")
        }

        val key = version.getVersionName()
        ModpackPlanCache.put(key, CachedModpackPlan(info, plan))
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("platform", info.platform.ifBlank { meta.platform })
            put("currentVersionName", info.currentVersionName)
            put("latestVersionName", info.latestVersionName)
            put("currentVersionId", info.currentVersionId)
            put("latestVersionId", info.latestVersionId)
            put("hasUpdate", info.hasUpdate)
            put("reason", info.reason)
            info.loaderUpdate?.let { loader ->
                add("loaderUpdate", JsonObject().apply {
                    put("loader", loader.loader)
                    put("currentVersion", loader.currentVersion)
                    put("latestVersion", loader.latestVersion)
                })
            }
            put("addedCount", plan.added.size)
            put("changedCount", plan.changed.size)
            put("removedCount", plan.removed.size)
            put("downloadBytes", plan.downloadBytes)
            put("downloadHuman", formatBytes(plan.downloadBytes))
            put("userFilesKept", plan.userFilesKept)
            add("added", jsonArray(plan.added.take(200)))
            add("changed", jsonArray(plan.changed.take(200)))
            add("removed", jsonArray(plan.removed.take(200)))
            add("skippedProtected", jsonArray(plan.skippedProtected.take(100)))
            put("backupHint", "更新器会在写入前备份；实际 backupDir 在 update_modpack 结果中返回")
        }
        return okResult(
            "更新计划：${info.currentVersionName} → ${info.latestVersionName}，" +
                    "新增 ${plan.added.size}、修改 ${plan.changed.size}、删除 ${plan.removed.size}，" +
                    "下载 ${formatBytes(plan.downloadBytes)}",
            body
        )
    }
}

/** 执行整合包更新；必须先用 plan_modpack_update 生成计划 */
object UpdateModpackTool : AgentTool {
    override val name: String = "update_modpack"
    override val description: String = "把指定整合包实例原地更新到检测到的最新版本。必须先调用 plan_modpack_update 生成计划；" +
            "执行前会再次检查版本，受 isProtectedPath 保护的文件（saves/、options.txt、servers.dat 等）不会被写入或删除。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance"),
        properties = mapOf(
            "instance" to stringSchema("整合包实例名称")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String {
        val instance = args.optText("instance").orEmpty()
        val cached = ModpackPlanCache.get(instance)
            ?: return "未生成更新计划：请先调用 plan_modpack_update 预览新增/修改/删除的文件与下载大小。\n" +
                    "出于安全考虑，未预览的整合包更新不会执行。"
        val info = cached.info
        val plan = cached.plan
        return buildString {
            appendLine("将原地更新整合包实例「${instance.ifBlank { info.instanceName }}」：")
            appendLine("平台：${info.platform.ifBlank { "unknown" }}")
            appendLine("版本：${info.currentVersionName} → ${info.latestVersionName}")
            appendLine(
                "文件变更：新增 ${plan.added.size}、修改 ${plan.changed.size}、删除 ${plan.removed.size}" +
                        "（共 ${plan.added.size + plan.changed.size + plan.removed.size} 个），" +
                        "下载约 ${formatBytes(plan.downloadBytes)}"
            )
            if (plan.skippedProtected.isNotEmpty()) {
                appendLine("受保护而跳过：${plan.skippedProtected.take(5).joinToString("、")}" +
                        if (plan.skippedProtected.size > 5) " 等 ${plan.skippedProtected.size} 个" else "")
            }
            appendLine("保留用户文件：${plan.userFilesKept} 个（不在整合包清单内的文件不会被删除）")
            appendLine("备份：更新器会在写入前备份到缓存目录的 modpack-backups（实际路径以执行结果 backupDir 为准）")
            appendLine("不会触碰 saves/、world*、options.txt、servers.dat。")
            if (info.reason.isNotBlank()) appendLine("注意：${info.reason}")
        }
    }

    override fun affectedPaths(args: JsonObject): List<String> {
        val instance = args.optText("instance").orEmpty()
        val cached = ModpackPlanCache.get(instance) ?: return emptyList()
        val version = resolveVersion(instance) ?: return emptyList()
        val gameDir = version.getGameDir()
        val plan = cached.plan
        return (plan.added + plan.changed + plan.removed)
            .filterNot { LauncherPathGuard.isProtectedPath(it) }
            .distinct()
            .take(300)
            .map { path ->
                runCatching { ModpackDiff.resolveTarget(gameDir, path).absolutePath }.getOrDefault(path)
            }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val cached = ModpackPlanCache.get(version.getVersionName())
            ?: return failResult(
                "没有找到有效的更新计划。请先调用 plan_modpack_update，并在 10 分钟内执行更新。"
            )
        if (!runCatching { LauncherPathGuard.isManaged(version.getGameDir()) }.getOrDefault(false)) {
            return failResult("实例目录不在启动器管理范围内，已中止更新。")
        }
        val allPaths = cached.plan.added + cached.plan.changed + cached.plan.removed
        val protectedPaths = allPaths.filter { LauncherPathGuard.isProtectedPath(it) }
        if (protectedPaths.isNotEmpty()) {
            return failResult(
                "更新计划包含受保护路径，已中止：${protectedPaths.take(5).joinToString("、")}",
                JsonObject().apply { add("protectedPaths", jsonArray(protectedPaths.take(50))) }
            )
        }

        val current = try {
            ModpackUpdateService.check(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("执行前检查更新失败：${e.message ?: e::class.java.simpleName}")
        }
        if (!current.hasUpdate && current.reason.isNotBlank()) {
            return failResult("无法识别或无法更新实例「${version.getVersionName()}」：${current.reason}")
        }
        if (!current.hasUpdate) {
            return failResult("当前没有可用更新（最新版本：${current.latestVersionName}）。")
        }
        if (current.latestVersionId != cached.info.latestVersionId ||
            current.currentVersionId != cached.info.currentVersionId
        ) {
            return failResult("更新源或当前版本已变化，请重新调用 plan_modpack_update 生成计划。")
        }

        val result = try {
            ModpackUpdateService.apply(version) { message ->
                ToolRuntime.emit("整合包更新：$message")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("整合包更新失败：${e.message ?: e::class.java.simpleName}")
        }

        if (!result.ok) {
            return failResult(
                result.message.ifBlank { "整合包更新失败" },
                JsonObject().apply {
                    put("instance", version.getVersionName())
                    put("backupDir", result.backupDir)
                    put("changedFiles", result.changedFiles)
                }
            )
        }
        ModpackPlanCache.remove(version.getVersionName())
        runCatching { VersionsManager.refresh("ai.update_modpack") }
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("message", result.message)
            put("backupDir", result.backupDir)
            put("changedFiles", result.changedFiles)
            put("fromVersion", cached.info.currentVersionName)
            put("toVersion", cached.info.latestVersionName)
        }
        return okResult(
            "整合包「${version.getVersionName()}」已更新：${result.message.ifBlank { "完成" }}",
            body
        )
    }
}

/** 联网搜索 */
object WebSearchTool : AgentTool {
    override val name: String = "web_search"
    override val description: String = "通过用户在设置中配置的搜索服务商（Tavily/Brave/Serper/自定义）联网搜索，" +
            "返回 title、url、snippet。未配置搜索 API Key 时会明确报错，不会伪造结果。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("query"),
        properties = mapOf(
            "query" to stringSchema("搜索关键词"),
            "count" to integerSchema("返回结果数量", minimum = 1, maximum = 10, default = 5)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optText("query") ?: return failResult("缺少必填参数：query")
        val count = args.optInt("count") ?: 5
        if (count !in 1..10) return failResult("count 必须在 1..10 之间。")
        val results = try {
            WebSearchClient.search(query, count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebSearchException) {
            return failResult(e.message ?: "联网搜索失败")
        } catch (e: Throwable) {
            return failResult("联网搜索失败：${e.message ?: e::class.java.simpleName}")
        }
        val array = JsonArray()
        results.forEach { result ->
            array.add(JsonObject().apply {
                put("title", result.title)
                put("url", result.url)
                put("snippet", result.snippet)
            })
        }
        val body = JsonObject().apply {
            put("query", query)
            put("count", results.size)
            add("results", array)
        }
        return okResult("搜索到 ${results.size} 条结果", body)
    }
}

// ---------------------------------------------------------------------------- caches

private data class CachedModpackPlan(
    val info: ModpackUpdateService.UpdateInfo,
    val plan: ModpackUpdateService.ChangePlan,
    val createdAt: Long = System.currentTimeMillis()
)

private object ModpackPlanCache {
    private const val TTL_MILLIS = 10 * 60 * 1000L
    private val map = java.util.concurrent.ConcurrentHashMap<String, CachedModpackPlan>()

    fun put(instance: String, plan: CachedModpackPlan) {
        map[instance] = plan
    }

    fun get(instance: String): CachedModpackPlan? {
        val plan = map[instance] ?: return null
        if (System.currentTimeMillis() - plan.createdAt > TTL_MILLIS) {
            map.remove(instance)
            return null
        }
        return plan
    }

    fun remove(instance: String) {
        map.remove(instance)
    }
}

private fun ModpackUpdateService.UpdateInfo.toJson(): JsonObject = JsonObject().apply {
    put("instanceName", instanceName)
    put("platform", platform)
    put("currentVersionName", currentVersionName)
    put("latestVersionName", latestVersionName)
    put("currentVersionId", currentVersionId)
    put("latestVersionId", latestVersionId)
    put("hasUpdate", hasUpdate)
    put("downloadUrl", downloadUrl)
    put("reason", reason)
    put("unrecognized", !hasUpdate && reason.isNotBlank())
    loaderUpdate?.let { loader ->
        add("loaderUpdate", JsonObject().apply {
            put("loader", loader.loader)
            put("currentVersion", loader.currentVersion)
            put("latestVersion", loader.latestVersion)
        })
    }
}
