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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.version.mod.AllModReader
import com.movtery.zalithlauncher.game.version.installed.Version
import kotlinx.coroutines.CancellationException
import java.io.File

/** 列出启动器已知的全部实例 */
object GetInstancesTool : AgentTool {
    override val name: String = "get_instances"
    override val description: String = "列出启动器里所有已安装的 Minecraft 实例，包含实例名、Minecraft 版本、" +
            "加载器及版本、是否整合包、版本目录，以及当前选中项。查询实例或不确定实例名时先调用它。"
    override val parametersSchema: JsonObject = objectSchema()
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val versions = allVersions()
        if (versions.isEmpty()) {
            return failResult("启动器里还没有已安装的实例，或版本列表尚未加载完成。")
        }
        val current = currentVersionName()
        val array = JsonArray()
        versions.take(100).forEach { version ->
            val info = version.getVersionInfo()
            val meta = runCatching { ModpackMeta.loadValid(version.getVersionPath()) }.getOrNull()
            val loaders = info?.loaderInfos?.mapNotNull { loaderInfo ->
                "${loaderInfo.loader.displayName} ${loaderInfo.version}".trim()
                    .takeIf { loaderInfo.loader.displayName.isNotBlank() }
            }.orEmpty()
            array.add(JsonObject().apply {
                put("name", version.getVersionName())
                put("minecraftVersion", info?.minecraftVersion.orEmpty())
                add("loaders", jsonArray(loaders))
                put("loader", loaders.joinToString(", ").ifBlank { "原版" })
                put("isModpack", meta != null)
                put("modpackPlatform", meta?.platform.orEmpty())
                put("modpackVersion", meta?.versionName.orEmpty())
                put("path", version.getVersionPath().absolutePath)
                put("gameDir", version.getGameDir().absolutePath)
                put("valid", version.isValid())
                put("selected", version.getVersionName() == current)
            })
        }
        val body = JsonObject().apply {
            put("count", versions.size)
            put("current", current.orEmpty())
            put("instances", array)
            if (versions.size > 100) put("truncated", true)
        }
        return okResult("共 ${versions.size} 个实例，当前选中：${current ?: "未选择"}", body)
    }
}

/** 当前选中实例的详细配置 */
class GetSelectedInstanceTool(context: Context) : AgentTool {
    private val appContext = context.applicationContext

    override val name: String = "get_selected_instance"
    override val description: String = "读取当前选中实例的详细配置：内存分配、Java 运行环境、渲染器、JVM 参数、" +
            "游戏目录、是否隔离、mods 数量与日志路径。排查启动问题或修改配置前先调用它。"
    override val parametersSchema: JsonObject = objectSchema()
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val version = resolveVersion(null) ?: return notFoundResult(null)
        val info = version.getVersionInfo()
        val config = version.getVersionConfig()
        val rendererId = runCatching { version.getRenderer() }.getOrDefault("")
        val rendererName = runCatching {
            Renderers.init()
            Renderers.getRenderers()
                .firstOrNull { it.getUniqueIdentifier() == rendererId || it.getRendererId() == rendererId }
                ?.getRendererName()
        }.getOrNull()
        val mods = modsDir(version).listFiles()?.filter { it.isFile }.orEmpty()
        val logFile = instanceLogPath(version)
        val loaders = info?.loaderInfos?.mapNotNull { loaderInfo ->
            "${loaderInfo.loader.displayName} ${loaderInfo.version}".trim()
                .takeIf { loaderInfo.loader.displayName.isNotBlank() }
        }.orEmpty()

        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("versionInfo", info?.getInfoString().orEmpty())
            put("minecraftVersion", info?.minecraftVersion.orEmpty())
            add("loaders", jsonArray(loaders))
            put("valid", version.isValid())
            put("ramAllocationConfig", config.ramAllocation)
            put("ramAllocation", runCatching { version.getRamAllocation(appContext) }.getOrDefault(-1))
            put("javaRuntime", version.getJavaRuntime())
            put("javaRuntimeDisplay", version.getJavaRuntime().ifBlank { "跟随全局设置/自动选择" })
            put("renderer", rendererId)
            put("rendererName", rendererName.orEmpty())
            put("rendererConfig", config.renderer)
            put("jvmArgs", version.getJvmArgs())
            put("gameArgs", version.getGameArgs())
            put("gameDir", version.getGameDir().absolutePath)
            put("versionDir", version.getVersionPath().absolutePath)
            put("isolation", version.isIsolation())
            put("skipGameIntegrityCheck", version.skipGameIntegrityCheck())
            put("modsCount", mods.size)
            put("modsDisabledCount", mods.count { it.name.endsWith(".disabled", ignoreCase = true) })
            put("logPath", logFile.absolutePath)
            put("logExists", logFile.isFile)
        }
        return okResult("实例「${version.getVersionName()}」：${info?.getInfoString() ?: "信息未知"}", body)
    }
}

/** 解析 mods 目录下 jar 的元数据，不能只给文件名 */
object ListModsTool : AgentTool {
    override val name: String = "list_mods"
    override val description: String = "解析指定实例（默认当前实例）mods 目录中每个模组的元数据：" +
            "文件名、modid、显示名称、版本、加载器。支持 fabric.mod.json、quilt.mod.json、" +
            "META-INF/mods.toml、mcmod.info 等格式；解析失败的条目会标记为 unknown 并计数，不会只返回文件名。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        val dir = modsDir(version)
        if (!dir.isDirectory) {
            return okResult(
                "实例「${version.getVersionName()}」没有 mods 目录",
                JsonObject().apply {
                    put("instance", version.getVersionName())
                    put("modsDir", dir.absolutePath)
                    put("count", 0)
                    put("unknownCount", 0)
                    put("mods", JsonArray())
                    put("note", "mods 目录不存在")
                }
            )
        }

        val mods = try {
            AllModReader(dir).readAllLocals()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failResult("读取模组元数据失败：${e.message ?: e::class.java.simpleName}")
        }

        val sorted = mods.sortedBy { it.file.name.lowercase() }
        var unknown = 0
        val array = JsonArray()
        sorted.forEach { mod ->
            val isUnknown = mod.notMod || mod.id.isBlank()
            if (isUnknown) unknown++
            array.add(JsonObject().apply {
                put("file", mod.file.name)
                put("modId", mod.id.ifBlank { "unknown" })
                put("name", mod.name.ifBlank { mod.file.name })
                put("version", mod.version?.takeIf { it.isNotBlank() } ?: "unknown")
                put("loader", mod.loader.displayName.ifBlank { "unknown" })
                put("fileSize", mod.fileSize)
                put("disabled", mod.file.name.endsWith(".disabled", ignoreCase = true))
                put("unknown", isUnknown)
            })
        }
        val body = JsonObject().apply {
            put("instance", version.getVersionName())
            put("modsDir", dir.absolutePath)
            put("count", sorted.size)
            put("unknownCount", unknown)
            put("mods", array)
        }
        return okResult(
            "实例「${version.getVersionName()}」共 ${sorted.size} 个模组文件，$unknown 个无法解析元数据",
            body
        )
    }
}

/** 列出光影包 */
object ListShadersTool : AgentTool {
    override val name: String = "list_shaders"
    override val description: String = "列出指定实例（默认当前实例）shaderpacks 目录中的光影包文件或文件夹，" +
            "包含名称、大小和最后修改时间。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        return listFolder(version, shadersDir(version), "shaderpacks", "光影包")
    }
}

/** 列出资源包 */
object ListResourcePacksTool : AgentTool {
    override val name: String = "list_resourcepacks"
    override val description: String = "列出指定实例（默认当前实例）resourcepacks 目录中的资源包文件或文件夹，" +
            "包含名称、大小和最后修改时间。"
    override val parametersSchema: JsonObject = objectSchema(
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略时使用当前选中实例")
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.optText("instance")
        val version = resolveVersion(requested) ?: return notFoundResult(requested)
        return listFolder(version, resourcePacksDir(version), "resourcepacks", "资源包")
    }
}

private fun listFolder(version: Version, dir: File, folderName: String, label: String): ToolResult {
    if (!dir.isDirectory) {
        return okResult(
            "实例「${version.getVersionName()}」没有 $folderName 目录",
            JsonObject().apply {
                put("instance", version.getVersionName())
                put("directory", dir.absolutePath)
                put("count", 0)
                put("entries", JsonArray())
                put("note", "$folderName 目录不存在")
            }
        )
    }
    val entries = dir.listFiles()?.sortedWith(
        compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
    ).orEmpty()
    val array = JsonArray()
    entries.take(500).forEach { file ->
        array.add(JsonObject().apply {
            put("name", file.name)
            put("type", if (file.isDirectory) "directory" else "file")
            put("size", if (file.isDirectory) -1L else runCatching { file.length() }.getOrDefault(0L))
            put("sizeHuman", if (file.isDirectory) "-" else formatBytes(runCatching { file.length() }.getOrDefault(0L)))
            put("modified", runCatching { file.lastModified() }.getOrDefault(0L))
            put("modifiedText", formatTime(runCatching { file.lastModified() }.getOrDefault(0L)))
        })
    }
    val body = JsonObject().apply {
        put("instance", version.getVersionName())
        put("directory", dir.absolutePath)
        put("count", entries.size)
        put("entries", array)
        if (entries.size > 500) put("truncated", true)
    }
    return okResult("实例「${version.getVersionName()}」共 ${entries.size} 个$label", body)
}
