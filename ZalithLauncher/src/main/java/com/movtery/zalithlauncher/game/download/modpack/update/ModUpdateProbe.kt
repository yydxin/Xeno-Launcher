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

import com.movtery.zalithlauncher.game.download.assets.platform.PlatformReleaseType
import com.movtery.zalithlauncher.game.download.assets.platform.getModrinthVersBySha1
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredModrinthSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredPlatformSearcher
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.ModrinthVersion
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.computeModFingerprints
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ModUpdateProbe"

/**
 * 模组 / 光影 / 资源包的更新探测。
 *
 * 只做 Modrinth 指纹反查：按本地文件 SHA-1 匹配项目版本，再查询同游戏版本的最新发布渠道版本。
 * 没有匹配到指纹的文件（CurseForge 来源、本地修改过、非 Modrinth 项目）一律标记为“无法确认”，
 * 绝不按文件名猜测。为了控制耗时，每次最多读取 [MAX_FILES] 个文件、[MAX_BYTES] 字节、
 * 最多查询 [MAX_PROJECTS] 个项目；覆盖范围会写进返回的说明。
 */
internal object ModUpdateProbe {
    private const val MAX_FILES = 80
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_PROJECTS = 20
    private const val FINGERPRINT_BATCH_SIZE = 25
    private const val HASH_PARALLELISM = 4

    data class Result(val note: String)

    private data class CandidateDir(
        val folderName: String,
        val extensions: Set<String>,
        val filterLoader: Boolean
    )

    private data class CandidateFile(
        val file: File,
        val relativePath: String,
        val filterLoader: Boolean
    )

    private val CANDIDATE_DIRS = listOf(
        CandidateDir("mods", setOf("jar", "zip", "litemod"), filterLoader = true),
        CandidateDir("shaderpacks", setOf("zip"), filterLoader = false),
        CandidateDir("resourcepacks", setOf("zip"), filterLoader = false)
    )

    suspend fun probe(
        version: Version,
        gameVersion: String?,
        loaderKey: String?
    ): Result = withContext(Dispatchers.IO) {
        val instanceDir = version.getGameDir()
        if (!instanceDir.isDirectory) {
            return@withContext Result("附属文件：实例目录不存在，无法检查")
        }

        var skippedForLimit = 0
        var selectedBytes = 0L
        val selected = mutableListOf<CandidateFile>()

        for (candidateDir in CANDIDATE_DIRS) {
            val dir = File(instanceDir, candidateDir.folderName)
            if (!dir.isDirectory) continue
            val files = dir.listFiles()
                ?.filter { it.isFile && it.isCandidate(candidateDir.extensions) }
                ?.sortedBy { it.length() }
                .orEmpty()
            for (file in files) {
                val size = file.length().coerceAtLeast(0L)
                if (selected.size >= MAX_FILES || selectedBytes + size > MAX_BYTES) {
                    skippedForLimit++
                    continue
                }
                selectedBytes += size
                selected += CandidateFile(
                    file = file,
                    relativePath = file.relativeTo(instanceDir).invariantSeparatorsPath,
                    filterLoader = candidateDir.filterLoader
                )
            }
        }

        if (selected.isEmpty()) {
            return@withContext Result("附属文件：未发现可检查的模组/光影/资源包文件")
        }

        val semaphore = Semaphore(HASH_PARALLELISM)
        val hashFailures = AtomicInteger(0)
        val fingerprints = coroutineScope {
            selected.map { candidate ->
                async {
                    semaphore.withPermit {
                        try {
                            candidate to computeModFingerprints(candidate.file).sha1
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            hashFailures.incrementAndGet()
                            Logger.warning(TAG, "计算模组指纹失败：${candidate.relativePath}", e)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val matched = mutableMapOf<String, ModrinthVersion>()
        var matchFailed = false
        fingerprints.map { it.second }.distinct().chunked(FINGERPRINT_BATCH_SIZE).forEach { chunk ->
            try {
                matched.putAll(getModrinthVersBySha1(chunk))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                matchFailed = true
                Logger.warning(TAG, "Modrinth 指纹批量查询失败", e)
            }
        }

        val projectGroups = LinkedHashMap<String, Pair<ModrinthVersion, String>>()
        var matchedFiles = 0
        fingerprints.forEach { (candidate, sha1) ->
            val installed = matched[sha1] ?: return@forEach
            matchedFiles++
            projectGroups.putIfAbsent(installed.projectId, installed to candidate.relativePath)
        }

        val updates = mutableListOf<String>()
        var failedProjects = 0
        var checkedProjects = 0
        if (gameVersion != null) {
            for ((projectId, group) in projectGroups) {
                if (checkedProjects >= MAX_PROJECTS) break
                checkedProjects++
                val (installed, samplePath) = group
                val latest = try {
                    latestVersion(
                        projectId = projectId,
                        gameVersion = gameVersion,
                        loaderKey = loaderKey,
                        filterLoader = selected.firstOrNull { it.relativePath == samplePath }?.filterLoader == true,
                        installedType = installed.versionType
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failedProjects++
                    Logger.warning(TAG, "检查 Modrinth 项目最新版本失败：$projectId", e)
                    null
                }
                if (latest != null && latest.id != installed.id) {
                    updates += "$samplePath（${installed.versionNumber} → ${latest.versionNumber}）"
                }
            }
        }

        val note = buildString {
            append("附属文件：Modrinth 反查 ")
            append(matchedFiles)
            append("/")
            append(fingerprints.size)
            append(" 个")
            if (selected.size != fingerprints.size) {
                append("（选取 ")
                append(selected.size)
                append(" 个，")
                append(selected.size - fingerprints.size)
                append(" 个读取失败）")
            }
            if (gameVersion == null) {
                append("；无法确定游戏版本，未检查最新版本")
            } else if (updates.isNotEmpty()) {
                append("；")
                append(updates.size)
                append(" 个有更新：")
                append(updates.take(5).joinToString("，"))
                if (updates.size > 5) append("…")
            } else {
                append("；未发现可确认的更新")
            }
            append("；其余文件无法确认（仅覆盖 Modrinth 指纹可匹配的文件，不按文件名猜测）")
            if (projectGroups.size > checkedProjects) {
                append("；仅检查前 ")
                append(checkedProjects)
                append(" 个项目")
            }
            if (failedProjects > 0) {
                append("；")
                append(failedProjects)
                append(" 个项目检查失败")
            }
            if (matchFailed) append("；部分指纹查询失败")
            if (hashFailures.get() > 0) {
                append("；")
                append(hashFailures.get())
                append(" 个文件指纹读取失败")
            }
            if (skippedForLimit > 0) {
                append("；另有 ")
                append(skippedForLimit)
                append(" 个文件超出本次检查上限")
            }
        }
        Result(note)
    }

    private suspend fun latestVersion(
        projectId: String,
        gameVersion: String,
        loaderKey: String?,
        filterLoader: Boolean,
        installedType: PlatformReleaseType
    ): ModrinthVersion? {
        val versions = mirroredPlatformSearcher(
            searchers = mirroredModrinthSource(),
            printLog = false
        ) { searcher ->
            searcher.getVersionsChunk(projectId)
        }
        return versions.filter { version ->
            version.gameVersions.contains(gameVersion) &&
                    (!filterLoader || loaderKey == null ||
                            version.loaders.any { it.equals(loaderKey, ignoreCase = true) }) &&
                    releaseAllowed(installedType, version.versionType)
        }.maxByOrNull { version ->
            runCatching { version.platformDatePublished() }.getOrDefault(Instant.EPOCH)
        }
    }

    private fun releaseAllowed(
        installed: PlatformReleaseType,
        candidate: PlatformReleaseType
    ): Boolean {
        if (installed == PlatformReleaseType.RELEASE) {
            return candidate == PlatformReleaseType.RELEASE
        }
        return candidate.ordinal <= installed.ordinal
    }

    private fun File.isCandidate(extensions: Set<String>): Boolean {
        if (name.endsWith(".disabled", ignoreCase = true)) return false
        return extension.lowercase() in extensions
    }
}
