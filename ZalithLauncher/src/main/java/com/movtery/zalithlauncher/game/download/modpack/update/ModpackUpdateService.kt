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

import android.os.StatFs
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.download.assets.platform.mcim.mapMCIMMirrorUrls
import com.movtery.zalithlauncher.game.download.engine.BatchDownloader
import com.movtery.zalithlauncher.game.download.engine.DownloadRequest
import com.movtery.zalithlauncher.game.download.engine.Fetcher
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPaths
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPlatform
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ModpackUpdateService"

/** 一次更新报告，写入 `.backup/<时间戳>/report.json` */
internal data class ModpackUpdateReport(
    val success: Boolean = false,
    val cancelled: Boolean = false,
    val message: String = "",
    val platform: String? = null,
    val fromVersionId: String? = null,
    val fromVersionName: String? = null,
    val toVersionId: String? = null,
    val toVersionName: String? = null,
    val archiveBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val added: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val conflicts: List<ReportConflict> = emptyList(),
    val keptUserConfig: List<String> = emptyList(),
    val skippedProtected: List<String> = emptyList(),
    val userFilesKept: Int = 0,
    val loaderUpdated: Boolean = false,
    val loaderNote: String? = null,
    val error: String? = null
)

internal data class ReportConflict(
    val path: String,
    val kind: String,
    val newPath: String
)

/**
 * 整合包更新服务（供 UI 与 AI 工具调用）。
 *
 * 设计约束：
 * - 文件写入统一经过 [ModpackPaths.isProtectedPath] 与规范化路径校验；
 * - 只删除旧清单 fileScope / 本地旧清单中明确属于整合包、且未受保护的文件；
 * - 旧清单之外的用户文件一律保留，并统计为 `userFilesKept`；
 * - config/ 下用户修改过的文件（旧清单有哈希且与本地不一致）保留用户版本，新版本写入 `<文件>.new`；
 * - 同一实例同时只允许一个写入任务（`plan` 只读，不占用写锁）。
 *
 * `onProgress` 回调运行在后台线程，调用方（UI）需要自行切换到主线程；
 * 回调抛出的 [CancellationException] 会触发协作式取消：服务回滚后返回失败结果。
 */
object ModpackUpdateService {
    data class LoaderUpdate(
        val loader: String,
        val currentVersion: String,
        val latestVersion: String
    )

    data class UpdateInfo(
        val instanceName: String,
        val platform: String,
        val currentVersionName: String,
        val latestVersionName: String,
        val currentVersionId: String,
        val latestVersionId: String,
        val hasUpdate: Boolean,
        val downloadUrl: String,
        val loaderUpdate: LoaderUpdate?,
        val reason: String = ""
    )

    data class ChangePlan(
        val added: List<String>,
        val changed: List<String>,
        val removed: List<String>,
        val downloadBytes: Long,
        val skippedProtected: List<String>,
        val userFilesKept: Int
    )

    data class UpdateResult(
        val ok: Boolean,
        val message: String,
        val backupDir: String = "",
        val changedFiles: Int = 0
    )

    /**
     * UI 用的实例状态（不访问网络）。
     */
    data class InstanceStatus(
        val isModpack: Boolean,
        val recognized: Boolean,
        val legacyRecord: Boolean,
        val platform: String,
        val hasOnlineSource: Boolean,
        val updateBlocked: Boolean,
        val currentVersionName: String,
        val displayName: String,
        val latestBackupDir: String,
        val backupRoot: String,
        val reason: String
    )

    private const val SPACE_MARGIN = 32L * 1024 * 1024
    private const val MAX_DOWNLOAD_CONNECTIONS = 4
    private const val DOWNLOAD_RETRY_ROUNDS = 2

    private val ONLINE_PLATFORMS = setOf(ModpackPlatform.MODRINTH, ModpackPlatform.CURSEFORGE)
    private val ONLINE_LOADERS = setOf(
        ModLoader.FABRIC,
        ModLoader.QUILT,
        ModLoader.FORGE,
        ModLoader.NEOFORGE
    )
    private val instanceLocks = ConcurrentHashMap<String, Mutex>()

    private data class SourceState(
        val versionDir: File,
        val instanceDir: File,
        val metaDir: File,
        val meta: ModpackMeta?,
        val legacy: Boolean,
        val legacyFileExists: Boolean,
        val hint: LocalPackHint?,
        val recordError: String?,
        val hasBackup: Boolean
    ) {
        val isModpack: Boolean
            get() = meta != null || hint != null || legacyFileExists || hasBackup
    }

    private data class Prepared(
        val staged: StagedPack,
        val changes: ChangeSet,
        val archiveBytes: Long
    )

    // ---------------- 对外 API ----------------

    suspend fun inspect(version: Version): InstanceStatus = withContext(Dispatchers.IO) {
        val state = sourceState(version)
        val platform = state.meta?.platformType()
        val isolationReason = isolationBlockReason(version, state)
        val recognized = state.meta != null && platform != null
        val hasOnline = recognized && platform in ONLINE_PLATFORMS
        val updateBlocked = isolationReason != null ||
                (state.meta != null && platform == null) ||
                (state.recordError != null && state.meta == null)

        val reasons = mutableListOf<String>()
        when {
            !state.isModpack -> reasons += "未找到整合包来源记录，无法确认来源，不参与更新"
            state.recordError != null && state.meta == null -> reasons += state.recordError
            state.meta == null -> {
                val hint = state.hint
                reasons += "无法确认来源，不参与更新：本地清单仅用于展示名称/版本，未记录 projectId/versionId"
                if (hint != null) {
                    reasons += "本地 ${hint.kind} 清单：${hint.name}${hint.version.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
                }
            }
            platform == null -> reasons += "来源记录中的平台无效：${state.meta.platform}"
            isolationReason != null -> reasons += isolationReason
            platform == ModpackPlatform.MULTIMC || platform == ModpackPlatform.MCBBS ->
                reasons += "${platform.displayName} 没有在线更新来源"
        }
        if (state.legacy && state.meta != null) {
            reasons += "来源记录为旧版 ${ModpackMeta.LEGACY_FILE_NAME}（已兼容识别）"
        }

        InstanceStatus(
            isModpack = state.isModpack,
            recognized = recognized,
            legacyRecord = state.legacy,
            platform = platform?.displayName ?: "",
            hasOnlineSource = hasOnline && !updateBlocked,
            updateBlocked = updateBlocked,
            currentVersionName = state.meta?.versionName.orEmpty().ifBlank {
                state.hint?.version.orEmpty()
            },
            displayName = state.hint?.name ?: version.getVersionName(),
            latestBackupDir = ModpackUpdater.latestBackupDir(state.instanceDir)?.absolutePath.orEmpty(),
            backupRoot = ModpackUpdater.backupRoot(state.instanceDir).absolutePath,
            reason = reasons.joinToString("\n")
        )
    }

    suspend fun checkAll(): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val versions = installedVersions()
        if (versions.isEmpty()) return@withContext emptyList()

        val semaphore = Semaphore(2)
        coroutineScope {
            versions.map { version ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val status = runCatching { inspect(version) }.getOrNull()
                            ?: return@withPermit null
                        if (!status.isModpack) return@withPermit null
                        try {
                            check(version)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            UpdateInfo(
                                instanceName = version.getVersionName(),
                                platform = status.platform,
                                currentVersionName = status.currentVersionName,
                                latestVersionName = "",
                                currentVersionId = "",
                                latestVersionId = "",
                                hasUpdate = false,
                                downloadUrl = "",
                                loaderUpdate = null,
                                reason = "检查失败：${readableMessage(e)}"
                            )
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }.sortedBy { it.instanceName }
    }

    suspend fun check(version: Version): UpdateInfo = withContext(Dispatchers.IO) {
        val state = sourceState(version)
        val instanceName = version.getVersionName()
        val meta = state.meta
        if (meta == null) {
            val reason = buildString {
                append(state.recordError ?: "未找到整合包来源记录，无法确认来源，不参与更新")
                state.hint?.let { hint ->
                    append("；本地 ${hint.kind} 清单显示名称「${hint.name}」")
                    if (hint.version.isNotBlank()) append("版本「${hint.version}」")
                    append("，仅用于展示，未记录 projectId/versionId")
                }
            }
            return@withContext UpdateInfo(
                instanceName = instanceName,
                platform = "",
                currentVersionName = state.hint?.version.orEmpty(),
                latestVersionName = "",
                currentVersionId = "",
                latestVersionId = "",
                hasUpdate = false,
                downloadUrl = "",
                loaderUpdate = null,
                reason = reason
            )
        }

        val platform = meta.platformType()
        if (platform == null) {
            return@withContext UpdateInfo(
                instanceName = instanceName,
                platform = "",
                currentVersionName = meta.versionName,
                latestVersionName = "",
                currentVersionId = meta.versionId.orEmpty(),
                latestVersionId = "",
                hasUpdate = false,
                downloadUrl = "",
                loaderUpdate = null,
                reason = "来源记录中的平台无效：${meta.platform}，无法确认来源，不参与更新"
            )
        }

        val isolationReason = isolationBlockReason(version, state)
        if (isolationReason != null) {
            return@withContext UpdateInfo(
                instanceName = instanceName,
                platform = platform.displayName,
                currentVersionName = meta.versionName,
                latestVersionName = "",
                currentVersionId = meta.versionId.orEmpty(),
                latestVersionId = "",
                hasUpdate = false,
                downloadUrl = "",
                loaderUpdate = null,
                reason = isolationReason
            )
        }

        val gameVersion = gameVersionFor(version, meta)
        val loaderKey = loaderKeyFor(version, meta)
        val reasons = mutableListOf<String>()
        var latestVersionName = ""
        var latestVersionId = ""
        var downloadUrl = ""
        var hasUpdate = false

        if (platform in ONLINE_PLATFORMS && !gameVersion.isNullOrBlank()) {
            val remote = PackUpdateSource.resolveLatest(meta, gameVersion, loaderKey)
            latestVersionName = remote.versionName
            latestVersionId = remote.versionId
            downloadUrl = remote.downloadUrl
            hasUpdate = if (!meta.versionId.isNullOrBlank()) {
                !remote.versionId.equals(meta.versionId, ignoreCase = true)
            } else {
                remote.versionName.isNotBlank() &&
                        !remote.versionName.equals(meta.versionName, ignoreCase = true)
            }
            if (!hasUpdate) reasons += "整合包已是最新版本"
        } else if (platform in ONLINE_PLATFORMS) {
            reasons += "无法确定 Minecraft 版本，无法检查整合包更新"
        } else {
            reasons += "${platform.displayName} 没有在线更新来源，无法检查整合包更新"
        }

        val loaderResult = try {
            LoaderUpdateChecker.check(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.warning(TAG, "加载器更新检查失败", e)
            reasons += "加载器更新检查失败：${readableMessage(e)}"
            null
        }
        val loaderUpdate = loaderResult?.update
        if (loaderUpdate != null) {
            reasons += "加载器 ${loaderUpdate.loader} 可更新：${loaderUpdate.currentVersion} → ${loaderUpdate.latestVersion}"
        }
        loaderResult?.note?.takeIf { it.isNotBlank() }?.let { reasons += it }

        if (platform in ONLINE_PLATFORMS) {
            val probe = try {
                ModUpdateProbe.probe(version, gameVersion, loaderKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warning(TAG, "附属文件更新检查失败", e)
                reasons += "附属文件检查失败：${readableMessage(e)}"
                null
            }
            probe?.note?.takeIf { it.isNotBlank() }?.let { reasons += it }
        }
        if (state.legacy) {
            reasons += "来源记录为旧版 ${ModpackMeta.LEGACY_FILE_NAME}（已兼容识别）"
        }

        UpdateInfo(
            instanceName = instanceName,
            platform = platform.displayName,
            currentVersionName = meta.versionName.ifBlank { meta.versionId.orEmpty() },
            latestVersionName = latestVersionName,
            currentVersionId = meta.versionId.orEmpty(),
            latestVersionId = latestVersionId,
            hasUpdate = hasUpdate,
            downloadUrl = downloadUrl,
            loaderUpdate = loaderUpdate,
            reason = reasons.filter { it.isNotBlank() }.joinToString("\n")
        )
    }

    suspend fun plan(version: Version): ChangePlan = withContext(Dispatchers.IO) {
        val state = sourceState(version)
        val meta = requireUpdateMeta(state, version)
        val gameVersion = gameVersionFor(version, meta)
            ?: throw IllegalStateException("无法确定 Minecraft 版本，不能生成更新计划")
        val loaderKey = loaderKeyFor(version, meta)
        val remote = PackUpdateSource.resolveLatest(meta, gameVersion, loaderKey)
        val workRoot = createWorkDir("plan")
        try {
            val prepared = prepare(version, state, meta, remote, workRoot) {}
            ChangePlan(
                added = prepared.changes.added,
                changed = prepared.changes.changed,
                removed = prepared.changes.removed,
                downloadBytes = prepared.changes.downloadBytes + prepared.archiveBytes,
                skippedProtected = prepared.changes.skippedProtected,
                userFilesKept = prepared.changes.userFilesKept
            )
        } finally {
            workRoot.deleteRecursively()
        }
    }

    suspend fun apply(
        version: Version,
        onProgress: (String) -> Unit = {}
    ): UpdateResult = withContext(Dispatchers.IO) {
        val state = try {
            sourceState(version)
        } catch (e: Throwable) {
            return@withContext UpdateResult(false, "读取实例信息失败：${readableMessage(e)}")
        }

        val lock = lockFor(state.instanceDir)
        if (!lock.tryLock()) {
            return@withContext UpdateResult(false, "该实例正在执行其他写入任务，请稍后再试")
        }

        var session: BackupSession? = null
        var workRoot: File? = null
        try {
            val meta = requireUpdateMeta(state, version)
            val platform = meta.platformType()
                ?: throw IllegalStateException("来源记录中的平台无效：${meta.platform}")
            val gameVersion = gameVersionFor(version, meta)
                ?: throw IllegalStateException("无法确定 Minecraft 版本，不能执行更新")
            val loaderKey = loaderKeyFor(version, meta)

            onProgress("正在获取新版整合包信息")
            val remote = PackUpdateSource.resolveLatest(meta, gameVersion, loaderKey)

            workRoot = createWorkDir("apply")
            val prepared = prepare(version, state, meta, remote, workRoot, onProgress)
            val changes = prepared.changes
            val staged = prepared.staged

            val loaderCheck = try {
                LoaderUpdateChecker.check(version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warning(TAG, "加载器更新检查失败，本次只更新整合包文件。", e)
                LoaderUpdateChecker.CheckResult(
                    update = null,
                    note = "加载器更新检查失败：${readableMessage(e)}"
                )
            }
            val loaderUpdate = loaderCheck.update
            val loaderNotes = mutableListOf<String>()
            loaderCheck.note.takeIf { it.isNotBlank() }?.let { loaderNotes += it }
            if (loaderUpdate != null) {
                loaderNotes += "加载器 ${loaderUpdate.loader} 可更新：" +
                        "${loaderUpdate.currentVersion} → ${loaderUpdate.latestVersion}"
            } else if (loaderNotes.isEmpty()) {
                loaderNotes += "加载器已是最新版本"
            }

            if (changes.isEmpty && loaderUpdate == null) {
                return@withContext UpdateResult(
                    ok = false,
                    message = "当前整合包没有需要应用的文件变更，且没有可更新的加载器"
                )
            }

            onProgress("正在检查存储空间")
            val backupTargets = (changes.writes.map { it.destPath } + changes.removed).distinct()
            val sourceFile = ModpackMeta.metaFile(state.metaDir)
            val backupBytes = backupTargets.sumOf { path ->
                ModpackPaths.resolve(state.instanceDir, path)
                    ?.takeIf { it.isFile }
                    ?.length()
                    ?: 0L
            } + sourceFile.length()
            ensureEnoughSpace(
                instanceDir = state.instanceDir,
                downloadBytes = changes.downloadBytes + prepared.archiveBytes,
                backupBytes = backupBytes
            )

            onProgress("正在备份将被修改或删除的文件")
            session = ModpackUpdater.createBackup(
                instanceDir = state.instanceDir,
                targets = backupTargets,
                sourceFile = sourceFile,
                historyBase = BackupHistory(
                    platform = meta.platform,
                    fromVersionId = meta.versionId,
                    fromVersionName = meta.versionName,
                    toVersionId = remote.versionId,
                    toVersionName = remote.versionName
                ),
                onProgress = onProgress
            )
            val backupDir = session.dir

            val downloadOps = changes.writes.filter { it.entry.urls.isNotEmpty() }
            val downloaded = if (downloadOps.isEmpty()) {
                emptyMap()
            } else {
                onProgress("正在下载有变化的文件（${downloadOps.size} 个）")
                downloadAll(downloadOps, workRoot, onProgress)
            }

            onProgress("正在应用文件变更")
            applyWrites(state.instanceDir, changes.writes, downloaded, onProgress)
            deleteRemoved(state.instanceDir, changes.removed, onProgress)

            onProgress("正在更新来源记录")
            val conflictPaths = changes.conflicts.map { it.path }.toSet()
            val newScope = staged.entries.mapNotNull { ModpackPaths.normalize(it.path) }
                .filterNot {
                    ModpackPaths.isProtectedPath(it) ||
                            ModpackPaths.isMetadataPath(it) ||
                            it in conflictPaths
                }
                .distinct()
                .sorted()
            val newMeta = meta.copy(
                versionId = remote.versionId,
                versionName = remote.versionName.ifBlank { meta.versionName },
                installedAt = Instant.now().toString(),
                gameVersion = staged.gameVersion ?: meta.gameVersion,
                loader = staged.loader ?: meta.loader,
                fileScope = newScope
            )
            ModpackMeta.save(state.metaDir, newMeta)

            var loaderInstalled = false
            var loaderResultNote: String? = null
            if (loaderUpdate != null) {
                onProgress("正在更新加载器 ${loaderUpdate.loader}")
                val loaderResult = LoaderUpdateChecker.install(version, loaderUpdate, onProgress)
                loaderInstalled = loaderResult.installed
                loaderResultNote = loaderResult.note
            }

            onProgress("正在写入更新报告")
            val message = buildSuccessMessage(
                remote = remote,
                changes = changes,
                loaderInstalled = loaderInstalled,
                loaderResultNote = loaderResultNote,
                loaderNotes = loaderNotes
            )
            val report = ModpackUpdateReport(
                success = true,
                message = message,
                platform = meta.platform,
                fromVersionId = meta.versionId,
                fromVersionName = meta.versionName,
                toVersionId = remote.versionId,
                toVersionName = remote.versionName,
                archiveBytes = prepared.archiveBytes,
                downloadBytes = changes.downloadBytes,
                added = changes.added,
                changed = changes.changed,
                removed = changes.removed,
                conflicts = changes.conflicts.map {
                    ReportConflict(it.path, it.kind.name, it.newPath)
                },
                keptUserConfig = changes.keptUserConfig,
                skippedProtected = changes.skippedProtected,
                userFilesKept = changes.userFilesKept,
                loaderUpdated = loaderInstalled,
                loaderNote = loaderResultNote ?: loaderNotes.joinToString("；"),
                error = null
            )
            runCatching { ModpackUpdater.writeReport(backupDir, report) }
                .onFailure { error -> Logger.warning(TAG, "写入更新报告失败", error) }

            UpdateResult(
                ok = true,
                message = message,
                backupDir = backupDir.absolutePath,
                changedFiles = changes.operationCount
            )
        } catch (e: CancellationException) {
            val outcome = restoreQuietly(state, session)
            val message = buildCancelledMessage(outcome)
            session?.let { activeSession ->
                withContext(NonCancellable) {
                    runCatching {
                        ModpackUpdater.writeReport(
                            activeSession.dir,
                            failureReport(cancelled = true, message = message, error = readableMessage(e))
                        )
                    }
                }
            }
            Logger.info(TAG, "整合包更新已取消：$message")
            if (!currentCoroutineContext().isActive) throw e
            UpdateResult(
                ok = false,
                message = message,
                backupDir = session?.dir?.absolutePath.orEmpty(),
                changedFiles = 0
            )
        } catch (e: Throwable) {
            val outcome = restoreQuietly(state, session)
            val message = buildFailureMessage(e, outcome)
            session?.let { activeSession ->
                withContext(NonCancellable) {
                    runCatching {
                        ModpackUpdater.writeReport(
                            activeSession.dir,
                            failureReport(cancelled = false, message = message, error = readableMessage(e))
                        )
                    }
                }
            }
            Logger.error(TAG, "整合包更新失败并已尝试回滚：$message", e)
            UpdateResult(
                ok = false,
                message = message,
                backupDir = session?.dir?.absolutePath.orEmpty(),
                changedFiles = 0
            )
        } finally {
            workRoot?.deleteRecursively()
            lock.unlock()
        }
    }

    suspend fun rollback(version: Version): UpdateResult = withContext(Dispatchers.IO) {
        val state = try {
            sourceState(version)
        } catch (e: Throwable) {
            return@withContext UpdateResult(false, "读取实例信息失败：${readableMessage(e)}")
        }
        val lock = lockFor(state.instanceDir)
        if (!lock.tryLock()) {
            return@withContext UpdateResult(false, "该实例正在执行其他写入任务，请稍后再试")
        }
        try {
            val backupDir = ModpackUpdater.latestBackupDir(state.instanceDir)
                ?: return@withContext UpdateResult(false, "没有可用的更新备份")
            val outcome = ModpackUpdater.restore(
                instanceDir = state.instanceDir,
                sourceDir = state.metaDir,
                backupDir = backupDir
            )
            val loaderUpdated = readLoaderUpdated(backupDir)
            val message = buildString {
                if (outcome.errors.isEmpty()) {
                    append("已回滚到更新前状态")
                } else {
                    append("回滚未完全成功")
                }
                append("：还原 ")
                append(outcome.restored)
                append(" 个文件，删除 ")
                append(outcome.deleted)
                append(" 个更新新增的文件；来源记录已恢复")
                if (loaderUpdated) {
                    append("。注意：加载器更新不在回滚范围内")
                }
                if (outcome.errors.isNotEmpty()) {
                    append("。问题：")
                    append(outcome.errors.take(3).joinToString("；"))
                }
            }
            UpdateResult(
                ok = outcome.errors.isEmpty(),
                message = message,
                backupDir = backupDir.absolutePath,
                changedFiles = outcome.restored + outcome.deleted
            )
        } catch (e: Throwable) {
            Logger.error(TAG, "回滚失败", e)
            UpdateResult(false, "回滚失败：${readableMessage(e)}")
        } finally {
            lock.unlock()
        }
    }

    // ---------------- 内部流程 ----------------

    private suspend fun prepare(
        version: Version,
        state: SourceState,
        meta: ModpackMeta,
        remote: PackUpdateSource.RemotePack,
        workRoot: File,
        onProgress: (String) -> Unit
    ): Prepared {
        onProgress("正在解析新版整合包清单")
        val archive = ArchiveCache.ensure(remote)
        val staged = PackSnapshot.stage(
            archive = archive,
            workDir = workRoot,
            expectedPlatform = meta.platformType()
                ?: throw IllegalStateException("来源记录中的平台无效：${meta.platform}"),
            onProgress = onProgress
        )
        validateCompatibility(version, staged, meta)

        onProgress("正在比较本地文件与新清单")
        val baseline = PackSnapshot.localBaseline(state.instanceDir, meta)
        val changes = PackSnapshot.diff(
            instanceDir = state.instanceDir,
            baseline = baseline,
            staged = staged,
            onProgress = onProgress
        )
        return Prepared(staged = staged, changes = changes, archiveBytes = archive.length())
    }

    private fun validateCompatibility(
        version: Version,
        staged: StagedPack,
        meta: ModpackMeta
    ) {
        val currentGameVersion = gameVersionFor(version, meta)
        val newGameVersion = staged.gameVersion
        if (!newGameVersion.isNullOrBlank() &&
            !currentGameVersion.isNullOrBlank() &&
            newGameVersion != currentGameVersion
        ) {
            throw IllegalStateException(
                "新版整合包要求 Minecraft $newGameVersion，当前实例是 $currentGameVersion；" +
                        "本工具只做同版本增量更新，请安装新的整合包实例"
            )
        }

        val info = version.getVersionInfo() ?: return
        val currentLoader = info.loaderInfos
            .firstOrNull { it.loader in ONLINE_LOADERS }
            ?.loader
        val newLoader = staged.loader?.let { name ->
            ModLoader.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        } ?: return

        if (newLoader !in ONLINE_LOADERS) return
        if (currentLoader == null) {
            throw IllegalStateException(
                "新版整合包需要 ${newLoader.displayName}，但当前实例没有可识别的模组加载器；" +
                        "请安装新的整合包实例"
            )
        }
        if (currentLoader != newLoader) {
            throw IllegalStateException(
                "新版整合包将加载器从 ${currentLoader.displayName} 更换为 ${newLoader.displayName}；" +
                        "本工具不自动更换加载器，请安装新的整合包实例"
            )
        }
    }

    private suspend fun downloadAll(
        writes: List<WriteOp>,
        workRoot: File,
        onProgress: (String) -> Unit
    ): Map<String, File> {
        val downloadDir = File(workRoot, "downloads").apply { mkdirs() }
        val requests = writes.map { op ->
            val target = File(downloadDir, op.path)
            DownloadRequest(
                urls = op.entry.urls.flatMap { it.mapMCIMMirrorUrls() },
                targetFile = target,
                sha1 = op.entry.sha1,
                expectedSize = op.entry.size.takeIf { it > 0 } ?: -1L
            )
        }
        val downloader = BatchDownloader(
            requests = requests,
            maxConnections = MAX_DOWNLOAD_CONNECTIONS,
            retryRounds = DOWNLOAD_RETRY_ROUNDS
        )
        downloader.onUpdate = { progress ->
            onProgress(
                "正在下载有变化的文件：${progress.downloadedFiles}/${progress.totalFiles}" +
                        "（${formatFileSize(progress.downloadedBytes)}）"
            )
        }
        try {
            downloader.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("下载更新文件失败：${readableMessage(e)}", e)
        }

        val result = writes.associate { op -> op.path to File(downloadDir, op.path) }
        result.forEach { (path, file) ->
            if (!file.isFile) {
                throw IllegalStateException("下载文件缺失：$path")
            }
        }
        return result
    }

    private fun applyWrites(
        instanceDir: File,
        writes: List<WriteOp>,
        downloaded: Map<String, File>,
        onProgress: (String) -> Unit
    ) {
        writes.forEachIndexed { index, op ->
            if (ModpackPaths.isProtectedPath(op.destPath) || ModpackPaths.isMetadataPath(op.destPath)) {
                throw IllegalStateException("拒绝写入受保护路径：${op.destPath}")
            }
            val source = downloaded[op.path] ?: op.entry.source
                ?: throw IllegalStateException("缺少待写入的文件内容：${op.path}")
            if (!source.isFile) {
                throw IllegalStateException("待写入的源文件不存在：${source.absolutePath}")
            }
            val target = ModpackPaths.resolve(instanceDir, op.destPath)
                ?: throw IllegalStateException("写入路径越界：${op.destPath}")
            if (target.isDirectory) {
                throw IllegalStateException("写入目标是目录：${op.destPath}")
            }

            ModpackUpdater.copyFile(source, target)

            val expectedSha1 = op.entry.sha1
            if (expectedSha1 != null &&
                !ModpackUpdater.sha1(target).equals(expectedSha1, ignoreCase = true)
            ) {
                throw IllegalStateException("写入后 SHA-1 校验失败：${op.destPath}")
            }
            if (expectedSha1 == null && op.entry.size > 0 && target.length() != op.entry.size) {
                throw IllegalStateException("写入后文件大小校验失败：${op.destPath}")
            }

            if ((index + 1) % 50 == 0 || index + 1 == writes.size) {
                onProgress("正在应用文件变更（${index + 1}/${writes.size}）")
            }
        }
    }

    private fun deleteRemoved(
        instanceDir: File,
        removed: List<String>,
        onProgress: (String) -> Unit
    ) {
        removed.forEachIndexed { index, rawPath ->
            val path = ModpackPaths.normalize(rawPath)
                ?: throw IllegalStateException("非法的删除路径：$rawPath")
            if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                throw IllegalStateException("拒绝删除受保护路径：$path")
            }
            val target = ModpackPaths.resolve(instanceDir, path)
                ?: throw IllegalStateException("删除路径越界：$path")
            if (target.isDirectory) {
                throw IllegalStateException("拒绝删除目录：$path")
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("删除文件失败：$path")
            }
            if ((index + 1) % 100 == 0 || index + 1 == removed.size) {
                onProgress("正在清理旧版本文件（${index + 1}/${removed.size}）")
            }
        }
    }

    private fun ensureEnoughSpace(
        instanceDir: File,
        downloadBytes: Long,
        backupBytes: Long
    ) {
        val available = try {
            StatFs(instanceDir.absolutePath).availableBytes
        } catch (e: Throwable) {
            Logger.warning(TAG, "无法读取存储空间，跳过空间检查。", e)
            return
        }
        val required = downloadBytes.coerceAtLeast(0L) +
                backupBytes.coerceAtLeast(0L) +
                SPACE_MARGIN
        if (available < required) {
            throw IllegalStateException(
                "存储空间不足：需要约 ${formatFileSize(required)}" +
                        "（下载 ${formatFileSize(downloadBytes)} + 备份 ${formatFileSize(backupBytes)}" +
                        " + 安全余量 ${formatFileSize(SPACE_MARGIN)}），" +
                        "当前可用 ${formatFileSize(available)}"
            )
        }
    }

    private fun restoreQuietly(state: SourceState, session: BackupSession?): RestoreOutcome? {
        val active = session ?: return null
        return runCatching {
            ModpackUpdater.restore(
                instanceDir = state.instanceDir,
                sourceDir = state.metaDir,
                backupDir = active.dir
            )
        }.onFailure { error ->
            Logger.error(TAG, "从备份回滚失败：${active.dir.absolutePath}", error)
        }.getOrNull()
    }

    private fun requireUpdateMeta(state: SourceState, version: Version): ModpackMeta {
        if (!state.isModpack) {
            throw IllegalStateException("无法确认来源，不参与更新：未找到来源记录或本地整合包清单")
        }
        if (state.recordError != null && state.meta == null) {
            throw IllegalStateException(state.recordError)
        }
        val meta = state.meta
            ?: throw IllegalStateException("无法确认来源，不参与更新：缺少可用的来源记录")
        val platform = meta.platformType()
            ?: throw IllegalStateException("来源记录中的平台无效：${meta.platform}")
        if (platform !in ONLINE_PLATFORMS) {
            throw UnsupportedOperationException("${platform.displayName} 没有在线更新来源，无法执行更新")
        }
        isolationBlockReason(version, state)?.let { throw IllegalStateException(it) }
        return meta
    }

    private fun sourceState(version: Version): SourceState {
        val instanceDir = version.getGameDir()
        val versionDir = version.getVersionPath()
        val candidates = listOf(versionDir, instanceDir)
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        var meta: ModpackMeta? = null
        var metaDir = versionDir
        var legacy = false
        var recordError: String? = null
        for (dir in candidates) {
            val record = try {
                ModpackMeta.loadRecord(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (recordError == null) {
                    recordError = "来源记录解析失败：${readableMessage(e)}"
                }
                null
            }
            if (record != null) {
                meta = record.meta
                metaDir = dir
                legacy = record.legacy
                recordError = null
                break
            }
        }

        val hint = PackSnapshot.readLocalHint(instanceDir)
            ?: if (instanceDir.absolutePath != versionDir.absolutePath) {
                PackSnapshot.readLocalHint(versionDir)
            } else null
        val legacyFileExists = ModpackMeta.legacyMetaFile(versionDir).isFile ||
                ModpackMeta.legacyMetaFile(instanceDir).isFile
        val hasBackup = ModpackUpdater.latestBackupDir(instanceDir) != null

        return SourceState(
            versionDir = versionDir,
            instanceDir = instanceDir,
            metaDir = metaDir,
            meta = meta,
            legacy = legacy,
            legacyFileExists = legacyFileExists,
            hint = hint,
            recordError = recordError,
            hasBackup = hasBackup
        )
    }

    private fun isolationBlockReason(version: Version, state: SourceState): String? {
        val instanceRoot = runCatching { state.instanceDir.canonicalFile }.getOrNull() ?: return null
        val versionRoot = runCatching { version.getVersionPath().canonicalFile }.getOrNull()
            ?: return null
        if (instanceRoot.path == versionRoot.path) return null
        return "该实例未启用版本隔离（游戏目录为 ${state.instanceDir.absolutePath}），" +
                "更新会写入共享游戏目录，已阻止操作；请先在版本设置中启用版本隔离"
    }

    private fun gameVersionFor(version: Version, meta: ModpackMeta): String? =
        version.getVersionInfo()?.minecraftVersion?.takeIf { it.isNotBlank() }
            ?: meta.gameVersion?.takeIf { it.isNotBlank() }

    private fun loaderKeyFor(version: Version, meta: ModpackMeta): String? {
        val fromVersion = version.getVersionInfo()?.loaderInfos
            ?.firstOrNull { it.loader in ONLINE_LOADERS }
            ?.loader
            ?.let { PackUpdateSource.loaderKey(it.name) }
        return fromVersion ?: PackUpdateSource.loaderKey(meta.loader)
    }

    private fun installedVersions(): List<Version> {
        val gameHome = getGameHome()
        if (gameHome.isBlank()) return emptyList()
        val versionsDir = File(getVersionsHome(gameHome))
        return versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> VersionsManager.loadVersion(gameHome, dir.name) }
            ?.filter { it.isValid() }
            ?.sortedBy { it.getVersionName() }
            .orEmpty()
    }

    private fun lockFor(instanceDir: File): Mutex {
        val key = runCatching { instanceDir.canonicalPath }
            .getOrDefault(instanceDir.absolutePath)
        return instanceLocks.computeIfAbsent(key) { Mutex() }
    }

    private fun createWorkDir(prefix: String): File {
        val root = File(PathManager.DIR_CACHE, "modpack_update_work").apply { mkdirs() }
        if (!root.isDirectory) {
            throw IllegalStateException("无法创建临时目录：${root.absolutePath}")
        }
        val dir = File(root, "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
        if (!dir.mkdirs()) {
            throw IllegalStateException("无法创建临时目录：${dir.absolutePath}")
        }
        return dir
    }

    private fun buildSuccessMessage(
        remote: PackUpdateSource.RemotePack,
        changes: ChangeSet,
        loaderInstalled: Boolean,
        loaderResultNote: String?,
        loaderNotes: List<String>
    ): String = buildString {
        append("整合包已更新到 ")
        append(remote.versionName.ifBlank { remote.versionId })
        append("：新增 ")
        append(changes.added.size)
        append("，修改 ")
        append(changes.changed.size)
        append("，删除 ")
        append(changes.removed.size)
        if (changes.conflicts.isNotEmpty()) {
            append("；保留用户配置/用户文件 ")
            append(changes.conflicts.size)
            append(" 个（新版本写入 .new）")
        }
        if (changes.keptUserConfig.isNotEmpty()) {
            append("；保留被用户修改的旧配置 ")
            append(changes.keptUserConfig.size)
            append(" 个")
        }
        if (changes.skippedProtected.isNotEmpty()) {
            append("；跳过受保护路径 ")
            append(changes.skippedProtected.size)
            append(" 个")
        }
        append("；保留用户文件 ")
        append(changes.userFilesKept)
        append(" 个")
        if (loaderInstalled && !loaderResultNote.isNullOrBlank()) {
            append("；")
            append(loaderResultNote)
        } else if (!loaderInstalled) {
            val note = loaderResultNote?.takeIf { it.isNotBlank() }
                ?: loaderNotes.firstOrNull { it.isNotBlank() }
            if (!note.isNullOrBlank()) {
                append("；加载器未更新：")
                append(note)
            }
        }
    }

    private fun buildFailureMessage(error: Throwable, outcome: RestoreOutcome?): String = buildString {
        if (outcome == null) {
            append("更新失败：")
            append(readableMessage(error))
            append("。尚未创建备份，实例文件未被修改")
        } else {
            if (outcome.errors.isEmpty()) {
                append("更新失败，已从备份回滚：")
            } else {
                append("更新失败，回滚未完全成功：")
            }
            append(readableMessage(error))
            append("。已还原 ")
            append(outcome.restored)
            append(" 个文件，删除 ")
            append(outcome.deleted)
            append(" 个更新新增的文件")
            if (outcome.errors.isNotEmpty()) {
                append("。回滚问题：")
                append(outcome.errors.take(3).joinToString("；"))
            }
        }
    }

    private fun buildCancelledMessage(outcome: RestoreOutcome?): String = buildString {
        append("更新已取消")
        if (outcome == null) {
            append("；尚未创建备份，实例文件未被修改")
        } else {
            append("，已从备份回滚（还原 ")
            append(outcome.restored)
            append(" 个文件，删除 ")
            append(outcome.deleted)
            append(" 个更新新增的文件）")
            if (outcome.errors.isNotEmpty()) {
                append("；回滚问题：")
                append(outcome.errors.take(3).joinToString("；"))
            }
        }
    }

    private fun failureReport(
        cancelled: Boolean,
        message: String,
        error: String?
    ): ModpackUpdateReport = ModpackUpdateReport(
        success = false,
        cancelled = cancelled,
        message = message,
        error = error
    )

    private fun readLoaderUpdated(backupDir: File): Boolean = runCatching {
        val report = File(backupDir, "report.json")
        if (!report.isFile) return false
        JsonParser.parseString(report.readText())
            .asJsonObject
            .get("loaderUpdated")
            ?.takeIf { !it.isJsonNull }
            ?.asBoolean == true
    }.getOrDefault(false)

    private fun readableMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    /**
     * 新版整合包压缩包的本地缓存：`plan` 下载一次，`apply` 在缓存有效时直接复用。
     */
    private object ArchiveCache {
        private val cacheLocks = ConcurrentHashMap<String, Mutex>()
        private val cacheDir = File(PathManager.DIR_CACHE, "modpack_update")

        suspend fun ensure(remote: PackUpdateSource.RemotePack): File {
            val safeName = remote.fileName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(80)
                .ifBlank { "pack.zip" }
            val target = File(cacheDir, "${remote.platform.id}-${remote.versionId}-$safeName")
            val lock = cacheLocks.computeIfAbsent(target.absolutePath) { Mutex() }
            return lock.withLock {
                if (isValid(target, remote)) return@withLock target

                cacheDir.mkdirs()
                if (!cacheDir.isDirectory) {
                    throw IllegalStateException("无法创建更新缓存目录：${cacheDir.absolutePath}")
                }
                val temp = File(cacheDir, "${target.name}.part")
                try {
                    Fetcher.downloadFile(
                        urls = remote.downloadUrl.mapMCIMMirrorUrls(),
                        targetFile = temp,
                        sha1 = remote.downloadSha1,
                        retry = 3
                    )
                    if (!temp.isFile) {
                        throw IllegalStateException("新版整合包下载后文件缺失")
                    }
                    if (remote.downloadSha1 != null &&
                        !ModpackUpdater.sha1(temp).equals(remote.downloadSha1, ignoreCase = true)
                    ) {
                        throw IllegalStateException("新版整合包 SHA-1 校验失败")
                    }
                    if (remote.downloadSize > 0 && temp.length() != remote.downloadSize) {
                        throw IllegalStateException(
                            "新版整合包大小校验失败：期望 ${remote.downloadSize}，实际 ${temp.length()}"
                        )
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    prune(target)
                    target
                } catch (e: CancellationException) {
                    temp.delete()
                    throw e
                } catch (e: Throwable) {
                    temp.delete()
                    if (e is IllegalStateException) throw e
                    throw IllegalStateException("下载新版整合包失败：${readableMessage(e)}", e)
                }
            }
        }

        private fun isValid(file: File, remote: PackUpdateSource.RemotePack): Boolean {
            if (!file.isFile) return false
            if (remote.downloadSha1 != null) {
                return ModpackUpdater.sha1(file).equals(remote.downloadSha1, ignoreCase = true)
            }
            if (remote.downloadSize > 0) {
                return file.length() == remote.downloadSize
            }
            return true
        }

        private fun prune(keep: File) {
            val others = cacheDir.listFiles()
                ?.filter { it.isFile && it.absolutePath != keep.absolutePath }
                ?: return
            others.sortedByDescending { it.lastModified() }
                .drop(2)
                .forEach { it.delete() }
        }
    }
}
