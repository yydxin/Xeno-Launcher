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
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPaths
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 一次更新前备份中的单个文件。
 * @param existed 更新前文件是否存在；false 表示这是更新新增的文件，回滚时删除
 * @param sha1 更新前文件的 SHA-1（用于回滚后校验）
 */
internal data class BackupEntry(
    val path: String,
    val existed: Boolean,
    val sha1: String? = null,
    val size: Long = 0L
)

/**
 * `.backup/<时间戳>/history.json` 的结构。
 */
internal data class BackupHistory(
    val schemaVersion: Int = 1,
    val createdAt: String? = null,
    val platform: String? = null,
    val fromVersionId: String? = null,
    val fromVersionName: String? = null,
    val toVersionId: String? = null,
    val toVersionName: String? = null,
    val sourceExisted: Boolean = false,
    val entries: List<BackupEntry>? = null
)

internal data class BackupSession(
    val dir: File,
    val history: BackupHistory
)

internal data class RestoreOutcome(
    val backupDir: File,
    val restored: Int,
    val deleted: Int,
    val errors: List<String>
)

/**
 * 更新事务的落盘层：只负责“备份 / 还原 / 写 JSON”，不参与 diff 与下载决策。
 *
 * 备份布局：
 * ```
 * <实例>/.backup/<时间戳>/
 *   history.json                     # 被覆盖、被删除、以及新增文件的清单
 *   source/.modpack_source.json      # 更新前的来源记录（如果存在）
 *   files/<相对路径>                 # 被覆盖/删除文件的原始内容
 *   report.json                      # 本次更新报告（成功或失败）
 * ```
 */
object ModpackUpdater {
    private const val HISTORY_FILE = "history.json"
    private const val REPORT_FILE = "report.json"
    private const val SOURCE_DIR = "source"
    private const val FILES_DIR = "files"
    private const val PROGRESS_STEP = 20

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun backupRoot(instanceDir: File): File = File(instanceDir, ModpackPaths.BACKUP_DIR)

    /** 最近的、带完整 history.json 的备份目录；不存在时返回 null */
    fun latestBackupDir(instanceDir: File): File? =
        backupRoot(instanceDir).listFiles()
            ?.filter { it.isDirectory && File(it, HISTORY_FILE).isFile }
            ?.maxByOrNull { it.name }

    internal fun readHistory(backupDir: File): BackupHistory? =
        runCatching {
            val file = File(backupDir, HISTORY_FILE)
            if (!file.isFile) return null
            gson.fromJson(file.readText(), BackupHistory::class.java)
        }.getOrNull()

    /**
     * 创建备份。只备份 [targets] 中真实存在的文件，同时为不存在的目标写入 `existed=false`，
     * 以便回滚时删除本次更新新增的文件。
     *
     * 任一文件备份失败都会删除尚未完成的备份目录并抛出异常，避免留下“可回滚”的假象。
     */
    internal fun createBackup(
        instanceDir: File,
        targets: Collection<String>,
        sourceFile: File,
        historyBase: BackupHistory,
        onProgress: (String) -> Unit = {}
    ): BackupSession {
        val root = backupRoot(instanceDir).apply { mkdirs() }
        if (!root.isDirectory) {
            throw IllegalStateException("无法创建备份目录：${root.absolutePath}")
        }

        val baseName = LocalDateTime.now().format(timestampFormatter)
        var backupDir = File(root, baseName)
        var suffix = 1
        while (backupDir.exists()) {
            backupDir = File(root, "$baseName-$suffix")
            suffix++
        }
        if (!backupDir.mkdirs()) {
            throw IllegalStateException("无法创建备份目录：${backupDir.absolutePath}")
        }

        try {
            val entries = mutableListOf<BackupEntry>()
            val filesRoot = File(backupDir, FILES_DIR).apply { mkdirs() }

            targets.distinct().forEachIndexed { index, rawPath ->
                val path = ModpackPaths.normalize(rawPath) ?: return@forEachIndexed
                if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                    return@forEachIndexed
                }
                val target = ModpackPaths.resolve(instanceDir, path)
                    ?: throw IllegalStateException("备份路径越界：$path")
                if (target.isDirectory) {
                    throw IllegalStateException("备份目标是目录而不是文件：$path")
                }
                if (target.isFile) {
                    val backupFile = File(filesRoot, path)
                    backupFile.parentFile?.mkdirs()
                    target.copyTo(backupFile, overwrite = true)
                    entries += BackupEntry(
                        path = path,
                        existed = true,
                        sha1 = sha1(target),
                        size = target.length()
                    )
                } else {
                    entries += BackupEntry(path = path, existed = false)
                }
                if ((index + 1) % PROGRESS_STEP == 0) {
                    onProgress("正在备份将被修改或删除的文件（${index + 1}/${targets.size}）")
                }
            }

            val sourceExisted = sourceFile.isFile
            if (sourceExisted) {
                val sourceBackup = File(backupDir, "$SOURCE_DIR/${ModpackMeta.FILE_NAME}")
                sourceBackup.parentFile?.mkdirs()
                sourceFile.copyTo(sourceBackup, overwrite = true)
            }

            val history = historyBase.copy(
                createdAt = Instant.now().toString(),
                sourceExisted = sourceExisted,
                entries = entries.toList()
            )
            writeJson(File(backupDir, HISTORY_FILE), history)
            onProgress("备份完成：${backupDir.absolutePath}")
            return BackupSession(backupDir, history)
        } catch (e: Throwable) {
            backupDir.deleteRecursively()
            throw e
        }
    }

    /**
     * 从备份还原被覆盖/删除的文件，并恢复更新前的来源记录。
     *
     * 返回的错误列表为空才代表完整回滚；任何路径越界、受保护路径、备份文件缺失都会记录错误，
     * 备份目录本身永远不会被删除，便于用户或开发者继续排查。
     */
    internal fun restore(
        instanceDir: File,
        sourceDir: File,
        backupDir: File
    ): RestoreOutcome {
        val history = readHistory(backupDir)
            ?: return RestoreOutcome(
                backupDir = backupDir,
                restored = 0,
                deleted = 0,
                errors = listOf("备份清单 history.json 缺失或损坏：${backupDir.absolutePath}")
            )

        val errors = mutableListOf<String>()
        var restored = 0
        var deleted = 0

        history.entries.orEmpty().forEach { entry ->
            val path = ModpackPaths.normalize(entry.path)
            if (path == null) {
                errors += "备份中存在非法路径：${entry.path}"
                return@forEach
            }
            if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                errors += "拒绝还原受保护路径：$path"
                return@forEach
            }
            val target = ModpackPaths.resolve(instanceDir, path)
            if (target == null) {
                errors += "还原路径越界：$path"
                return@forEach
            }

            if (entry.existed) {
                val backupFile = File(backupDir, "$FILES_DIR/$path")
                if (!backupFile.isFile) {
                    errors += "备份文件缺失：$path"
                    return@forEach
                }
                runCatching {
                    target.parentFile?.mkdirs()
                    backupFile.copyTo(target, overwrite = true)
                    val expected = entry.sha1
                    if (expected != null && !sha1(target).equals(expected, ignoreCase = true)) {
                        throw IllegalStateException("还原后校验失败")
                    }
                    restored++
                }.onFailure { error ->
                    errors += "还原失败：$path（${readableMessage(error)}）"
                }
            } else {
                if (target.isDirectory) {
                    errors += "拒绝删除目录：$path"
                    return@forEach
                }
                if (target.exists()) {
                    if (target.delete()) {
                        deleted++
                    } else {
                        errors += "删除新增文件失败：$path"
                    }
                }
            }
        }

        //恢复来源记录
        runCatching {
            val target = File(sourceDir, ModpackMeta.FILE_NAME)
            val backupSource = File(backupDir, "$SOURCE_DIR/${ModpackMeta.FILE_NAME}")
            if (history.sourceExisted) {
                if (!backupSource.isFile) {
                    throw IllegalStateException("备份中的来源记录缺失")
                }
                sourceDir.mkdirs()
                backupSource.copyTo(target, overwrite = true)
            } else {
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("无法删除更新写入的来源记录")
                }
            }
        }.onFailure { error ->
            errors += "恢复来源记录失败：${readableMessage(error)}"
        }

        return RestoreOutcome(
            backupDir = backupDir,
            restored = restored,
            deleted = deleted,
            errors = errors
        )
    }

    fun writeReport(backupDir: File, report: Any) {
        writeJson(File(backupDir, REPORT_FILE), report)
    }

    fun copyFile(source: File, target: File) {
        if (!source.isFile) {
            throw IllegalStateException("源文件不存在：${source.absolutePath}")
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        if (!target.isFile) {
            throw IllegalStateException("文件写入失败：${target.absolutePath}")
        }
    }

    fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile) return false
        if (a.length() != b.length()) return false
        return sha1(a).equals(sha1(b), ignoreCase = true)
    }

    private fun writeJson(file: File, value: Any) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(gson.toJson(value))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun readableMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
}
