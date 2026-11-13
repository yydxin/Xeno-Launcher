/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.screens.content.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ai.AiApiFormat
import com.movtery.zalithlauncher.ai.AiProviderConfig
import com.movtery.zalithlauncher.ai.AiProviderTemplate
import com.movtery.zalithlauncher.ai.AiSettingsStore
import com.movtery.zalithlauncher.ai.WebSearchConfig
import com.movtery.zalithlauncher.ai.WebSearchProvider
import com.movtery.zalithlauncher.ai.ui.AiSettingsBusy
import com.movtery.zalithlauncher.ai.ui.AiSettingsMessage
import com.movtery.zalithlauncher.ai.ui.AiSettingsViewModel
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedColumn
import com.movtery.zalithlauncher.ui.components.ImePanContainer
import com.movtery.zalithlauncher.ui.components.OwnOutlinedTextField
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.components.SimpleTextSlider
import com.movtery.zalithlauncher.ui.components.TitleAndSummary
import com.movtery.zalithlauncher.ui.components.fadeEdge
import com.movtery.zalithlauncher.ui.components.rememberDialogMaxHeight
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn

/**
 * AI 服务设置页：服务商管理 + 联网搜索设置。
 *
 * 页面骨架与 RendererSettingsScreen 保持一致：
 * BaseScreen → AnimatedColumn → SettingsCardColumn，内边距 12dp。
 */
@Composable
fun AiProviderSettingsScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?,
    viewModel: AiSettingsViewModel = viewModel()
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<AiProviderConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<AiProviderConfig?>(null) }

    fun openEditor(provider: AiProviderConfig?) {
        viewModel.clearMessage()
        viewModel.clearFetchedModels()
        editingProvider = provider
        showEditor = true
    }

    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.Ai, settingsScreenKey, false)
    ) { isVisible ->
        AnimatedColumn(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 12.dp),
            isVisible = isVisible
        ) { scope ->
            AnimatedItem(scope) { yOffset ->
                viewModel.message?.let { message ->
                    NoticeBanner(
                        modifier = Modifier.offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                        message = message,
                        onDismiss = viewModel::clearMessage
                    )
                }
            }

            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    SettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Single
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(all = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TitleAndSummary(
                                title = stringResource(R.string.ai_settings_intro_title),
                                summary = stringResource(R.string.ai_settings_intro_body)
                            )
                        }
                    }
                }
            }

            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    Text(
                        modifier = Modifier.padding(all = 8.dp),
                        text = stringResource(R.string.ai_settings_providers_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    viewModel.busyAction?.let { busy ->
                        BusyRow(busy = busy)
                    }

                    if (viewModel.providers.isEmpty()) {
                        Text(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .alpha(0.7f),
                            text = stringResource(R.string.ai_settings_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    viewModel.providers.forEachIndexed { index, provider ->
                        val position = if (index == 0) {
                            CardPosition.Top
                        } else {
                            CardPosition.Middle
                        }
                        ProviderCard(
                            modifier = Modifier.fillMaxWidth(),
                            provider = provider,
                            isActive = provider.id == viewModel.activeProvider?.id,
                            keySaved = provider.id in viewModel.savedKeyIds,
                            busy = viewModel.busy,
                            busyAction = viewModel.busyAction,
                            position = position,
                            onSetActive = { viewModel.setActive(provider.id) },
                            onEdit = { openEditor(provider) },
                            onDelete = { pendingDelete = provider },
                            onTest = { viewModel.testConnection(provider, null) }
                        )
                    }

                    SettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = if (viewModel.providers.isEmpty()) {
                            CardPosition.Single
                        } else {
                            CardPosition.Bottom
                        },
                        onClick = { openEditor(null) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(all = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.ai_settings_add),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            AnimatedItem(scope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    SearchSettingsCard(
                        config = viewModel.searchConfig,
                        keySaved = viewModel.searchKeySaved,
                        busyAction = viewModel.busyAction,
                        onSave = { config, apiKey, onSaved ->
                            viewModel.saveSearchConfig(config, apiKey, onSaved)
                        }
                    )
                }
            }
        }
    }

    if (showEditor) {
        ProviderEditorDialog(
            initial = editingProvider,
            viewModel = viewModel,
            savedKeyExists = editingProvider?.let { it.id in viewModel.savedKeyIds } == true,
            onDismiss = { showEditor = false }
        )
    }

    pendingDelete?.let { provider ->
        SimpleAlertDialog(
            title = stringResource(R.string.ai_settings_delete_title),
            text = stringResource(R.string.ai_settings_delete_message, provider.name),
            confirmText = stringResource(R.string.generic_delete),
            dismissText = stringResource(R.string.generic_cancel),
            onConfirm = {
                viewModel.deleteProvider(provider.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun BusyRow(busy: AiSettingsBusy) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = busyText(busy),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    modifier: Modifier = Modifier,
    provider: AiProviderConfig,
    isActive: Boolean,
    keySaved: Boolean,
    busy: Boolean,
    busyAction: AiSettingsBusy?,
    position: CardPosition,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    SettingsCard(
        modifier = modifier,
        position = position
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = provider.name.ifBlank {
                            stringResource(R.string.ai_settings_unnamed_provider)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        modifier = Modifier.alpha(0.7f),
                        text = provider.baseUrl.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isActive) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            text = stringResource(R.string.ai_settings_active),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Text(
                text = stringResource(
                    R.string.ai_settings_format_label,
                    apiFormatLabel(provider.format)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = if (provider.defaultModel.isBlank()) {
                    stringResource(R.string.ai_settings_default_model_none)
                } else {
                    stringResource(R.string.ai_settings_default_model, provider.defaultModel)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(
                    if (keySaved) {
                        R.string.ai_settings_key_saved
                    } else {
                        R.string.ai_settings_key_missing
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (keySaved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onSetActive,
                    enabled = !isActive && !busy
                ) {
                    Text(stringResource(R.string.ai_settings_set_active))
                }
                TextButton(
                    onClick = onEdit,
                    enabled = !busy
                ) {
                    Text(stringResource(R.string.generic_edit))
                }
                TextButton(
                    onClick = onTest,
                    enabled = !busy
                ) {
                    Text(
                        stringResource(
                            if (busyAction == AiSettingsBusy.TEST_CONNECTION) {
                                R.string.ai_settings_testing
                            } else {
                                R.string.ai_settings_test
                            }
                        )
                    )
                }
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.generic_delete))
                }
            }
        }
    }
}

@Composable
private fun SearchSettingsCard(
    config: WebSearchConfig,
    keySaved: Boolean,
    busyAction: AiSettingsBusy?,
    onSave: (config: WebSearchConfig, apiKey: String?, onSaved: () -> Unit) -> Unit
) {
    var provider by remember { mutableStateOf(config.provider) }
    var endpoint by remember { mutableStateOf(config.endpoint) }
    var resultCount by remember { mutableIntStateOf(config.resultCount) }
    var apiKey by remember { mutableStateOf("") }
    var endpointError by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(config.provider, config.endpoint, config.resultCount) {
        provider = config.provider
        endpoint = config.endpoint
        resultCount = config.resultCount
    }

    SettingsCard(
        modifier = Modifier.fillMaxWidth(),
        position = CardPosition.Single
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TitleAndSummary(
                title = stringResource(R.string.ai_settings_search_title),
                summary = stringResource(R.string.ai_settings_search_summary)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.ai_settings_search_provider),
                    style = MaterialTheme.typography.titleSmall
                )
                Box {
                    AssistChip(
                        onClick = { providerMenuExpanded = true },
                        label = {
                            Text(
                                text = provider.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_drop_down_rounded),
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        WebSearchProvider.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.displayName) },
                                trailingIcon = if (entry == provider) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null
                                        )
                                    }
                                } else null,
                                onClick = {
                                    provider = entry
                                    endpointError = false
                                    providerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            OwnOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    endpointError = false
                },
                label = { Text(stringResource(R.string.ai_settings_search_endpoint)) },
                isError = endpointError,
                supportingText = {
                    Text(
                        text = if (endpointError) {
                            stringResource(R.string.ai_settings_error_endpoint_empty)
                        } else {
                            stringResource(R.string.ai_settings_search_endpoint_hint)
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.ai_settings_search_result_count),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = resultCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            SimpleTextSlider(
                modifier = Modifier.fillMaxWidth(),
                value = resultCount.toFloat(),
                valueRange = 1f..10f,
                steps = 8,
                toInt = true,
                onValueChange = { resultCount = it.toInt() }
            )

            OwnOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.ai_settings_search_api_key)) },
                supportingText = {
                    Text(
                        text = if (keySaved) {
                            stringResource(R.string.ai_settings_search_key_saved)
                        } else {
                            stringResource(R.string.ai_settings_search_key_missing)
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            Text(
                modifier = Modifier.alpha(0.7f),
                text = stringResource(R.string.ai_settings_search_key_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (busyAction == AiSettingsBusy.SAVE_SEARCH) {
                BusyRow(busy = AiSettingsBusy.SAVE_SEARCH)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        if (provider == WebSearchProvider.CUSTOM && endpoint.isBlank()) {
                            endpointError = true
                        } else {
                            endpointError = false
                            onSave(
                                WebSearchConfig(
                                    provider = provider,
                                    endpoint = endpoint.trim(),
                                    resultCount = resultCount
                                ),
                                apiKey.takeIf { it.isNotBlank() }
                            ) {
                                apiKey = ""
                            }
                        }
                    },
                    enabled = busyAction == null
                ) {
                    Text(stringResource(R.string.ai_settings_search_save))
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(
    message: AiSettingsMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (message.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (message.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(
                    message.textRes,
                    *message.args.toTypedArray()
                ),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = content)
            ) {
                Text(stringResource(R.string.ai_settings_notice_dismiss))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorDialog(
    initial: AiProviderConfig?,
    viewModel: AiSettingsViewModel,
    savedKeyExists: Boolean,
    onDismiss: () -> Unit
) {
    val editing = initial != null
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var format by remember { mutableStateOf(initial?.format ?: AiApiFormat.OPENAI) }
    var apiKey by remember { mutableStateOf("") }
    var modelsText by remember {
        mutableStateOf(initial?.models?.joinToString("\n").orEmpty())
    }
    var defaultModel by remember { mutableStateOf(initial?.defaultModel.orEmpty()) }
    var selectedTemplate by remember { mutableStateOf<AiProviderTemplate?>(null) }
    var templateMenuExpanded by remember { mutableStateOf(false) }
    var formatMenuExpanded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<Int?>(null) }
    val appliedFetchVersion = remember { mutableIntStateOf(viewModel.fetchedModelsVersion) }
    val scrollState = rememberScrollState()

    LaunchedEffect(viewModel.fetchedModelsVersion) {
        val version = viewModel.fetchedModelsVersion
        if (version > appliedFetchVersion.intValue) {
            appliedFetchVersion.intValue = version
            val fetched = viewModel.fetchedModels
            if (fetched.isNotEmpty()) {
                modelsText = fetched.joinToString("\n")
                if (defaultModel.isBlank()) {
                    defaultModel = fetched.first()
                }
            }
        }
    }

    fun readModels(): List<String> = modelsText
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    fun buildConfig(fallbackDefaultModel: Boolean): AiProviderConfig {
        val models = readModels()
        val fallback = models.firstOrNull().orEmpty()
        return AiProviderConfig(
            id = initial?.id.orEmpty(),
            name = name.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            format = format,
            models = models,
            defaultModel = defaultModel.trim().ifBlank {
                if (fallbackDefaultModel) fallback else ""
            },
            supportsTools = initial?.supportsTools ?: true
        )
    }

    fun applyTemplate(template: AiProviderTemplate) {
        selectedTemplate = template
        name = template.displayName
        baseUrl = template.baseUrl
        format = template.format
        modelsText = template.models.joinToString("\n")
        defaultModel = template.models.firstOrNull().orEmpty()
        validationError = null
    }

    Dialog(
        onDismissRequest = { if (!viewModel.busy) onDismiss() },
        properties = DialogProperties(decorFitsSystemWindows = false)
    ) {
        ImePanContainer(
            modifier = Modifier
                .heightIn(max = rememberDialogMaxHeight())
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(all = 6.dp)
                    .heightIn(max = (maxHeight - 12.dp).coerceAtMost(rememberDialogMaxHeight()))
                    .fillMaxWidth(0.97f)
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (editing) {
                                R.string.ai_settings_editor_edit_title
                            } else {
                                R.string.ai_settings_editor_add_title
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fadeEdge(scrollState)
                            .weight(1f, fill = false)
                            .verticalScrollWithBar(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.message?.let { message ->
                            NoticeBanner(
                                message = message,
                                onDismiss = viewModel::clearMessage
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.ai_settings_editor_template),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Box {
                                AssistChip(
                                    onClick = { templateMenuExpanded = true },
                                    label = {
                                        Text(
                                            text = selectedTemplate?.displayName
                                                ?: stringResource(R.string.ai_settings_editor_template_none),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_drop_down_rounded),
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenu(
                                    expanded = templateMenuExpanded,
                                    onDismissRequest = { templateMenuExpanded = false }
                                ) {
                                    AiSettingsStore.templates.forEach { template ->
                                        DropdownMenuItem(
                                            text = { Text(template.displayName) },
                                            onClick = {
                                                applyTemplate(template)
                                                templateMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        OwnOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = name,
                            onValueChange = {
                                name = it
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.ai_settings_editor_name)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )

                        OwnOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.ai_settings_editor_base_url)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.ai_settings_editor_format),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Box {
                                AssistChip(
                                    onClick = { formatMenuExpanded = true },
                                    label = { Text(apiFormatLabel(format)) },
                                    trailingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_drop_down_rounded),
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenu(
                                    expanded = formatMenuExpanded,
                                    onDismissRequest = { formatMenuExpanded = false }
                                ) {
                                    AiApiFormat.entries.forEach { entry ->
                                        DropdownMenuItem(
                                            text = { Text(apiFormatLabel(entry)) },
                                            trailingIcon = if (entry == format) {
                                                {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_check),
                                                        contentDescription = null
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                format = entry
                                                formatMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        OwnOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.ai_settings_editor_api_key)) },
                            supportingText = {
                                Text(
                                    text = if (editing && savedKeyExists) {
                                        stringResource(R.string.ai_settings_editor_api_key_saved)
                                    } else {
                                        stringResource(R.string.ai_settings_editor_api_key_new)
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )

                        OwnOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = modelsText,
                            onValueChange = {
                                modelsText = it
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.ai_settings_editor_models)) },
                            supportingText = {
                                Text(
                                    text = stringResource(R.string.ai_settings_editor_models_hint),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            singleLine = false,
                            minLines = 3,
                            maxLines = 6,
                            shape = MaterialTheme.shapes.large
                        )

                        OwnOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = defaultModel,
                            onValueChange = {
                                defaultModel = it
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.ai_settings_editor_default_model)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )

                        validationError?.let { errorRes ->
                            Text(
                                text = stringResource(errorRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.fetchModels(
                                        buildConfig(fallbackDefaultModel = true),
                                        apiKey.takeIf { it.isNotBlank() }
                                    )
                                },
                                enabled = !viewModel.busy
                            ) {
                                Text(
                                    stringResource(
                                        if (viewModel.busyAction == AiSettingsBusy.FETCH_MODELS) {
                                            R.string.ai_settings_editor_fetching
                                        } else {
                                            R.string.ai_settings_editor_fetch
                                        }
                                    )
                                )
                            }
                            TextButton(
                                onClick = {
                                    viewModel.testConnection(
                                        buildConfig(fallbackDefaultModel = true),
                                        apiKey.takeIf { it.isNotBlank() }
                                    )
                                },
                                enabled = !viewModel.busy
                            ) {
                                Text(
                                    stringResource(
                                        if (viewModel.busyAction == AiSettingsBusy.TEST_CONNECTION) {
                                            R.string.ai_settings_testing
                                        } else {
                                            R.string.ai_settings_editor_test
                                        }
                                    )
                                )
                            }
                            TextButton(
                                onClick = {
                                    viewModel.testToolCalling(
                                        buildConfig(fallbackDefaultModel = true),
                                        apiKey.takeIf { it.isNotBlank() }
                                    )
                                },
                                enabled = !viewModel.busy
                            ) {
                                Text(
                                    stringResource(
                                        if (viewModel.busyAction == AiSettingsBusy.TEST_TOOLS) {
                                            R.string.ai_settings_editor_testing_tools
                                        } else {
                                            R.string.ai_settings_editor_test_tools
                                        }
                                    )
                                )
                            }
                        }

                        viewModel.busyAction?.let { busy ->
                            BusyRow(busy = busy)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss
                        ) {
                            Text(stringResource(R.string.generic_cancel))
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val config = buildConfig(fallbackDefaultModel = false)
                                val error = validateEditorConfig(config)
                                if (error != null) {
                                    validationError = error
                                } else {
                                    validationError = null
                                    val keyToSave = when {
                                        apiKey.isNotBlank() -> apiKey
                                        editing -> null
                                        else -> ""
                                    }
                                    viewModel.saveProvider(config, keyToSave, onDismiss)
                                }
                            },
                            enabled = !viewModel.busy
                        ) {
                            Text(
                                stringResource(
                                    if (viewModel.busyAction == AiSettingsBusy.SAVE_PROVIDER) {
                                        R.string.ai_settings_editor_saving
                                    } else {
                                        R.string.generic_save
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun validateEditorConfig(config: AiProviderConfig): Int? {
    val url = config.baseUrl
    return when {
        config.name.isBlank() -> R.string.ai_settings_error_name_empty
        url.isBlank() -> R.string.ai_settings_error_url_empty
        !url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true) ->
            R.string.ai_settings_error_url_invalid
        config.models.isEmpty() -> R.string.ai_settings_error_models_empty
        config.defaultModel.isBlank() -> R.string.ai_settings_error_model_empty
        else -> null
    }
}

@Composable
private fun apiFormatLabel(format: AiApiFormat): String = stringResource(
    when (format) {
        AiApiFormat.OPENAI -> R.string.ai_settings_format_openai
        AiApiFormat.ANTHROPIC -> R.string.ai_settings_format_anthropic
    }
)

@Composable
private fun busyText(busy: AiSettingsBusy): String = stringResource(
    when (busy) {
        AiSettingsBusy.SAVE_PROVIDER -> R.string.ai_settings_busy_save
        AiSettingsBusy.DELETE_PROVIDER -> R.string.ai_settings_busy_delete
        AiSettingsBusy.FETCH_MODELS -> R.string.ai_settings_busy_fetch_models
        AiSettingsBusy.TEST_CONNECTION -> R.string.ai_settings_busy_test_connection
        AiSettingsBusy.TEST_TOOLS -> R.string.ai_settings_busy_test_tools
        AiSettingsBusy.SAVE_SEARCH -> R.string.ai_settings_busy_save_search
    }
)
