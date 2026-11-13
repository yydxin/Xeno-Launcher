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

import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPaths
import java.io.File
import java.security.MessageDigest

/** 一次增量更新的变更集合（路径相对游戏目录） */
data class ModpackChanges(
    val added: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    /** 新清单里有、但被保护规则拦下的路径（不会写入游戏目录） */
    val skippedProtected: List<String> = emptyList(),
    /** 处理单个文件时的异常（只影响该文件，不中断流程） */
    val fileErrors: List<String> = emptyList()
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || modified.isNotEmpty() || deleted.isNotEmpty()
    val total: Int get() = added.size + modified.size + deleted.size
}

/**
 * 文件级 diff：以旧 `fileScope` 为删除依据，逐文件比较 SHA-1（无 SHA-1 时逐字节）。
 */
object ModpackDiff {
    /** 去掉可能存在的 overrides/ 前缀，得到合并布局下的语义路径 */
    fun canonical(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/').lowercase()
        return if (normalized.startsWith("overrides/")) normalized.removePrefix("overrides/") else normalized
    }

    /** 兼容合并/未合并 overrides 的布局：优先真实路径，其次 overrides/ 下的同名文件 */
    fun resolveTarget(targetDir: File, path: String): File {
        val direct = File(targetDir, path)
        if (direct.exists()) return direct
        val legacy = File(File(targetDir, "overrides"), path)
        return if (legacy.exists()) legacy else direct
    }

    fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().use { left ->
            b.inputStream().use { right ->
                val bufA = ByteArray(32 * 1024)
                val bufB = ByteArray(32 * 1024)
                while (true) {
                    val readA = left.read(bufA)
                    if (readA == -1) return right.read() == -1
                    var offset = 0
                    while (offset < readA) {
                        val readB = right.read(bufB, 0, readA - offset)
                        if (readB == -1) return false
                        for (i in 0 until readB) {
                            if (bufA[offset + i] != bufB[i]) return false
                        }
                        offset += readB
                    }
                }
            }
        }
    }

    fun computeChanges(targetDir: File, plan: PackPlan, meta: ModpackMeta): ModpackChanges {
        val added = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // 新清单路径 → 清单内 SHA-1（下载文件）；overrides 文件用解包后的实际 SHA-1
        val incoming = LinkedHashMap<String, String?>()
        plan.files.forEach { incoming[it.path] = null }
        plan.downloads.forEach { incoming[it.path] = it.sha1 }
        val incomingCanonical = incoming.keys.map { canonical(it) }.toSet()

        incoming.forEach { (path, declaredSha1) ->
            if (ModpackPaths.isProtected(path)) {
                skipped += path
                return@forEach
            }
            runCatching {
                val target = resolveTarget(targetDir, path)
                val source = plan.files.firstOrNull { it.path == path }?.file
                val newSha1 = declaredSha1
                    ?: source?.takeIf { it.isFile }?.let { sha1(it) }
                when {
                    !target.isFile -> added += path
                    newSha1 != null && newSha1.equals(sha1(target), ignoreCase = true) -> Unit
                    newSha1 != null -> modified += path
                    source == null || !source.isFile -> modified += path
                    !sameContent(source, target) -> modified += path
                }
            }.onFailure {
                // 单个文件异常只记录，不影响整体流程
                errors += "$path: ${it.message}"
                if (path !in added && path !in modified) modified += path
            }
        }

        val deleted = meta.fileScope
            .filterNot { canonical(it) in incomingCanonical }
            .filterNot { ModpackPaths.isProtected(it) }
            .filter { resolveTarget(targetDir, it).isFile }
            .sorted()

        meta.fileScope
            .filterNot { canonical(it) in incomingCanonical }
            .filter { ModpackPaths.isProtected(it) }
            .forEach { skipped += it }

        return ModpackChanges(
            added = added.distinct().sorted(),
            modified = modified.distinct().sorted(),
            deleted = deleted,
            skippedProtected = skipped.distinct().sorted(),
            fileErrors = errors
        )
    }
}
