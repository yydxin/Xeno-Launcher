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

package com.movtery.zalithlauncher.game.download.modpack.meta

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 整合包平台。注意：只允许这四个合法值，其它一律视为无效元数据。
 */
enum class ModpackPlatform(val id: String, val displayName: String) {
    MODRINTH("modrinth", "Modrinth"),
    CURSEFORGE("curseforge", "CurseForge"),
    MULTIMC("multimc", "MultiMC"),
    MCBBS("mcbbs", "MCBBS");

    companion object {
        fun fromId(id: String?): ModpackPlatform? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * 整合包来源记录：安装时写入版本目录根部的 `.modpack_source.json`。
 *
 * 新字段 `installedAt` 为 ISO-8601 时间；老版本写入的文件名为 `modpack_meta.json`，
 * 读取时自动兼容（见 [LEGACY_FILE_NAME]），但新写入永远使用 `.modpack_source.json`。
 *
 * - 在线平台必填 projectId/versionId；
 * - 离线平台 projectId/versionId 可为空，但 versionName/gameVersion/fileScope 必填；
 * - [fileScope] 是更新时允许删除的唯一合法依据，绝不包含用户存档与自建数据。
 * - 本地清单（modrinth.index.json / manifest.json）只在没有来源记录时用于展示名称/版本，
 *   绝不用于推断 projectId，也不参与更新。
 */
data class ModpackMeta(
    val platform: String = "",
    val projectId: String? = null,
    val versionId: String? = null,
    val versionName: String = "",
    val installedAt: String? = null,
    val gameVersion: String? = null,
    val loader: String? = null,
    val fileScope: List<String> = emptyList()
) {
    fun platformType(): ModpackPlatform? = ModpackPlatform.fromId(platform)

    companion object {
        /** 新版来源记录文件名（位于实例根目录） */
        const val FILE_NAME = ".modpack_source.json"

        /** 旧版来源记录文件名，仅用于兼容读取 */
        const val LEGACY_FILE_NAME = "modpack_meta.json"

        private val gson = GsonBuilder().setPrettyPrinting().create()

        /** 一条来源记录以及它实际来自哪个文件 */
        data class Record(
            val meta: ModpackMeta,
            val file: File,
            val legacy: Boolean
        )

        fun metaFile(versionDir: File): File = File(versionDir, FILE_NAME)

        fun legacyMetaFile(versionDir: File): File = File(versionDir, LEGACY_FILE_NAME)

        /**
         * 读取来源记录：优先新文件名，其次兼容旧文件名。
         * @return 文件不存在返回 null；内容损坏时抛出异常，由调用方如实展示原因
         */
        fun loadRecord(versionDir: File): Record? {
            val current = metaFile(versionDir)
            if (current.isFile) {
                return Record(parse(current), current, legacy = false)
            }
            val legacy = legacyMetaFile(versionDir)
            if (legacy.isFile) {
                return Record(parse(legacy), legacy, legacy = true)
            }
            return null
        }

        /**
         * 严格解析：文件不存在返回 null；内容损坏时抛出（调用方记录日志并隐藏入口）。
         */
        fun loadStrict(versionDir: File): ModpackMeta? = loadRecord(versionDir)?.meta

        /** 宽松解析：任何异常都返回 null */
        fun load(versionDir: File): ModpackMeta? = runCatching { loadStrict(versionDir) }.getOrNull()

        /**
         * 更新入口的唯一判定条件：文件存在、可正常解析、platform 合法。
         */
        fun loadValid(versionDir: File): ModpackMeta? =
            load(versionDir)?.takeIf { it.platformType() != null }

        /** 当前实例是否只有旧版来源记录（用于 UI 提示） */
        fun isLegacyRecord(versionDir: File): Boolean =
            !metaFile(versionDir).isFile && legacyMetaFile(versionDir).isFile

        fun save(versionDir: File, meta: ModpackMeta) {
            versionDir.mkdirs()
            val target = metaFile(versionDir)
            //先写临时文件再替换，避免写入过程中被杀进程留下半个 JSON
            val temp = File(versionDir, "$FILE_NAME.tmp")
            temp.writeText(gson.toJson(meta))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }

        /**
         * 手工解析而不是直接 Gson 反序列化：
         * 老文件的 fileScope 可能缺失，Gson 会绕过 Kotlin 默认值写入 null；
         * 这里同时兼容旧版可能使用的 fileId 字段。
         */
        private fun parse(file: File): ModpackMeta {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            return ModpackMeta(
                platform = root.string("platform").orEmpty(),
                projectId = root.string("projectId"),
                versionId = root.string("versionId") ?: root.string("fileId"),
                versionName = root.string("versionName").orEmpty(),
                installedAt = root.string("installedAt"),
                gameVersion = root.string("gameVersion"),
                loader = root.string("loader"),
                fileScope = root.getAsJsonArray("fileScope")
                    ?.mapNotNull { element -> runCatching { element.asString }.getOrNull() }
                    ?: emptyList()
            )
        }

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
    }
}

/**
 * 整合包路径保护规则（安装生成 fileScope、后续 diff/删除/解压共用）。
 *
 * 这里所有对外入口都接受“相对实例目录的路径”，统一在内部做规范化；
 * 非法路径（绝对路径、`..` 越界、空路径）一律按“受保护”处理，调用方不得写入。
 */
object ModpackPaths {
    /** 更新备份目录（实例根目录下），永不参与整合包文件管理 */
    const val BACKUP_DIR = ".backup"

    /** 永远保留的文件名（任意层级） */
    val PROTECTED_NAMES = setOf("options.txt", "optionsof.txt", "servers.dat")

    /** 永远保留的顶层目录（存档、日志、截图与玩家数据） */
    val PROTECTED_DIRS = setOf(
        "saves", "playerdata", "journeymap",
        "screenshots", "logs", "crash-reports",
        ".backup", ".zalith", ".zl2"
    )

    /** 实例根部属于启动器/整合包自身的元数据文件，不作为游戏文件处理 */
    val METADATA_NAMES = setOf(
        ModpackMeta.FILE_NAME,
        "${ModpackMeta.FILE_NAME}.tmp",
        ModpackMeta.LEGACY_FILE_NAME,
        "modrinth.index.json",
        "manifest.json",
        "mmc-pack.json",
        "mcbbs.packmeta",
        "instance.cfg"
    )

    /**
     * 规范化相对路径。
     * @return 合法相对路径；绝对路径、盘符路径、空路径、以及任何逃逸出根目录的 `..` 返回 null
     */
    fun normalize(path: String): String? {
        if (path.isBlank()) return null
        if (path.indexOf('\u0000') >= 0) return null
        val unified = path.replace('\\', '/')
        if (unified.startsWith("/")) return null
        //Windows 盘符绝对路径（即使运行在 Android 上也不应接受）
        if (unified.length >= 2 && unified[1] == ':') return null

        val segments = ArrayDeque<String>()
        for (segment in unified.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeLast()
                }
                else -> segments.addLast(segment)
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /**
     * 把相对路径解析为实例内的真实文件；路径非法或（经 canonical 解析后）越界时返回 null。
     */
    fun resolve(instanceDir: File, path: String): File? {
        val normalized = normalize(path) ?: return null
        val target = File(instanceDir, normalized)
        val root = runCatching { instanceDir.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path.trimEnd(File.separatorChar)
        if (canonical.path != rootPath &&
            !canonical.path.startsWith(rootPath + File.separator)
        ) return null
        return target
    }

    /**
     * 整合包更新红线：这些路径永远不能写入、覆盖或删除。
     *
     * 覆盖 saves/、options.txt、servers.dat、screenshots/、logs/、crash-reports/、
     * world 系列目录、任意层级的 playerdata/，以及启动器元数据与更新备份目录。
     */
    fun isProtectedPath(path: String): Boolean {
        val normalized = normalize(path) ?: return true
        val segments = normalized.lowercase().split('/')
        val first = segments.first()
        val last = segments.last()

        if (first in PROTECTED_DIRS) return true
        // world、world_nether、world_the_end 以及用户自建的 world1/world_xxx
        if (first.startsWith("world")) return true
        if (segments.any { it == "playerdata" }) return true
        if (last in PROTECTED_NAMES) return true
        //只把根部的元数据当作元数据；config/manifest.json 之类的嵌套文件仍属于整合包内容
        if (segments.size == 1 && first in METADATA_NAMES) return true
        return false
    }

    /** 兼容上一轮实现的旧名字 */
    fun isProtected(path: String): Boolean = isProtectedPath(path)

    fun isConfigPath(path: String): Boolean {
        val normalized = normalize(path) ?: return false
        return normalized.lowercase().startsWith("config/")
    }

    fun isMetadataPath(path: String): Boolean {
        val normalized = normalize(path) ?: return true
        return normalized.lowercase() in METADATA_NAMES
    }

    /**
     * 安装目录中属于整合包的文件清单：
     * 排除版本自身 json/jar、图标目录、来源记录、更新备份以及所有受保护路径。
     */
    fun buildFileScope(versionDir: File, versionName: String): List<String> {
        if (!versionDir.isDirectory) return emptyList()
        val rootMetadata = setOf("$versionName.json", "$versionName.jar")
        return versionDir.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                normalize(file.relativeTo(versionDir).invariantSeparatorsPath)
            }
            .filterNot { it in rootMetadata }
            .filterNot { it.startsWith(".zalith/") || it.startsWith(".zl2") }
            .filterNot { it.startsWith("${BACKUP_DIR}/") || it == BACKUP_DIR }
            .filterNot { it.startsWith("${ModpackMeta.FILE_NAME}.tmp") }
            .filterNot { isMetadataPath(it) }
            .filterNot { isProtectedPath(it) }
            .distinct()
            .sorted()
            .toList()
    }
}
