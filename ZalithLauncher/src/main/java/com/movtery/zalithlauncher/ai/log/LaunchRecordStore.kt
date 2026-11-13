/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.log

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "LaunchRecordStore"
private const val MAX_RECORDS = 30
private const val FILE_NAME = "launch-records.json"

/**
 * 一次游戏启动的本地记录。
 *
 * @param instanceName 实例名称
 * @param version 版本描述（通常为“MC 版本 + 加载器版本”）
 * @param timestamp 启动时间（Unix 毫秒）
 * @param logPath 本次启动对应的日志文件绝对路径
 * @param exitCode 退出码；进程尚未结束或无法获取时为 null
 */
data class LaunchRecord(
    val instanceName: String,
    val version: String,
    val timestamp: Long,
    val logPath: String,
    val exitCode: Int? = null
)

/**
 * 启动记录持久化。
 *
 * - 只保留最近 [MAX_RECORDS] 条；
 * - 写入采用“同目录临时文件 + 原子替换”，避免进程被杀时留下半个 JSON；
 * - 所有异常都在内部消化，绝不向启动流程抛出。
 */
object LaunchRecordStore {
    private val lock = Any()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<MutableList<LaunchRecord>>() {}.type

    private var cache: MutableList<LaunchRecord>? = null

    /** 记录文件；PathManager 尚未初始化时返回 null */
    private fun recordFile(): File? = runCatching {
        File(PathManager.DIR_FILES_PRIVATE, "ai").apply { mkdirs() }
            .let { File(it, FILE_NAME) }
    }.getOrNull()

    /**
     * 记录一次启动。应在真正拉起游戏前/后调用；内部不会抛出异常。
     */
    fun recordStart(version: Version, logFile: File) {
        synchronized(lock) {
            runCatching {
                val records = loadLocked()
                val record = LaunchRecord(
                    instanceName = version.getVersionName(),
                    version = version.getVersionInfo()?.getInfoString()
                        ?: version.getVersionName(),
                    timestamp = System.currentTimeMillis(),
                    logPath = logFile.absolutePath,
                    exitCode = null
                )
                records.add(record)
                while (records.size > MAX_RECORDS) {
                    records.removeAt(0)
                }
                persistLocked(records)
            }.onFailure { e ->
                Logger.warning(TAG, "Failed to record game launch.", e)
            }
        }
    }

    /**
     * 更新最近一条记录的退出码。没有记录时什么都不做；内部不会抛出异常。
     */
    fun recordExit(code: Int) {
        synchronized(lock) {
            runCatching {
                val records = loadLocked()
                val last = records.lastOrNull() ?: return@synchronized
                if (last.exitCode == code) return@synchronized
                records[records.lastIndex] = last.copy(exitCode = code)
                persistLocked(records)
            }.onFailure { e ->
                Logger.warning(TAG, "Failed to record game exit code.", e)
            }
        }
    }

    /** 最近一条启动记录 */
    fun latest(): LaunchRecord? = synchronized(lock) {
        loadLocked().lastOrNull()
    }

    /**
     * 最近的启动记录，按时间倒序（最新在前）。
     * @param limit 数量上限，小于等于 0 时返回空列表
     */
    fun recent(limit: Int): List<LaunchRecord> = synchronized(lock) {
        if (limit <= 0) return@synchronized emptyList()
        loadLocked().asReversed().take(limit)
    }

    // ------------------------------------------------------------------ internals

    private fun loadLocked(): MutableList<LaunchRecord> {
        cache?.let { return it }
        val file = recordFile()
        val loaded = if (file != null && file.isFile) {
            runCatching {
                gson.fromJson<List<LaunchRecord>>(file.readText(), listType)
                    ?.filter { it.instanceName.isNotBlank() && it.timestamp > 0 }
                    ?.toMutableList()
            }.onFailure { e ->
                Logger.warning(TAG, "Launch records are corrupted, starting with an empty list.", e)
            }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val result = loaded.toMutableList()
        if (result.size > MAX_RECORDS) {
            while (result.size > MAX_RECORDS) result.removeAt(0)
        }
        cache = result
        return result
    }

    private fun persistLocked(records: MutableList<LaunchRecord>) {
        cache = records
        val file = recordFile() ?: return
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return

        val temp = File(parent, "$FILE_NAME.tmp")
        temp.writeText(gson.toJson(records))
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }
}
