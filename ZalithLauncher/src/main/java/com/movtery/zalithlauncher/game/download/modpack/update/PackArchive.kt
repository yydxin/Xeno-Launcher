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

import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPaths
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 整合包压缩包解压。
 *
 * 安全规则：
 * - 每个 entry 先经 [ModpackPaths.normalize] 规范化，绝对路径、`..` 越界、NUL 一律整包拒绝；
 * - 落盘前再次用 canonicalPath 校验目标必须位于 [dest] 内；
 * - 解压失败不会留下“看似成功”的结果：entry 数为 0 或任一文件未落盘都会抛异常。
 */
object PackArchive {
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP = 50

    fun extractZip(
        zip: File,
        dest: File,
        onProgress: (String) -> Unit = {}
    ) {
        if (!zip.isFile) throw IllegalStateException("整合包压缩包不存在：${zip.absolutePath}")
        dest.mkdirs()
        if (!dest.isDirectory) throw IllegalStateException("无法创建解压目录：${dest.absolutePath}")

        val destRoot = dest.canonicalFile
        val destPrefix = destRoot.path.trimEnd(File.separatorChar) + File.separator
        val extracted = mutableListOf<File>()
        var entryCount = 0

        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entryCount++

                val normalized = ModpackPaths.normalize(entry.name)
                    ?: throw IllegalStateException("整合包压缩包包含非法路径：${entry.name}")

                val target = File(dest, normalized)
                val canonical = target.canonicalFile
                if (!canonical.path.startsWith(destPrefix)) {
                    throw IllegalStateException("整合包压缩包尝试解压到目标目录之外：${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!canonical.isDirectory && !canonical.mkdirs()) {
                        throw IllegalStateException("无法创建解压目录：${canonical.absolutePath}")
                    }
                } else {
                    canonical.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs()) {
                            throw IllegalStateException("无法创建解压目录：${parent.absolutePath}")
                        }
                    }
                    canonical.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                    extracted += canonical
                    if (extracted.size % PROGRESS_STEP == 0) {
                        onProgress("正在解压整合包（${extracted.size} 个文件）")
                    }
                }
                input.closeEntry()
            }
        }

        if (entryCount == 0) {
            throw IllegalStateException("不是有效的整合包压缩包：${zip.name}")
        }
        val missing = extracted.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "有 ${missing.size} 个文件未能解压，例如：" +
                        missing.take(5).joinToString { it.name }
            )
        }
        onProgress("整合包解压完成（${extracted.size} 个文件）")
    }
}
