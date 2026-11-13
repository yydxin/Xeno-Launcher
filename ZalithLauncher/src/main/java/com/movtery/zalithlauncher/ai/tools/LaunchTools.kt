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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.ai.log.LaunchRecordStore
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.activities.runGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 修改实例配置（内存、JVM 参数、渲染器） */
class SetInstanceConfigTool(context: Context) : AgentTool {
    private val appContext = context.applicationContext

    override val name: String = "set_instance_config"
    override val description: String = "修改指定实例的独立配置：内存（512..16384 MB）、JVM 参数、渲染器 ID。" +
            "只修改该实例的 version.config，不修改全局设置、存档或模组；渲染器必须是启动器当前加载的渲染器。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance"),
        properties = mapOf(
            "instance" to stringSchema("实例名称"),
            "ram_mb" to integerSchema(
                "内存分配（MB）；省略表示不修改",
                minimum = 512,
                maximum = 16384
            ),
            "jvm_args" to stringSchema("JVM 参数；传空字符串表示清空并使用全局参数"),
            "renderer" to stringSchema("渲染器唯一 ID 或渲染器 ID；传 default 表示跟随全局")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String {
        val instance = args.optText("instance").orEmpty()
        val changes = mutableListOf<String>()
        args.optInt("ram_mb")?.let { changes += "内存 → $it MB" }
        args.get("jvm_args")?.takeIf { !it.isJsonNull }?.let { changes += "JVM 参数 → ${it.asString}" }
        args.optText("renderer")?.let { changes += "渲染器 → $it" }
        return buildString {
            appendLine("将修改实例「$instance」的独立配置：")
            if (changes.isEmpty()) appendLine("- （没有提供任何要修改的字段，执行会失败）")
            else changes.forEach { appendLine("- $it") }
            appendLine("只写入该实例的 version.config；不会触碰存档、模组或全局设置。")
        }
    }

    override fun affectedPaths(args: JsonObject): List<String> {
        val version = resolveVersion(args.optText("instance")) ?: return emptyList()
        return listOf(File(version.getZalithVersionPath(), "version.config").absolutePath)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val ramMb = args.optInt("ram_mb")
        val jvmArgsElement = args.get("jvm_args")?.takeIf { !it.isJsonNull }
        if (jvmArgsElement != null && (!jvmArgsElement.isJsonPrimitive || !jvmArgsElement.asJsonPrimitive.isString)) {
            return failResult("jvm_args 必须是字符串。")
        }
        val jvmArgsPresent = jvmArgsElement != null
        val jvmArgs = jvmArgsElement?.let { runCatching { it.asString }.getOrNull() }
        val rendererElement = args.get("renderer")?.takeIf { !it.isJsonNull }
        if (rendererElement != null && (!rendererElement.isJsonPrimitive || !rendererElement.asJsonPrimitive.isString)) {
            return failResult("renderer 必须是字符串。")
        }
        val rendererPresent = rendererElement != null
        val rendererRaw = rendererElement?.let { runCatching { it.asString }.getOrNull() }
        if (ramMb == null && !jvmArgsPresent && !rendererPresent) {
            return failResult("至少提供一个要修改的配置项：ram_mb、jvm_args 或 renderer。")
        }
        if (ramMb != null && ramMb !in 512..16384) {
            return failResult("ram_mb 必须在 512..16384 之间。")
        }
        if (jvmArgs != null && jvmArgs.length > 4096) {
            return failResult("jvm_args 过长（${jvmArgs.length} 字符），上限 4096。")
        }

        val config = version.getVersionConfig()
        val oldRam = config.ramAllocation
        val oldJvmArgs = config.jvmArgs
        val oldRenderer = config.renderer
        var rendererIdToSave: String? = null
        var rendererName = ""

        if (rendererPresent) {
            val raw = rendererRaw.orEmpty()
            if (raw.isBlank() || raw.equals("default", ignoreCase = true)) {
                rendererIdToSave = ""
            } else {
                val renderers = runCatching {
                    Renderers.init()
                    Renderers.getRenderers()
                }.getOrDefault(emptyList())
                if (renderers.isEmpty()) {
                    return failResult("启动器渲染器列表尚未初始化，无法校验渲染器。请稍后重试。")
                }
                val found = renderers.firstOrNull {
                    it.getUniqueIdentifier().equals(raw, ignoreCase = true) ||
                            it.getRendererId().equals(raw, ignoreCase = true)
                } ?: return failResult(
                    "渲染器「$raw」不存在。可用渲染器：" +
                            renderers.joinToString("、") { "${it.getUniqueIdentifier()}(${it.getRendererName()})" }
                )
                rendererIdToSave = found.getUniqueIdentifier()
                rendererName = found.getRendererName()
            }
        }

        if (ramMb != null) config.ramAllocation = ramMb
        if (jvmArgsPresent) config.jvmArgs = jvmArgs.orEmpty()
        rendererIdToSave?.let { config.renderer = it }

        val saveResult = runCatching { config.saveWithThrowable() }
        saveResult.exceptionOrNull()?.let { e ->
            // 回滚内存中的修改，避免界面显示与磁盘不一致
            config.ramAllocation = oldRam
            config.jvmArgs = oldJvmArgs
            config.renderer = oldRenderer
            return failResult("保存实例配置失败：${e.message ?: e::class.java.simpleName}")
        }

        val deviceMax = runCatching { version.getRamAllocation(appContext) }.getOrDefault(-1)
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("configPath", File(version.getZalithVersionPath(), "version.config").absolutePath)
            put("oldRamMb", oldRam)
            put("newRamMb", config.ramAllocation)
            put("deviceEffectiveRamMb", deviceMax)
            put("oldJvmArgs", oldJvmArgs)
            put("newJvmArgs", config.jvmArgs)
            put("oldRenderer", oldRenderer)
            put("newRenderer", config.renderer)
            put("newRendererName", rendererName)
            if (ramMb != null && deviceMax in 1 until ramMb) {
                put("warning", "设备可用内存上限为 $deviceMax MB，启动时会被限制到该值。")
            }
        }
        return okResult("实例「${version.getVersionName()}」配置已保存", body)
    }
}

/** 使用当前账号启动实例 */
class LaunchGameTool(context: Context) : AgentTool {
    private val appContext = context.applicationContext

    override val name: String = "launch_game"
    override val description: String = "使用当前登录账号启动指定实例（默认当前实例），并写入启动记录。" +
            "会启动游戏 Activity 并覆盖该实例的 latest_game.log；不会修改存档、模组或配置。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String {
        val instance = args.optText("instance")
        val version = resolveVersion(instance)
        val account = AccountsManager.currentAccountFlow.value
        return buildString {
            appendLine("将启动实例「${version?.getVersionName() ?: instance ?: "当前实例"}」。")
            appendLine("账号：${account?.username ?: "未登录（启动会失败）"}")
            version?.let { appendLine("日志：${instanceLogPath(it).absolutePath}") }
            appendLine("会写入启动记录；不会修改存档、mods 或 options.txt。")
        }
    }

    override fun affectedPaths(args: JsonObject): List<String> {
        val version = resolveVersion(args.optText("instance")) ?: return emptyList()
        return listOf(instanceLogPath(version).absolutePath)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        if (!version.isValid()) {
            return failResult("实例「${version.getVersionName()}」无效：版本目录或版本 JSON 不存在。")
        }
        val account = AccountsManager.currentAccountFlow.value
            ?: return failResult("还没有登录账号，请先在启动器里登录或创建离线账号。")

        val launchContext: Context = if (appContext is Activity) {
            appContext
        } else {
            NewTaskContext(appContext)
        }
        val logFile = instanceLogPath(version)
        try {
            runGame(launchContext, version, account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("启动游戏失败：${e.message ?: e::class.java.simpleName}")
        }

        LaunchRecordStore.recordStart(version, logFile)
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put(
                "version",
                version.getVersionInfo()?.getInfoString() ?: version.getVersionName()
            )
            put("account", account.username)
            put("logPath", logFile.absolutePath)
            put("startedAt", System.currentTimeMillis())
            put("note", "启动记录已写入；退出码需要由游戏退出回调补充。")
        }
        return okResult("已启动实例「${version.getVersionName()}」，账号：${account.username}", body)
    }
}

/** 把实例的加载器更新/安装到指定版本（项目真实 GameInstaller 入口） */
class UpdateLoaderTool(context: Context) : AgentTool {
    private val appContext = context.applicationContext

    override val name: String = "update_loader"
    override val description: String = "使用项目内置的 GameInstaller.modifyVersion 重建实例版本，安装或更新 Fabric、Quilt、" +
            "Forge、NeoForge 加载器。会保留 mods/、saves/ 与实例配置；仅替换版本 json/jar 并补齐 libraries。" +
            "version 省略时选择同 Minecraft 版本的最新稳定版（当前是 beta 时允许 beta）。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("instance", "loader"),
        properties = mapOf(
            "instance" to stringSchema("实例名称"),
            "loader" to stringSchema("加载器", listOf("fabric", "quilt", "forge", "neoforge")),
            "version" to stringSchema("目标加载器版本；省略则选择最新稳定版")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.HIGH_RISK_WRITE

    override fun confirmText(args: JsonObject): String {
        val instance = args.optText("instance").orEmpty()
        val loader = args.optText("loader").orEmpty()
        val target = args.optText("version")?.let { "到 $it" } ?: "到最新稳定版"
        return buildString {
            appendLine("将把实例「$instance」的 ${loader.ifBlank { "模组加载器" }} 更新$target。")
            appendLine("使用项目内置 GameInstaller.modifyVersion 重建版本：会重新下载原版/加载器文件并替换版本 json/jar。")
            appendLine("保留 mods/、saves/、resourcepacks/ 和实例配置；会补齐 libraries，可能耗时较长。")
            appendLine("原版 json/jar 会被安装器内部备份到缓存目录，成功后再清理。")
        }
    }

    override fun affectedPaths(args: JsonObject): List<String> {
        val version = resolveVersion(args.optText("instance")) ?: return emptyList()
        val name = version.getVersionName()
        val versionDir = version.getVersionPath()
        return listOf(
            File(versionDir, "$name.json").absolutePath,
            File(versionDir, "$name.jar").absolutePath,
            File(version.getGameHome(), "libraries").absolutePath,
            PathManager.DIR_CACHE_GAME_DOWNLOADER.absolutePath
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance") ?: return failResult("缺少必填参数：instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val loaderText = args.optText("loader")?.lowercase()
            ?: return failResult("缺少必填参数：loader")
        val loader = when (loaderText) {
            "fabric" -> ModLoader.FABRIC
            "quilt" -> ModLoader.QUILT
            "forge" -> ModLoader.FORGE
            "neoforge" -> ModLoader.NEOFORGE
            else -> return failResult("loader 只能是 fabric、quilt、forge 或 neoforge。")
        }
        val requestedVersion = args.optText("version")

        val resolved = try {
            LoaderSupport.resolve(version, loader, requestedVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoaderSupport.ResolveException) {
            return failResult(e.message ?: "无法解析加载器版本")
        } catch (e: Throwable) {
            return failResult("解析加载器版本失败：${e.message ?: e::class.java.simpleName}")
        }
        if (resolved.alreadyLatest) {
            return okResult(
                "实例「${version.getVersionName()}」的 ${resolved.loaderName} 已经是当前可用的最新版 " +
                        "（${resolved.targetVersion}），无需更新。",
                JsonObject().apply {
                    put("instance", version.getVersionName())
                    put("loader", resolved.loaderName)
                    put("current", resolved.previousVersion.orEmpty())
                    put("latest", resolved.targetVersion)
                    put("alreadyLatest", true)
                }
            )
        }

        val parentJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        val scope = CoroutineScope(
            currentCoroutineContext() + SupervisorJob(parentJob) + Dispatchers.IO
        )
        val deferred = CompletableDeferred<Throwable?>()
        val installer = GameInstaller(appContext, resolved.downloadInfo, scope)
        try {
            installer.modifyVersion(
                isRunning = {
                    deferred.complete(IllegalStateException("已有启动器安装任务在运行，请稍后重试。"))
                },
                onModified = { deferred.complete(null) },
                onError = { deferred.complete(it) }
            )
            val error = withTimeoutOrNull(45 * 60 * 1000L) { deferred.await() }
            if (error == null && !deferred.isCompleted) {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                return failResult("更新加载器超时（45 分钟），已取消本次任务。")
            }
            if (error != null) {
                return failResult("更新加载器失败：${error.message ?: error::class.java.simpleName}")
            }
        } catch (e: CancellationException) {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            throw e
        } catch (e: Throwable) {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            return failResult("更新加载器失败：${e.message ?: e::class.java.simpleName}")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        runCatching { VersionsManager.refresh("ai.update_loader") }
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("loader", resolved.loaderName)
            put("previousVersion", resolved.previousVersion.orEmpty())
            put("newVersion", resolved.targetVersion)
            put("method", "GameInstaller.modifyVersion")
        }
        return okResult(
            "实例「${version.getVersionName()}」的 ${resolved.loaderName} 已更新到 ${resolved.targetVersion}",
            body
        )
    }
}

/**
 * runGame 使用 context.startActivity，Application Context 必须带 NEW_TASK。
 * 用包装器保留对真实 runGame 的调用，而不是复制一份启动逻辑。
 */
private class NewTaskContext(base: Context) : ContextWrapper(base) {
    override fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        super.startActivity(intent)
    }
}
