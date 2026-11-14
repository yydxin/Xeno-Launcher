/*
 * Zalith Launcher 2
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

package com.movtery.zalithlauncher.ui.screens.content.settings

import com.movtery.zalithlauncher.ui.glass.GlassToast as Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.movtery.zalithlauncher.ui.glass.GlassButton as Button
import com.movtery.zalithlauncher.ui.glass.GlassFilledTonalButton as FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesConfig
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesGlslCachePolicy
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesConfigDebugLog
import com.movtery.zalithlauncher.ui.components.SimpleTextSlider
import com.movtery.zalithlauncher.ui.components.SliderValueEditDialog
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesDiagnostics
import com.movtery.zalithlauncher.game.renderer.renderers.MobileGluesRenderer
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedColumn
import com.movtery.zalithlauncher.ui.components.MarqueeText
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.components.TitleAndSummary
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.IntSliderSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.ListSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.TextInputSettingsCard
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.utils.file.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.io.File
import java.util.Date

/**
 * MobileGlues 内置渲染器的配置页面。
 *
 * 配置不写入启动器设置，而是直接读写 MobileGlues 的 `<MG_DIR_PATH>/config.json`；
 * 缓存只在用户松手/点击箭头/确认手输时保存；其余项目使用“保存”。加载和恢复赋值不触发保存。
 *
 * @param onOpenLog 打开日志查看器，参数为 latest.log 的绝对路径
 */
@Composable
fun MobileGluesSettingsScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?,
    onOpenLog: (String) -> Unit
) {
    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.MobileGlues, settingsScreenKey, false)
    ) { isVisible ->
        val context = LocalContext.current
        val uiScope = rememberCoroutineScope()

        val settingsModel: MobileGluesSettingsViewModel = viewModel(key = "mobileglues-settings")
        val state by settingsModel.state.collectAsStateWithLifecycle()
        val config = state.config
        var diagnostics by remember { mutableStateOf<MobileGluesDiagnostics?>(null) }
        var showResetDialog by remember { mutableStateOf(false) }
        var debugEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            debugEnabled = withContext(Dispatchers.IO) { MobileGluesConfigDebugLog.isEnabled() }
        }
        val viewDebugLog: () -> Unit = {
            uiScope.launch {
                val ready = withContext(Dispatchers.IO) {
                    runCatching {
                        MobileGluesConfigDebugLog.flushPendingMappings()
                        check(MobileGluesConfigDebugLog.logFile.isFile)
                    }.isSuccess
                }
                if (ready) onOpenLog(MobileGluesConfigDebugLog.logFile.absolutePath)
                else Toast.makeText(context, R.string.mg_config_debug_missing, Toast.LENGTH_SHORT).show()
            }
        }
        val exportDebugLog: () -> Unit = {
            uiScope.launch {
                val snapshot = withContext(Dispatchers.IO) { runCatching {
                    MobileGluesConfigDebugLog.exportSnapshot(File(context.cacheDir,
                        "mg-config-debug-${System.currentTimeMillis()}.log"))
                } }
                snapshot.onSuccess { file ->
                    runCatching { shareFile(context, file) }.onFailure {
                        Toast.makeText(context, R.string.mg_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { Toast.makeText(context, R.string.mg_config_debug_missing, Toast.LENGTH_SHORT).show() }
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current

        // Re-read the exact MG file after tab changes and after returning from the game.
        LaunchedEffect(isVisible) { if (isVisible) settingsModel.reload() }
        DisposableEffect(lifecycleOwner, isVisible) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && isVisible) settingsModel.reload()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(state.config, state.loading) {
            if (!state.loading) diagnostics = withContext(Dispatchers.IO) {
                MobileGluesDiagnostics.inspect(context)
            }
        }
        LaunchedEffect(settingsModel) {
            settingsModel.saveResults.collect { saved ->
                Toast.makeText(context, if (saved) R.string.generic_saved else R.string.mg_save_failed,
                    Toast.LENGTH_SHORT).show()
            }
        }

        if (config == null || state.loading) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (state.loading) R.string.mg_status_loading else R.string.mg_config_read_failed))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.loading) {
                    Button(onClick = settingsModel::reload) { Text(stringResource(R.string.mg_config_retry)) }
                    FilledTonalButton(onClick = viewDebugLog) { Text(stringResource(R.string.mg_config_debug_view)) }
                    FilledTonalButton(onClick = exportDebugLog) { Text(stringResource(R.string.mg_config_debug_export)) }
                }
            }
            return@BaseScreen
        }

        //multidrawOrder 是 MobileGlues 的必填项，空串会被 MobileGlues 当成未设置
        val multidrawOrderValid = config.multidrawOrder.isNotBlank()
        val multidrawOrderHint: (@Composable () -> Unit)? = if (multidrawOrderValid) {
            null
        } else {
            val hint: @Composable () -> Unit = {
                Text(text = stringResource(R.string.mg_multidraw_order_error))
            }
            hint
        }

        AnimatedColumn(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 12.dp),
            isVisible = isVisible
        ) { scope ->
            //运行状态 + 当前渲染器提示
            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    val isCurrent = MobileGluesRenderer.isCurrent()

                    if (!isCurrent) {
                        SettingsCard(
                            modifier = Modifier.fillMaxWidth(),
                            position = CardPosition.Top
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(all = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_info_outlined),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = stringResource(R.string.mg_screen_not_current_warning),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    SettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = if (isCurrent) CardPosition.Single else CardPosition.Bottom
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(all = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TitleAndSummary(
                                title = stringResource(R.string.mg_status_title),
                                summary = stringResource(R.string.mg_status_summary)
                            )

                            val inspection = diagnostics
                            if (inspection == null) {
                                Text(
                                    modifier = Modifier.alpha(0.7f),
                                    text = stringResource(R.string.mg_status_loading),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                val libraryValue = if (inspection.libraryPresent) {
                                    stringResource(
                                        R.string.mg_status_present_with_size,
                                        formatFileSize(inspection.librarySizeBytes)
                                    )
                                } else {
                                    stringResource(R.string.mg_status_missing)
                                }
                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_library),
                                    value = libraryValue,
                                    isError = !inspection.libraryPresent
                                )

                                val abiValue = if (inspection.deviceAbis.isEmpty()) {
                                    stringResource(R.string.mg_status_unknown)
                                } else {
                                    inspection.deviceAbis.joinToString(", ")
                                }
                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_abi),
                                    value = abiValue,
                                    isError = !inspection.abiSupported
                                )
                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_abi_support),
                                    value = if (inspection.abiSupported) {
                                        stringResource(R.string.mg_status_abi_supported, inspection.requiredAbi)
                                    } else {
                                        stringResource(R.string.mg_status_abi_unsupported, inspection.requiredAbi)
                                    },
                                    isError = !inspection.abiSupported
                                )

                                val glesValue = if (inspection.glesVersion.isEmpty()) {
                                    stringResource(R.string.mg_status_unknown)
                                } else {
                                    inspection.glesVersion
                                }
                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_gles),
                                    value = glesValue,
                                    isError = inspection.glesVersionCode == 0 || !inspection.glesSupported
                                )

                                val configDirValue = if (inspection.configDirectoryPath.isEmpty()) {
                                    stringResource(R.string.mg_status_unknown)
                                } else {
                                    inspection.configDirectoryPath
                                }
                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_config_dir),
                                    value = configDirValue
                                )
                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_config_file),
                                    value = when {
                                        !inspection.configExists -> stringResource(R.string.mg_status_config_missing)
                                        inspection.configValid -> stringResource(R.string.mg_status_config_ready)
                                        else -> stringResource(R.string.mg_status_config_invalid)
                                    },
                                    isError = inspection.configExists && !inspection.configValid
                                )

                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_log),
                                    value = if (inspection.logExists) {
                                        stringResource(
                                            R.string.mg_status_log_value,
                                            formatFileSize(inspection.logSizeBytes),
                                            formatDateTime(inspection.logLastModified)
                                        )
                                    } else {
                                        stringResource(R.string.mg_status_log_missing)
                                    }
                                )

                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_current),
                                    value = stringResource(
                                        if (isCurrent) R.string.mg_status_current_yes else R.string.mg_status_current_no
                                    ),
                                    isError = !isCurrent
                                )

                                DiagnosticRow(
                                    title = stringResource(R.string.mg_status_checked_at),
                                    value = formatDateTime(inspection.checkedAt)
                                )

                                for (issue in inspection.issues) {
                                    //日志/配置尚未生成属于正常状态，用普通文字提示，其余问题用错误色
                                    val informational = issue == MobileGluesDiagnostics.Issue.LOG_MISSING ||
                                            issue == MobileGluesDiagnostics.Issue.CONFIG_MISSING
                                    Text(
                                        modifier = if (informational) Modifier.alpha(0.7f) else Modifier,
                                        text = issueText(issue),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (informational) {
                                            LocalContentColor.current
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            //后端与兼容性
            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    IntOptionSettingsCard(
                        enabled = state.editable,
                        value = config.enableANGLE,
                        options = angleOptions,
                        title = stringResource(R.string.mg_enable_angle_title),
                        summary = stringResource(R.string.mg_enable_angle_summary),
                        position = CardPosition.Top,
                        onValueChange = { value -> settingsModel.update { it.copy(enableANGLE = value) } }
                    )

                    IntOptionSettingsCard(
                        enabled = state.editable,
                        value = config.enableNoError,
                        options = noErrorOptions,
                        title = stringResource(R.string.mg_enable_noerror_title),
                        summary = stringResource(R.string.mg_enable_noerror_summary),
                        position = CardPosition.Middle,
                        onValueChange = { value -> settingsModel.update { it.copy(enableNoError = value) } }
                    )

                    IntOptionSettingsCard(
                        enabled = state.editable,
                        value = config.customGLVersion,
                        options = glVersionOptions,
                        title = stringResource(R.string.mg_custom_gl_version_title),
                        summary = stringResource(R.string.mg_custom_gl_version_summary),
                        position = CardPosition.Middle,
                        onValueChange = { value -> settingsModel.update { it.copy(customGLVersion = value) } }
                    )

                    IntOptionSettingsCard(
                        enabled = state.editable,
                        value = config.fsr1Setting,
                        options = fsrOptions,
                        title = stringResource(R.string.mg_fsr_title),
                        summary = stringResource(R.string.mg_fsr_summary),
                        position = CardPosition.Middle,
                        onValueChange = { value -> settingsModel.update { it.copy(fsr1Setting = value) } }
                    )

                    IntOptionSettingsCard(
                        enabled = state.editable,
                        value = config.angleDepthClearFixMode,
                        options = depthClearOptions,
                        title = stringResource(R.string.mg_depth_clear_title),
                        summary = stringResource(R.string.mg_depth_clear_summary),
                        position = CardPosition.Bottom,
                        onValueChange = { value -> settingsModel.update { it.copy(angleDepthClearFixMode = value) } }
                    )
                }
            }

            //扩展开关与缓存
            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    SwitchSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        checked = config.enableExtComputeShader != 0,
                        onCheckedChange = { checked ->
                            settingsModel.update { it.copy(enableExtComputeShader = if (checked) 1 else 0) }
                        },
                        title = stringResource(R.string.mg_ext_compute_title),
                        summary = stringResource(R.string.mg_ext_compute_summary)
                    )

                    SwitchSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        checked = config.enableExtTimerQuery != 0,
                        onCheckedChange = { checked ->
                            settingsModel.update { it.copy(enableExtTimerQuery = if (checked) 1 else 0) }
                        },
                        title = stringResource(R.string.mg_ext_timer_title),
                        summary = stringResource(R.string.mg_ext_timer_summary)
                    )

                    SwitchSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        checked = config.enableExtDirectStateAccess != 0,
                        onCheckedChange = { checked ->
                            settingsModel.update { it.copy(enableExtDirectStateAccess = if (checked) 1 else 0) }
                        },
                        title = stringResource(R.string.mg_ext_dsa_title),
                        summary = stringResource(R.string.mg_ext_dsa_summary)
                    )

                    SwitchSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        checked = config.hideMGEnvLevel != MobileGluesConfig.HIDE_ENV_DISABLED,
                        onCheckedChange = { checked ->
                            settingsModel.update { it.copy(
                                hideMGEnvLevel = if (checked) {
                                    MobileGluesConfig.HIDE_ENV_LEVEL_1
                                } else {
                                    MobileGluesConfig.HIDE_ENV_DISABLED
                                }
                            ) }
                        },
                        title = stringResource(R.string.mg_hide_env_title),
                        summary = stringResource(R.string.mg_hide_env_summary)
                    )

                    // Fixed MB scale: 512 MB is always exactly 50%; no available-memory dependency.
                    // The draft also lives in the ViewModel, never in the scrolling component.
                    MobileGluesCacheSettingsCard(
                        value = state.cacheValue!!,
                        enabled = state.editable,
                        onPreview = settingsModel::previewCache,
                        onCommit = settingsModel::commitCache
                    )
                }
            }

            //Multidraw 顺序
            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    TextInputSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        value = config.multidrawOrder,
                        title = stringResource(R.string.mg_multidraw_order_title),
                        position = CardPosition.Top,
                        summary = stringResource(R.string.mg_multidraw_order_summary),
                        onValueChange = { value -> settingsModel.update { it.copy(multidrawOrder = value) } },
                        isError = !multidrawOrderValid,
                        label = { Text(text = stringResource(R.string.mg_multidraw_order_label)) },
                        supportingText = multidrawOrderHint,
                        singleLine = true
                    )

                    TextInputSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        value = config.multidrawOrderArrays,
                        title = stringResource(R.string.mg_multidraw_arrays_title),
                        position = CardPosition.Middle,
                        summary = stringResource(R.string.mg_multidraw_entry_summary),
                        onValueChange = { value -> settingsModel.update { it.copy(multidrawOrderArrays = value) } }
                    )

                    TextInputSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        value = config.multidrawOrderElements,
                        title = stringResource(R.string.mg_multidraw_elements_title),
                        position = CardPosition.Middle,
                        summary = stringResource(R.string.mg_multidraw_entry_summary),
                        onValueChange = { value -> settingsModel.update { it.copy(multidrawOrderElements = value) } }
                    )

                    TextInputSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        value = config.multidrawOrderElementsBaseVertex,
                        title = stringResource(R.string.mg_multidraw_elements_base_vertex_title),
                        position = CardPosition.Middle,
                        summary = stringResource(R.string.mg_multidraw_entry_summary),
                        onValueChange = { value -> settingsModel.update { it.copy(multidrawOrderElementsBaseVertex = value) } }
                    )

                    TextInputSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        value = config.multidrawOrderArraysIndirect,
                        title = stringResource(R.string.mg_multidraw_arrays_indirect_title),
                        position = CardPosition.Middle,
                        summary = stringResource(R.string.mg_multidraw_entry_summary),
                        onValueChange = { value -> settingsModel.update { it.copy(multidrawOrderArraysIndirect = value) } }
                    )

                    TextInputSettingsCard(
                        enabled = state.editable,
                        modifier = Modifier.fillMaxWidth(),
                        value = config.multidrawOrderElementsIndirect,
                        title = stringResource(R.string.mg_multidraw_elements_indirect_title),
                        position = CardPosition.Bottom,
                        summary = stringResource(R.string.mg_multidraw_entry_summary),
                        onValueChange = { value -> settingsModel.update { it.copy(multidrawOrderElementsIndirect = value) } }
                    )
                }
            }

            //保存 / 恢复默认 / 查看日志
            AnimatedItem(scope) { yOffset ->
                SettingsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    position = CardPosition.Single
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TitleAndSummary(
                            title = stringResource(R.string.mg_actions_title),
                            summary = stringResource(R.string.mg_actions_summary)
                        )
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        SwitchSettingsCard(
                            enabled = state.editable,
                            modifier = Modifier.fillMaxWidth(),
                            position = CardPosition.Single,
                            checked = debugEnabled,
                            title = stringResource(R.string.mg_config_debug_title),
                            summary = stringResource(R.string.mg_config_debug_summary),
                            onCheckedChange = { checked ->
                                uiScope.launch {
                                    val changed = withContext(Dispatchers.IO) {
                                        MobileGluesConfigDebugLog.setEnabled(checked)
                                    }
                                    if (changed) {
                                        debugEnabled = checked
                                        settingsModel.reload()
                                    } else Toast.makeText(context, R.string.mg_save_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(modifier = Modifier.weight(1f), onClick = viewDebugLog) {
                                MarqueeText(text = stringResource(R.string.mg_config_debug_view))
                            }
                            FilledTonalButton(modifier = Modifier.weight(1f), onClick = exportDebugLog) {
                                MarqueeText(text = stringResource(R.string.mg_config_debug_export))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = state.editable && multidrawOrderValid,
                                onClick = settingsModel::save
                            ) {
                                MarqueeText(text = stringResource(R.string.generic_save))
                            }

                            FilledTonalButton(
                                modifier = Modifier.weight(1f),
                                enabled = state.editable,
                                onClick = { showResetDialog = true }
                            ) {
                                MarqueeText(text = stringResource(R.string.mg_restore_defaults))
                            }
                        }

                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = diagnostics?.logExists == true,
                            onClick = { onOpenLog(MobileGluesConfig.logFile.absolutePath) }
                        ) {
                            MarqueeText(text = stringResource(R.string.mg_view_log))
                        }

                        if (diagnostics?.logExists != true) {
                            Text(
                                modifier = Modifier.alpha(0.7f),
                                text = stringResource(R.string.mg_view_log_missing),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        if (showResetDialog) {
            SimpleAlertDialog(
                title = stringResource(R.string.mg_restore_defaults_title),
                text = stringResource(R.string.mg_restore_defaults_message),
                confirmText = stringResource(R.string.mg_restore_defaults_confirm),
                onConfirm = {
                    showResetDialog = false
                    settingsModel.reset()
                },
                onDismiss = { showResetDialog = false }
            )
        }
    }
}

/** Only dialog visibility is local; every value, including the drag preview, comes from StateFlow. */
@Composable
private fun MobileGluesCacheSettingsCard(
    value: Int,
    enabled: Boolean,
    onPreview: (Int) -> Unit,
    onCommit: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.mg_max_cache_title)
    val range = MobileGluesGlslCachePolicy.MIN_MB.toFloat()..MobileGluesGlslCachePolicy.MAX_MB.toFloat()
    SettingsCard(modifier = Modifier.fillMaxWidth(), position = CardPosition.Bottom) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TitleAndSummary(title = title, summary = stringResource(R.string.mg_max_cache_fixed_summary))
            SimpleTextSlider(
                modifier = Modifier.fillMaxWidth(),
                value = MobileGluesConfigDebugLog.valueToSlider(value),
                enabled = enabled,
                shorter = true,
                toInt = true,
                suffix = stringResource(R.string.mg_unit_mb),
                valueRange = range,
                steps = MobileGluesGlslCachePolicy.MAX_MB - MobileGluesGlslCachePolicy.MIN_MB - 1,
                fineTuningControl = true,
                fineTuningStep = 1f,
                onValueChange = { onPreview(MobileGluesConfigDebugLog.sliderToValue(it)) },
                onValueChangeFinished = onCommit,
                onTextClick = { editing = true }
            )
        }
    }
    if (editing && enabled) {
        SliderValueEditDialog(
            onDismissRequest = { editing = false },
            title = title,
            valueRange = range,
            value = value.toFloat(),
            intCheck = true,
            onValueChange = { onPreview(MobileGluesConfigDebugLog.sliderToValue(it)) },
            onValueChangeFinished = onCommit
        )
    }
}

/**
 * 下拉选项：value 为写入 config.json 的整数，labelRes 为界面文案。
 * labelArgs 用于 GL 版本这类由两个数字拼接的文案。
 */
private data class IntOption(
    val value: Int,
    @StringRes val labelRes: Int,
    val labelArgs: List<Int> = emptyList()
)

private val angleOptions = listOf(
    IntOption(MobileGluesConfig.ANGLE_DISABLE_IF_POSSIBLE, R.string.mg_angle_mode_disable_if_possible),
    IntOption(MobileGluesConfig.ANGLE_ENABLE_IF_POSSIBLE, R.string.mg_angle_mode_enable_if_possible),
    IntOption(MobileGluesConfig.ANGLE_FORCE_DISABLE, R.string.mg_angle_mode_force_disable),
    IntOption(MobileGluesConfig.ANGLE_FORCE_ENABLE, R.string.mg_angle_mode_force_enable)
)

private val noErrorOptions = listOf(
    IntOption(MobileGluesConfig.NO_ERROR_AUTO, R.string.mg_noerror_auto),
    IntOption(MobileGluesConfig.NO_ERROR_DISABLE, R.string.mg_noerror_disable),
    IntOption(MobileGluesConfig.NO_ERROR_LEVEL_1, R.string.mg_noerror_level1),
    IntOption(MobileGluesConfig.NO_ERROR_LEVEL_2, R.string.mg_noerror_level2)
)

/**
 * MobileGlues 会把 34..39 夹取为 33，因此只列出有效档位。
 */
private val glVersionOptions = listOf(
    IntOption(MobileGluesConfig.GL_VERSION_DEFAULT, R.string.mg_gl_version_default),
    IntOption(32, R.string.mg_gl_version_value, listOf(3, 2)),
    IntOption(33, R.string.mg_gl_version_value, listOf(3, 3)),
    IntOption(40, R.string.mg_gl_version_value, listOf(4, 0)),
    IntOption(41, R.string.mg_gl_version_value, listOf(4, 1)),
    IntOption(42, R.string.mg_gl_version_value, listOf(4, 2)),
    IntOption(43, R.string.mg_gl_version_value, listOf(4, 3)),
    IntOption(44, R.string.mg_gl_version_value, listOf(4, 4)),
    IntOption(45, R.string.mg_gl_version_value, listOf(4, 5)),
    IntOption(46, R.string.mg_gl_version_value, listOf(4, 6))
)

private val fsrOptions = listOf(
    IntOption(MobileGluesConfig.FSR_DISABLED, R.string.mg_fsr_disabled),
    IntOption(MobileGluesConfig.FSR_ULTRA_QUALITY, R.string.mg_fsr_ultra_quality),
    IntOption(MobileGluesConfig.FSR_QUALITY, R.string.mg_fsr_quality),
    IntOption(MobileGluesConfig.FSR_BALANCED, R.string.mg_fsr_balanced),
    IntOption(MobileGluesConfig.FSR_PERFORMANCE, R.string.mg_fsr_performance)
)

private val depthClearOptions = listOf(
    IntOption(MobileGluesConfig.DEPTH_CLEAR_DISABLED, R.string.mg_depth_clear_disabled),
    IntOption(MobileGluesConfig.DEPTH_CLEAR_MODE_1, R.string.mg_depth_clear_mode1),
    IntOption(MobileGluesConfig.DEPTH_CLEAR_MODE_2, R.string.mg_depth_clear_mode2)
)

/**
 * 整数下拉卡片：复用设置页已有的 ListSettingsCard（不依赖设置单元，直接回调值）。
 */
@Composable
private fun IntOptionSettingsCard(
    value: Int,
    options: List<IntOption>,
    title: String,
    summary: String,
    position: CardPosition,
    onValueChange: (Int) -> Unit,
    enabled: Boolean = true
) {
    ListSettingsCard(
        modifier = Modifier.fillMaxWidth(),
        items = options,
        enabled = enabled,
        currentId = value.toString(),
        defaultId = options.first().value.toString(),
        title = title,
        position = position,
        summary = summary,
        getItemText = { option -> stringResource(option.labelRes, *option.labelArgs.toTypedArray()) },
        getItemId = { option -> option.value.toString() },
        onValueChange = { option -> onValueChange(option.value) }
    )
}

/**
 * 状态卡中的一行：标题 + 值；异常值使用主题的 error 色。
 */
@Composable
private fun DiagnosticRow(
    title: String,
    value: String,
    isError: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            modifier = if (isError) Modifier else Modifier.alpha(0.7f),
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else LocalContentColor.current
        )
    }
}

@Composable
private fun issueText(issue: MobileGluesDiagnostics.Issue): String = when (issue) {
    MobileGluesDiagnostics.Issue.LIBRARY_MISSING -> stringResource(R.string.mg_issue_library_missing)
    MobileGluesDiagnostics.Issue.ABI_UNSUPPORTED -> stringResource(R.string.mg_issue_abi_unsupported)
    MobileGluesDiagnostics.Issue.GLES_UNKNOWN -> stringResource(R.string.mg_issue_gles_unknown)
    MobileGluesDiagnostics.Issue.GLES_TOO_LOW -> stringResource(R.string.mg_issue_gles_low)
    MobileGluesDiagnostics.Issue.CONFIG_MISSING -> stringResource(R.string.mg_issue_config_missing)
    MobileGluesDiagnostics.Issue.CONFIG_INVALID -> stringResource(R.string.mg_issue_config_invalid)
    MobileGluesDiagnostics.Issue.LOG_MISSING -> stringResource(R.string.mg_issue_log_missing)
    MobileGluesDiagnostics.Issue.INSPECTION_FAILED -> stringResource(R.string.mg_issue_inspection_failed)
}

private fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
}
