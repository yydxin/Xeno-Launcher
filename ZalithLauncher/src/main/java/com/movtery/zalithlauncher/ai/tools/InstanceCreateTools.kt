package com.movtery.zalithlauncher.ai.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersion
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.quilt.QuiltVersion
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.forge.ForgeVersion
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.neoforge.NeoForgeVersion
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.game.versioninfo.MinecraftVersions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 读取 Mojang 版本清单；版本号只能来自这里。 */
object GetMinecraftVersionsTool : AgentTool {
    override val name: String = "get_minecraft_versions"
    override val description: String =
        "读取 Mojang 官方版本清单，返回版本 id 与类型（release/snapshot/old_beta/old_alpha）。" +
            "创建实例前的 mc_version 必须来自本工具，不得凭记忆填写。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "type" to stringSchema(
                "版本类型；all 表示全部",
                enumValues = listOf("release", "snapshot", "old_beta", "old_alpha", "all"),
                default = "release"
            ),
            "keyword" to stringSchema("按版本号片段过滤，例如 1.20"),
            "limit" to integerSchema("最多返回条数", minimum = 1, maximum = 100, default = 30)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val type = args.optText("type")?.lowercase() ?: "release"
        val keyword = args.optText("keyword")
        val limit = (args.optInt("limit") ?: 30).coerceIn(1, 100)
        val versions = MinecraftVersions.getVersionManifest().versions
            .asSequence()
            .filter { type == "all" || it.type == type }
            .filter { keyword.isNullOrBlank() || it.id.contains(keyword, ignoreCase = true) }
            .take(limit)
            .toList()
        val array = JsonArray()
        versions.forEach { v ->
            array.add(JsonObject().apply {
                addProperty("id", v.id)
                addProperty("type", v.type)
                addProperty("releaseTime", v.releaseTime)
            })
        }
        return okResult(
            summary = "获取到 ${versions.size} 个 Minecraft 版本（$type）",
            body = JsonObject().apply { add("versions", array) }
        )
    }
}

/** 查询某 Minecraft 版本可用的加载器版本。 */
object GetLoaderVersionsTool : AgentTool {
    override val name: String = "get_loader_versions"
    override val description: String =
        "查询指定 Minecraft 版本可用的 Fabric/Quilt/Forge/NeoForge 加载器版本。" +
            "创建带加载器的实例时，loader_version 必须来自本工具。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("loader", "mc_version"),
        properties = mapOf(
            "loader" to stringSchema("加载器", enumValues = listOf("fabric", "quilt", "forge", "neoforge")),
            "mc_version" to stringSchema("Minecraft 版本，例如 1.20.1"),
            "limit" to integerSchema("最多返回条数", minimum = 1, maximum = 50, default = 15)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val loader = args.optText("loader")?.lowercase()
            ?: return failResult("缺少 loader")
        val mc = args.optText("mc_version") ?: return failResult("缺少 mc_version")
        val limit = (args.optInt("limit") ?: 15).coerceIn(1, 50)
        val modLoader = when (loader) {
            "fabric" -> ModLoader.FABRIC
            "quilt" -> ModLoader.QUILT
            "forge" -> ModLoader.FORGE
            "neoforge" -> ModLoader.NEOFORGE
            else -> return failResult("不支持的加载器：$loader")
        }
        val candidates = LoaderSupport.candidates(modLoader, mc).take(limit)
        val array = JsonArray()
        candidates.forEach { c ->
            array.add(JsonObject().apply {
                addProperty("version", c.version)
                addProperty("stable", c.stable)
            })
        }
        return okResult(
            summary = "查询到 ${candidates.size} 个 ${modLoader.displayName} 版本（MC $mc）",
            body = JsonObject().apply { add("versions", array) }
        )
    }
}

/**
 * 创建实例：下载游戏本体与加载器（不自动装模组）。
 * 复用现有 GameInstaller，不另起下载器；安装完成后刷新并验证实例存在。
 */
class CreateInstanceTool(private val context: Context) : AgentTool {
    override val name: String = "create_instance"
    override val description: String =
        "创建新实例并下载 Minecraft 游戏本体；可选安装 Fabric、Quilt、Forge 或 NeoForge。" +
            "mc_version 必须来自 get_minecraft_versions，loader_version 必须来自 get_loader_versions。" +
            "安装可能需要数分钟；完成后会用实例列表验证。不会修改现有实例。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("name", "mc_version"),
        properties = mapOf(
            "name" to stringSchema("新实例名称，例如 1.20.1-Fabric"),
            "mc_version" to stringSchema("Minecraft 版本 id"),
            "loader" to stringSchema("加载器；留空表示原版", enumValues = listOf("fabric", "quilt", "forge", "neoforge")),
            "loader_version" to stringSchema("加载器版本；省略时选择最新稳定版")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String =
        "将创建实例「${args.optText("name").orEmpty()}」，下载 Minecraft " +
            "${args.optText("mc_version").orEmpty()} 与加载器，可能占用数百 MB 磁盘和流量。"

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.optText("name") ?: return failResult("缺少实例名称")
        if (!isValidName(name)) return failResult("实例名称不合法：不能包含路径分隔符或特殊字符")
        if (allVersions().any { it.getVersionName().equals(name, ignoreCase = true) }) {
            return failResult("实例「$name」已存在")
        }
        val mc = args.optText("mc_version") ?: return failResult("缺少 mc_version")
        val manifest = MinecraftVersions.getVersionManifest()
        if (manifest.versions.none { it.id == mc }) {
            return failResult("Minecraft 版本不存在：$mc，请先调用 get_minecraft_versions")
        }

        var info = GameDownloadInfo(gameVersion = mc, customVersionName = name)
        val loader = args.optText("loader")
        if (!loader.isNullOrBlank()) {
            val modLoader = when (loader.lowercase()) {
                "fabric" -> ModLoader.FABRIC
                "quilt" -> ModLoader.QUILT
                "forge" -> ModLoader.FORGE
                "neoforge" -> ModLoader.NEOFORGE
                else -> return failResult("不支持的加载器：$loader")
            }
            val candidates = runCatching { LoaderSupport.candidates(modLoader, mc) }
                .getOrElse { return failResult("查询加载器版本失败：${it.message}") }
            if (candidates.isEmpty()) return failResult("MC $mc 没有可用的 ${modLoader.displayName} 版本")
            val requested = args.optText("loader_version")
            val chosen = if (requested.isNullOrBlank()) {
                candidates.firstOrNull { it.stable } ?: candidates.first()
            } else {
                candidates.firstOrNull { it.version == requested }
                    ?: return failResult("加载器版本不存在：$requested，请先调用 get_loader_versions")
            }
            info = when (modLoader) {
                ModLoader.FABRIC -> info.copy(fabric = chosen.addon as FabricVersion)
                ModLoader.QUILT -> info.copy(quilt = chosen.addon as QuiltVersion)
                ModLoader.FORGE -> info.copy(forge = chosen.addon as ForgeVersion)
                ModLoader.NEOFORGE -> info.copy(neoforge = chosen.addon as NeoForgeVersion)
                else -> return failResult("不支持的加载器：$loader")
            }
        }

        val deferred = CompletableDeferred<Result<String>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            GameInstaller(context.applicationContext, info, scope).installGame(
                onInstalled = { deferred.complete(Result.success(it)) },
                onError = { deferred.complete(Result.failure(it)) },
                onGameAlreadyInstalled = {
                    deferred.complete(Result.failure(IllegalStateException("已存在同名实例")))
                }
            )
            val result = withTimeoutOrNull(30 * 60 * 1000L) { deferred.await() }
                ?: return failResult("安装超时（30 分钟），请查看实例列表确认是否已完成")
            result.fold(
                onSuccess = {
                    VersionsManager.refresh("ai.create_instance")
                    val exists = withTimeoutOrNull(10_000L) {
                        while (allVersions().none { v -> v.getVersionName() == it }) delay(200)
                        true
                    } ?: false
                    if (exists) {
                        okResult(
                            summary = "实例「$it」创建成功（Minecraft $mc${if (loader.isNullOrBlank()) "" else " + $loader"}）",
                            body = JsonObject().apply {
                                addProperty("instance", it)
                                addProperty("minecraft", mc)
                                addProperty("loader", loader.orEmpty())
                            }
                        )
                    } else {
                        failResult("安装进程结束，但实例列表中没有「$it」，请检查日志")
                    }
                },
                onFailure = { failResult("创建实例失败：${it.message ?: it::class.java.simpleName}") }
            )
        } finally {
            scope.cancel()
        }
    }

    private fun isValidName(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 &&
            !name.contains('/') && !name.contains('\\') &&
            name != "." && name != ".." &&
            name.none { it in "\\:*?\"<>|" }
}
