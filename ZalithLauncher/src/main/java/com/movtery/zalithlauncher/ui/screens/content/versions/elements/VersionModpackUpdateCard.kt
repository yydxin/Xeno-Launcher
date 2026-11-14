/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.screens.content.versions.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.movtery.zalithlauncher.ui.glass.GlassMaterialAlertDialog as AlertDialog
import com.movtery.zalithlauncher.ui.glass.GlassButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.movtery.zalithlauncher.ui.glass.GlassOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import com.movtery.zalithlauncher.ui.glass.GlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.download.modpack.update.ModpackUpdateService
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.ui.screens.content.versions.layouts.VersionChunkBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else "%.1f %s".format(value, units[index])
}

/**
 * 版本总览页的「整合包更新」卡片。
 *
 * 只读取真实来源记录（.modpack_source.json / 兼容旧的 modpack_meta.json）：
 * - 没有来源记录或无法确认 projectId 的实例只显示原因，不提供更新按钮；
 * - 更新前必须先生成计划并确认，执行与回滚全部由 [ModpackUpdateService] 负责；
 * - 受保护路径（saves/ 等）由服务层硬拦截，这里不做任何文件操作。
 */
@Composable
fun VersionModpackUpdateCard(
    modifier: Modifier = Modifier,
    version: Version,
) {
    val scope = rememberCoroutineScope()
    var status by remember(version) { mutableStateOf<ModpackUpdateService.InstanceStatus?>(null) }
    var loadingStatus by remember(version) { mutableStateOf(true) }

    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<ModpackUpdateService.UpdateInfo?>(null) }
    var planning by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<ModpackUpdateService.ChangePlan?>(null) }
    var applying by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var applyJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<ModpackUpdateService.UpdateResult?>(null) }
    var rollbackResult by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    var showRollbackConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        loadingStatus = true
        status = runCatching { ModpackUpdateService.inspect(version) }.getOrNull()
        loadingStatus = false
    }

    val current = status ?: return
    if (!current.isModpack) return

    fun check() {
        if (checking) return
        checking = true
        error = null
        result = null
        info = null
        plan = null
        scope.launch {
            try {
                info = ModpackUpdateService.check(version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                checking = false
            }
        }
    }

    fun makePlan() {
        if (planning) return
        planning = true
        error = null
        scope.launch {
            try {
                plan = ModpackUpdateService.plan(version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                planning = false
            }
        }
    }

    fun apply() {
        if (applying) return
        applying = true
        error = null
        result = null
        progressText = ""
        applyJob = scope.launch {
            try {
                result = ModpackUpdateService.apply(version) { message -> progressText = message }
                status = runCatching { ModpackUpdateService.inspect(version) }.getOrNull()
                info = null
                plan = null
            } catch (e: CancellationException) {
                progressText = "已取消"
            } catch (e: Throwable) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                applying = false
                applyJob = null
            }
        }
    }

    fun rollback() {
        error = null
        scope.launch {
            try {
                val r = ModpackUpdateService.rollback(version)
                rollbackResult = if (r.ok) {
                    "回滚完成：${r.message}"
                } else {
                    "回滚失败：${r.message}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e::class.java.simpleName
            }
        }
    }

    VersionChunkBackground(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.xzlc_modpack_section_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (current.platform.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.xzlc_modpack_platform, current.platform),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = current.displayName,
                style = MaterialTheme.typography.bodyMedium
            )
            if (current.currentVersionName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.xzlc_modpack_current, current.currentVersionName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (loadingStatus) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }

            if (!current.recognized) {
                Text(
                    text = current.reason.ifBlank { stringResource(R.string.xzlc_modpack_unconfirmed) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (current.latestBackupDir.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.xzlc_modpack_backup_path, current.latestBackupDir),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { showRollbackConfirm = true }) {
                        Text(text = stringResource(R.string.xzlc_modpack_rollback))
                    }
                }
                return@Column
            }

            if (!current.hasOnlineSource) {
                Text(
                    text = current.reason.ifBlank { stringResource(R.string.xzlc_modpack_unconfirmed) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (current.hasOnlineSource) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { check() }, enabled = !checking && !applying) {
                        Text(
                            text = if (checking) stringResource(R.string.xzlc_modpack_checking)
                            else stringResource(R.string.xzlc_modpack_check)
                        )
                    }
                    if (current.latestBackupDir.isNotBlank()) {
                        OutlinedButton(
                            onClick = { showRollbackConfirm = true },
                            enabled = !applying
                        ) {
                            Text(text = stringResource(R.string.xzlc_modpack_rollback))
                        }
                    }
                }
            }

            info?.let { update ->
                if (update.hasUpdate) {
                    Text(
                        text = stringResource(
                            R.string.xzlc_modpack_available,
                            update.currentVersionName.ifBlank { "?" },
                            update.latestVersionName.ifBlank { "?" }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    update.loaderUpdate?.let { loader ->
                        Text(
                            text = stringResource(
                                R.string.xzlc_modpack_loader_update,
                                loader.loader,
                                loader.currentVersion,
                                loader.latestVersion
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { makePlan() },
                        enabled = !planning && !applying
                    ) {
                        Text(
                            text = if (planning) stringResource(R.string.xzlc_modpack_planning)
                            else stringResource(R.string.xzlc_modpack_plan)
                        )
                    }
                } else {
                    Text(
                        text = update.reason.ifBlank { stringResource(R.string.xzlc_modpack_up_to_date) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (applying) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = progressText.ifBlank { stringResource(R.string.xzlc_modpack_applying) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { applyJob?.cancel() }) {
                    Text(text = stringResource(R.string.xzlc_modpack_cancel))
                }
            }

            error?.let {
                Text(
                    text = stringResource(R.string.xzlc_modpack_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            result?.let {
                Text(
                    text = stringResource(R.string.xzlc_modpack_result, it.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (it.backupDir.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.xzlc_modpack_backup_path, it.backupDir),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            rollbackResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (current.reason.isNotBlank() && current.recognized) {
                Text(
                    text = current.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (plan != null) {
        val changePlan = plan!!
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text(text = stringResource(R.string.xzlc_modpack_plan_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.xzlc_modpack_plan_summary,
                        changePlan.added.size,
                        changePlan.changed.size,
                        changePlan.removed.size,
                        formatBytes(changePlan.downloadBytes),
                        changePlan.userFilesKept,
                        changePlan.skippedProtected.size
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    plan = null
                    showApplyConfirm = true
                }) {
                    Text(text = stringResource(R.string.xzlc_modpack_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { plan = null }) {
                    Text(text = stringResource(R.string.xzlc_modpack_cancel))
                }
            }
        )
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text(text = stringResource(R.string.xzlc_modpack_apply_confirm_title)) },
            text = { Text(text = stringResource(R.string.xzlc_modpack_apply_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    showApplyConfirm = false
                    apply()
                }) {
                    Text(text = stringResource(R.string.xzlc_modpack_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) {
                    Text(text = stringResource(R.string.xzlc_modpack_cancel))
                }
            }
        )
    }

    if (showRollbackConfirm) {
        AlertDialog(
            onDismissRequest = { showRollbackConfirm = false },
            title = { Text(text = stringResource(R.string.xzlc_modpack_rollback)) },
            text = { Text(text = stringResource(R.string.xzlc_modpack_rollback_confirm)) },
            confirmButton = {
                Button(onClick = {
                    showRollbackConfirm = false
                    rollback()
                }) {
                    Text(text = stringResource(R.string.xzlc_modpack_rollback))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRollbackConfirm = false }) {
                    Text(text = stringResource(R.string.xzlc_modpack_cancel))
                }
            }
        )
    }
}
