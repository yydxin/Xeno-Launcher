package com.movtery.zalithlauncher.ai.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion
import com.movtery.zalithlauncher.game.download.modpack.install.ModPackInstaller
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private fun platformOf(raw: String?): Platform? = when (raw?.trim()?.lowercase()) {
    "modrinth" -> Platform.MODRINTH
    "curseforge" -> Platform.CURSEFORGE
    else -> null
}

private fun PlatformVersion.toJson(): JsonObject = JsonObject().apply {
    addProperty("version_id", platformId())
    addProperty("name", platformDisplayName())
    addProperty("version", platformVersion())
    addProperty("file_name", platformFileName())
    addProperty("size", platformFileSize())
    addProperty("release_type", platformReleaseType().name)
    addProperty("published", platformDatePublished().toString())
    add("game_versions", JsonArray().apply { platformGameVersion().forEach { add(it) } })
    add("loaders", JsonArray().apply { platformLoaders().forEach { add(it.getDisplayName()) } })
}

object SearchModpacksTool : AgentTool {
    override val name: String = "search_modpacks"
    override val description: String =
        "搜索 Modrinth/CurseForge 整合包，返回 project_id、名称、简介、下载量与图标。安装前先用本工具确认项目。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("query"),
        properties = mapOf(
            "query" to stringSchema("整合包关键词"),
            "platform" to stringSchema("平台", enumValues = listOf("modrinth", "curseforge"), default = "modrinth"),
            "limit" to integerSchema("返回数量", minimum = 1, maximum = 20, default = 8)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.optText("query") ?: return failResult("缺少 query")
        val platform = platformOf(args.optText("platform") ?: "modrinth")
            ?: return failResult("不支持的平台")
        val limit = (args.optInt("limit") ?: 8).coerceIn(1, 20)
        val items = PlatformSupport.search(platform, query, "modpack", null, null, limit)
        val array = JsonArray()
        items.forEach { item ->
            array.add(JsonObject().apply {
                addProperty("platform", platform.name.lowercase())
                addProperty("project_id", item.id)
                addProperty("slug", item.slug)
                addProperty("title", item.title)
                addProperty("description", item.description)
                addProperty("downloads", item.downloads)
                addProperty("iconUrl", item.iconUrl.orEmpty())
            })
        }
        return okResult(
            summary = "在 ${platform.displayName} 搜索到 ${items.size} 个整合包",
            body = JsonObject().apply { add("modpacks", array) }
        )
    }
}

object GetModpackVersionsTool : AgentTool {
    override val name: String = "get_modpack_versions"
    override val description: String =
        "查询整合包项目的可用版本。返回的 version_id 可直接传给 install_modpack。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("project_id"),
        properties = mapOf(
            "project_id" to stringSchema("search_modpacks 返回的 project_id"),
            "platform" to stringSchema("平台", enumValues = listOf("modrinth", "curseforge"), default = "modrinth"),
            "limit" to integerSchema("返回数量", minimum = 1, maximum = 50, default = 20)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val projectId = args.optText("project_id") ?: return failResult("缺少 project_id")
        val platform = platformOf(args.optText("platform") ?: "modrinth")
            ?: return failResult("不支持的平台")
        val limit = (args.optInt("limit") ?: 20).coerceIn(1, 50)
        val versions = runCatching { PlatformSupport.listVersions(platform, projectId, limit) }
            .getOrElse { return failResult("查询整合包版本失败：${it.message}") }
        val array = JsonArray()
        versions.forEach { array.add(it.toJson()) }
        return okResult(
            summary = "查询到 ${versions.size} 个整合包版本",
            body = JsonObject().apply { add("versions", array) }
        )
    }
}

class InstallModpackTool(private val context: Context) : AgentTool {
    override val name: String = "install_modpack"
    override val description: String =
        "一键安装 Modrinth/CurseForge 整合包为新实例：自动下载整合包、游戏本体与加载器，并保留存档保护。" +
            "project_id 来自 search_modpacks；version_id 来自 get_modpack_versions（省略则选最新兼容版）。" +
            "会创建新实例，不会覆盖现有实例。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("project_id", "instance_name"),
        properties = mapOf(
            "project_id" to stringSchema("整合包 project_id"),
            "platform" to stringSchema("平台", enumValues = listOf("modrinth", "curseforge"), default = "modrinth"),
            "version_id" to stringSchema("指定版本 id；省略则选最新兼容版"),
            "instance_name" to stringSchema("新实例名称")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String =
        "将新建实例「${args.optText("instance_name").orEmpty()}」并下载安装整合包，可能占用数百 MB 到数 GB 磁盘和流量。"

    override suspend fun execute(args: JsonObject): ToolResult {
        val projectId = args.optText("project_id") ?: return failResult("缺少 project_id")
        val instanceName = args.optText("instance_name") ?: return failResult("缺少 instance_name")
        if (instanceName.isBlank() || instanceName.contains('/') || instanceName.contains('\\')) {
            return failResult("实例名称不合法")
        }
        if (allVersions().any { it.getVersionName().equals(instanceName, ignoreCase = true) }) {
            return failResult("实例「$instanceName」已存在")
        }
        val platform = platformOf(args.optText("platform") ?: "modrinth")
            ?: return failResult("不支持的平台")
        val versionId = args.optText("version_id")
        val version = runCatching {
            PlatformSupport.findCompatibleVersion(
                platform = platform,
                projectId = projectId,
                kind = "modpack",
                minecraftVersion = "",
                loaderName = null,
                versionId = versionId
            )
        }.getOrElse { return failResult("获取整合包版本失败：${it.message}") }
            ?: return failResult("没有找到可安装的整合包版本")

        val deferred = CompletableDeferred<Result<String>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            ModPackInstaller(
                context = context.applicationContext,
                version = version,
                iconUrl = null,
                scope = scope,
                waitForVersionName = { instanceName },
                waitForConfirmMobileData = { true }
            ).installModPack(
                onInstalled = { deferred.complete(Result.success(it)) },
                onCancelled = { deferred.complete(Result.failure(IllegalStateException("安装被取消"))) },
                onError = { deferred.complete(Result.failure(it)) }
            )
            val result = withTimeoutOrNull(60 * 60 * 1000L) { deferred.await() }
                ?: return failResult("安装超时（60 分钟），请查看实例列表确认")
            result.fold(
                onSuccess = {
                    VersionsManager.refresh("ai.install_modpack")
                    val exists = withTimeoutOrNull(15_000L) {
                        while (allVersions().none { v -> v.getVersionName() == it }) delay(200)
                        true
                    } ?: false
                    if (exists) {
                        okResult(
                            summary = "整合包实例「$it」安装成功（${version.platformDisplayName()}）",
                            body = JsonObject().apply {
                                addProperty("instance", it)
                                addProperty("modpack", version.platformDisplayName())
                                addProperty("version_id", version.platformId())
                            }
                        )
                    } else {
                        failResult("安装进程结束，但实例列表中没有「$it」")
                    }
                },
                onFailure = { failResult("整合包安装失败：${it.message ?: it::class.java.simpleName}") }
            )
        } finally {
            scope.cancel()
        }
    }
}
