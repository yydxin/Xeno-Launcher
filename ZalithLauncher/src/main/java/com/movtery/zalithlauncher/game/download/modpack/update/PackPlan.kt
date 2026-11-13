/*
 * Zalith Launcher 2 Community
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.download.modpack.update

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPlatform
import java.io.File

/** 随包携带的文件（overrides / .minecraft 等），[path] 相对游戏目录 */
data class PackFile(val path: String, val file: File)

/** 清单声明的下载文件，[path] 相对游戏目录 */
data class PackDownload(
    val path: String,
    val url: String,
    val sha1: String? = null,
    val size: Long = 0L
)

/**
 * 新版整合包清单的统一视图。四类平台只是解析来源不同，后续 diff/更新完全共用。
 */
data class PackPlan(
    val platform: ModpackPlatform,
    val name: String,
    val version: String,
    val gameVersion: String? = null,
    val loader: String? = null,
    val projectId: String? = null,
    val versionId: String? = null,
    val files: List<PackFile> = emptyList(),
    val downloads: List<PackDownload> = emptyList()
) {
    /** 清单声明的全部路径（去重、按路径排序） */
    val allPaths: List<String>
        get() = (files.map { it.path } + downloads.map { it.path }).distinct().sorted()
}

/**
 * 四类整合包清单解析：输入解压后的包根目录，输出统一的 [PackPlan]。
 */
object PackPlanParser {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** 部分整合包压缩包内还有一层目录 */
    fun resolveRoot(extracted: File): File {
        var root = extracted
        repeat(2) {
            val children = root.listFiles()?.filter { !it.name.startsWith(".") } ?: return@repeat
            if (children.size == 1 && children[0].isDirectory) root = children[0]
        }
        return root
    }

    fun detect(relativePaths: Collection<String>): ModpackPlatform? {
        val names = relativePaths.map { it.replace('\\', '/').trimStart('/') }
        fun anySuffix(suffix: String) = names.any { it == suffix || it.endsWith("/$suffix") }
        return when {
            anySuffix("modrinth.index.json") -> ModpackPlatform.MODRINTH
            anySuffix("mcbbs.packmeta") -> ModpackPlatform.MCBBS
            anySuffix("mmc-pack.json") || anySuffix("instance.cfg") -> ModpackPlatform.MULTIMC
            anySuffix("manifest.json") -> ModpackPlatform.CURSEFORGE
            else -> null
        }
    }

    fun parse(extracted: File): PackPlan {
        val root = resolveRoot(extracted)
        val platform = detect(
            root.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        ) ?: throw IllegalArgumentException("Unsupported modpack format")
        return when (platform) {
            ModpackPlatform.MODRINTH -> parseModrinth(root)
            ModpackPlatform.CURSEFORGE -> parseCurseForge(root)
            ModpackPlatform.MULTIMC -> parseMultiMc(root)
            ModpackPlatform.MCBBS -> parseMcbbs(root)
        }
    }

    private fun parseModrinth(root: File): PackPlan {
        val index = gson.fromJson(File(root, "modrinth.index.json").readText(), JsonObject::class.java)
        val dependencies = index.getAsJsonObject("dependencies")
        val downloads = mutableListOf<PackDownload>()
        index.getAsJsonArray("files")?.forEach { element ->
            val file = element.asJsonObject
            val path = file.string("path") ?: return@forEach
            val url = file.getAsJsonArray("downloads")?.firstOrNull()?.asString ?: return@forEach
            val env = file.getAsJsonObject("env")
            if (env?.string("client") == "unsupported") return@forEach
            downloads += PackDownload(
                path = path,
                url = url,
                sha1 = file.getAsJsonObject("hashes")?.string("sha1"),
                size = file.get("fileSize")?.asLong ?: 0L
            )
        }
        val overrides = File(root, "overrides")
        return PackPlan(
            platform = ModpackPlatform.MODRINTH,
            name = index.string("name") ?: "Modrinth Pack",
            version = index.string("versionNumber") ?: index.string("versionId") ?: "",
            gameVersion = dependencies?.string("minecraft"),
            loader = dependencies?.entrySet()?.firstOrNull { it.key != "minecraft" }
                ?.let { "${it.key} ${it.value.asString}" },
            files = collect(overrides.takeIf { it.isDirectory } ?: root),
            downloads = downloads
        )
    }

    private fun parseCurseForge(root: File): PackPlan {
        val manifest = gson.fromJson(File(root, "manifest.json").readText(), JsonObject::class.java)
        val minecraft = manifest.getAsJsonObject("minecraft")
        val overridesName = manifest.string("overrides") ?: "overrides"
        val overrides = File(root, overridesName).takeIf { it.isDirectory } ?: File(root, "overrides")
        return PackPlan(
            platform = ModpackPlatform.CURSEFORGE,
            name = manifest.string("name") ?: "CurseForge Pack",
            version = manifest.string("version") ?: "",
            gameVersion = minecraft?.string("version"),
            loader = minecraft?.getAsJsonArray("modLoaders")?.firstOrNull()
                ?.asJsonObject?.string("id"),
            // CF 的文件列表需要 projectID/fileID 联网解析，离线导入只能覆盖 overrides
            files = collect(overrides.takeIf { it.isDirectory } ?: root)
        )
    }

    private fun parseMultiMc(root: File): PackPlan {
        val mmc = File(root, "mmc-pack.json").takeIf { it.isFile }?.let {
            runCatching { gson.fromJson(it.readText(), JsonObject::class.java) }.getOrNull()
        }
        var gameVersion: String? = null
        mmc?.getAsJsonArray("components")?.forEach { element ->
            val component = element.asJsonObject
            if (component.string("uid") == "net.minecraft") gameVersion = component.string("version")
        }
        val name = File(root, "instance.cfg").takeIf { it.isFile }
            ?.readLines()?.firstOrNull { it.startsWith("name=") }?.substringAfter('=')
            ?: root.name
        val contentRoot = File(root, ".minecraft").takeIf { it.isDirectory } ?: root
        return PackPlan(
            platform = ModpackPlatform.MULTIMC,
            name = name,
            version = gameVersion ?: "",
            gameVersion = gameVersion,
            // 排除实例自身元数据
            files = collect(contentRoot).filterNot {
                it.path == "mmc-pack.json" || it.path == "instance.cfg"
            }
        )
    }

    private fun parseMcbbs(root: File): PackPlan {
        val packMeta = File(root, "mcbbs.packmeta").takeIf { it.isFile }?.let {
            runCatching { gson.fromJson(it.readText(), JsonObject::class.java) }.getOrNull()
        }
        val manifest = File(root, "manifest.json").takeIf { it.isFile }?.let {
            runCatching { gson.fromJson(it.readText(), JsonObject::class.java) }.getOrNull()
        }
        val overrides = File(root, "overrides").takeIf { it.isDirectory } ?: root
        return PackPlan(
            platform = ModpackPlatform.MCBBS,
            name = packMeta?.string("name") ?: manifest?.string("name") ?: "MCBBS Pack",
            version = packMeta?.string("version") ?: manifest?.string("version") ?: "",
            files = collect(overrides).filterNot {
                it.path == "mcbbs.packmeta" || it.path == "manifest.json"
            }
        )
    }

    private fun collect(root: File): List<PackFile> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.name.startsWith(".") }
            .map { file ->
                PackFile(file.relativeTo(root).invariantSeparatorsPath, file)
            }
            .filter { it.path.isNotEmpty() }
            .toList()
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}
