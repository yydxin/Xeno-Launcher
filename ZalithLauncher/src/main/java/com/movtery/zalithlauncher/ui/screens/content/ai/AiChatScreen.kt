/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("DEPRECATION")

package com.movtery.zalithlauncher.ui.screens.content.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.movtery.zalithlauncher.ui.glass.GlassAssistChip as AssistChip
import com.movtery.zalithlauncher.ui.glass.GlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.movtery.zalithlauncher.ui.glass.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.movtery.zalithlauncher.ui.glass.GlassFilledTonalButton as FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.movtery.zalithlauncher.ui.glass.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.movtery.zalithlauncher.ui.glass.GlassSurface as Surface
import androidx.compose.material3.Text
import com.movtery.zalithlauncher.ui.glass.GlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.ui.glass.GlassDialog as Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ai.AiProviderConfig
import com.movtery.zalithlauncher.ai.ChatMode
import com.movtery.zalithlauncher.ai.AiSettingsStore
import com.movtery.zalithlauncher.ai.ChatSession
import com.movtery.zalithlauncher.ai.ui.AiChatViewModel
import com.movtery.zalithlauncher.ui.components.OwnOutlinedTextField
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * AI 聊天页。
 *
 * 消息区、工具调用、确认弹窗都直接映射 [AiChatViewModel] 的真实状态；
 * 本文件不保存任何假数据，也不代替 ViewModel 发请求。
 */
@Composable
fun AiChatScreen(
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    viewModel: AiChatViewModel = viewModel()
) {
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var availableProviders by remember { mutableStateOf(AiSettingsStore.providers()) }
    val listState = rememberLazyListState()
    val messageClipboard = LocalClipboardManager.current

    val messages = viewModel.messages
    val activeProvider = viewModel.activeProvider
    val streamingVisible = viewModel.generating &&
        (viewModel.streamingText.isNotEmpty() || viewModel.liveTools.isEmpty())
    val listItemCount = messages.size +
        (if (streamingVisible) 1 else 0) +
        viewModel.liveTools.size

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (
                last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 8
                )
        }
    }

    LaunchedEffect(viewModel.current?.id) {
        if (listItemCount > 0) {
            listState.scrollToItem(listItemCount - 1)
        }
    }
    LaunchedEffect(listItemCount, viewModel.streamingText.length) {
        if (listItemCount > 0 && isAtBottom) {
            listState.scrollToItem(listItemCount - 1)
        }
    }

    val submit: () -> Unit = {
        if (!viewModel.generating && viewModel.activeProvider != null) {
            val text = input.trim()
            if (text.isNotEmpty()) {
                val before = viewModel.messages.size
                viewModel.send(text)
                // send 在真正追加消息后才算被接受；缺少 Key/模型时保留草稿，方便用户去设置补全
                if (viewModel.messages.size > before) {
                    input = ""
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(all = 12.dp)
    ) {
        ChatTopBar(
            providerName = activeProvider?.name.orEmpty(),
            modelName = viewModel.activeModel,
            providers = availableProviders,
            activeProviderId = activeProvider?.id.orEmpty(),
            models = activeProvider?.usableModels().orEmpty(),
            onRefreshProviders = {
                viewModel.refreshProviders()
                availableProviders = AiSettingsStore.providers()
            },
            onSelectProvider = { viewModel.selectProvider(it) },
            onSelectModel = { viewModel.selectModel(it) },
            mode = viewModel.mode,
            onSelectMode = viewModel::selectMode,
            onNewSession = {
                viewModel.newSession()
                input = ""
                showHistory = false
            },
            onShowHistory = { showHistory = true },
            onOpenSettings = onOpenSettings,
            onBack = onBack
        )

        Spacer(modifier = Modifier.height(8.dp))

        viewModel.error?.let { error ->
            ChatErrorCard(
                text = error,
                onDismiss = viewModel::clearError
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (activeProvider == null) {
            NoProviderCard(onOpenSettings = onOpenSettings)
            Spacer(modifier = Modifier.height(8.dp))
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val messageMaxWidth = (
                if (maxWidth >= 600.dp) maxWidth * 0.75f else maxWidth * 0.86f
                ).coerceAtMost(640.dp)

            if (messages.isEmpty() && !viewModel.generating) {
                EmptyChatState(
                    onSuggestion = { input = it }
                )
            } else {
                val toolArguments = remember(viewModel.current?.id, messages.toList()) {
                    buildMap {
                        messages.forEach { message ->
                            if (message.role == "assistant") {
                                message.toolCalls.forEach { call ->
                                    put(call.id, call.arguments)
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(messages) { _, message ->
                        when (message.role) {
                            "user" -> UserMessageBubble(
                                content = message.content,
                                maxWidth = messageMaxWidth
                            )

                            "assistant" -> {
                                if (message.content.isNotBlank()) {
                                    Column {
                                        if (message.reasoning.isNotBlank()) {
                                            ReasoningPanel(
                                                text = message.reasoning,
                                                maxWidth = messageMaxWidth
                                            )
                                        }
                                        AssistantMessageBubble(
                                            content = message.content,
                                            streaming = false,
                                            maxWidth = messageMaxWidth
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = {
                                                    messageClipboard.setText(AnnotatedString(message.content))
                                                }
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.generic_copy),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                                            if (!viewModel.generating && lastAssistant?.timestamp == message.timestamp) {
                                                TextButton(onClick = { viewModel.regenerate() }) {
                                                    Text(
                                                        text = stringResource(R.string.ai_chat_regenerate),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "tool" -> {
                                AiToolCard(
                                    name = message.toolName ?: "tool",
                                    arguments = toolArguments[message.toolCallId].orEmpty(),
                                    summary = message.toolSummary,
                                    ok = message.toolOk,
                                    maxWidth = messageMaxWidth
                                )
                            }
                        }
                    }

                    if (streamingVisible) {
                        item {
                            Column {
                                if (viewModel.streamingReasoning.isNotBlank()) {
                                    ReasoningPanel(
                                        text = viewModel.streamingReasoning,
                                        maxWidth = messageMaxWidth,
                                        initiallyExpanded = false
                                    )
                                }
                                AssistantMessageBubble(
                                    content = viewModel.streamingText,
                                    streaming = true,
                                    maxWidth = messageMaxWidth
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        items = viewModel.liveTools,
                        key = { _, tool -> tool.id }
                    ) { _, tool ->
                        AiToolCard(
                            name = tool.name,
                            arguments = tool.arguments,
                            summary = tool.summary,
                            ok = tool.ok,
                            maxWidth = messageMaxWidth
                        )
                    }
                }
            }
        }

        if (viewModel.generating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(
                        R.string.ai_chat_generating_round,
                        viewModel.round.coerceAtLeast(1),
                        viewModel.toolProgress
                            ?: stringResource(R.string.ai_chat_generating_default)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        ChatInputBar(
            value = input,
            onValueChange = { input = it },
            generating = viewModel.generating,
            providerConfigured = activeProvider != null,
            onSubmit = submit,
            onStop = viewModel::stop
        )
    }

    if (showHistory) {
        HistoryDialog(
            sessions = viewModel.sessions,
            currentId = viewModel.current?.id,
            onSelect = { session ->
                viewModel.selectSession(session)
                showHistory = false
            },
            onDelete = { session -> pendingDelete = session },
            onDismiss = { showHistory = false }
        )
    }

    pendingDelete?.let { session ->
        SimpleAlertDialog(
            title = stringResource(R.string.ai_chat_history_delete_title),
            text = stringResource(
                R.string.ai_chat_history_delete_message,
                session.title.ifBlank { stringResource(R.string.ai_chat_history_untitled) }
            ),
            confirmText = stringResource(R.string.generic_delete),
            dismissText = stringResource(R.string.generic_cancel),
            onConfirm = {
                viewModel.deleteSession(session)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    viewModel.confirmRequest?.let { request ->
        SimpleAlertDialog(
            title = request.title.ifBlank { stringResource(R.string.ai_chat_confirm_title) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = request.details.ifBlank { request.toolName },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (request.affectedPaths.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_chat_confirm_affected),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        request.affectedPaths.take(8).forEach { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (request.affectedPaths.size > 8) {
                            Text(
                                text = stringResource(
                                    R.string.ai_chat_confirm_more_files,
                                    request.affectedPaths.size - 8
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.ai_chat_confirm_allow),
            dismissText = stringResource(R.string.ai_chat_confirm_deny),
            onConfirm = { viewModel.answerConfirmation(true) },
            onCancel = { viewModel.answerConfirmation(false) },
            onDismissRequest = { viewModel.answerConfirmation(false) }
        )
    }
}

@Composable
private fun ChatTopBar(
    providerName: String,
    modelName: String,
    providers: List<AiProviderConfig>,
    activeProviderId: String,
    models: List<String>,
    onRefreshProviders: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    mode: ChatMode,
    onSelectMode: (ChatMode) -> Unit,
    onNewSession: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.generic_back)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            val chipLabel = when {
                providerName.isBlank() -> stringResource(R.string.ai_chat_no_provider)
                modelName.isBlank() -> providerName
                else -> "$providerName · $modelName"
            }

            AssistChip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onRefreshProviders()
                    menuExpanded = true
                },
                label = {
                    Text(
                        text = chipLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_drop_down_rounded),
                        contentDescription = stringResource(R.string.ai_chat_model_selector)
                    )
                }
            )

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = stringResource(R.string.ai_chat_section_provider),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                providers.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = provider.name.ifBlank { provider.baseUrl },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = if (provider.id == activeProviderId) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null
                                )
                            }
                        } else null,
                        onClick = {
                            onSelectProvider(provider.id)
                            menuExpanded = false
                        }
                    )
                }
                if (providers.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_chat_no_provider)) },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        }
                    )
                }

                HorizontalDivider()

                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = stringResource(R.string.ai_chat_section_model),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_chat_no_models)) },
                        enabled = false,
                        onClick = {}
                    )
                }
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = if (model == modelName) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null
                                )
                            }
                        } else null,
                        onClick = {
                            onSelectModel(model)
                            menuExpanded = false
                        }
                    )
                }

                HorizontalDivider()

                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_chat_action_settings)) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    }
                )
            }
        }

        Box {
            var modeMenuExpanded by remember { mutableStateOf(false) }
            TextButton(
                onClick = { modeMenuExpanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(mode.labelRes()),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            DropdownMenu(
                expanded = modeMenuExpanded,
                onDismissRequest = { modeMenuExpanded = false }
            ) {
                ChatMode.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.labelRes())) },
                        onClick = {
                            onSelectMode(item)
                            modeMenuExpanded = false
                        }
                    )
                }
            }
        }

        TextButton(
            onClick = onNewSession,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_chat_action_new),
                style = MaterialTheme.typography.labelMedium
            )
        }

        TextButton(
            onClick = onShowHistory,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_chat_action_history),
                style = MaterialTheme.typography.labelMedium
            )
        }

        TextButton(
            onClick = onOpenSettings,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_chat_action_settings),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ChatErrorCard(
    text: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_chat_error_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(stringResource(R.string.ai_chat_error_dismiss))
            }
        }
    }
}

@Composable
private fun NoProviderCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_chat_no_provider),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.ai_chat_no_provider_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onOpenSettings
            ) {
                Text(stringResource(R.string.ai_chat_go_settings))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChatState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.ai_chat_suggestion_1),
        stringResource(R.string.ai_chat_suggestion_2),
        stringResource(R.string.ai_chat_suggestion_3)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.ai_chat_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onSuggestion(suggestion) },
                    label = {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_privacy),
            modifier = Modifier.alpha(0.7f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun UserMessageBubble(
    content: String,
    maxWidth: Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Text(
                modifier = Modifier.padding(12.dp),
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    content: String,
    streaming: Boolean,
    maxWidth: Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            MarkdownBody(
                modifier = Modifier.padding(12.dp),
                content = content,
                streaming = streaming
            )
        }
    }
}

/**
 * 工具卡片：生成中使用 LiveTool 的实时状态，历史记录使用 toolSummary。
 * 参数/结果都限制在 800 字符内，避免把整段原始 JSON 塞进界面。
 */
@Composable
private fun AiToolCard(
    name: String,
    arguments: String,
    summary: String,
    ok: Boolean?,
    maxWidth: Dp
) {
    var expanded by remember(name, arguments) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) -180f else 0f,
        label = "aiToolCardArrow"
    )
    val statusText = when (ok) {
        null -> stringResource(R.string.ai_chat_tool_running)
        true -> stringResource(R.string.ai_chat_tool_success)
        false -> stringResource(R.string.ai_chat_tool_failed)
    }
    val statusColor = when (ok) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
    }
    val shape = MaterialTheme.shapes.large

    Surface(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .clickable { expanded = !expanded },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
                Icon(
                    modifier = Modifier.rotate(rotation),
                    painter = painterResource(R.drawable.ic_arrow_drop_down_rounded),
                    contentDescription = stringResource(
                        if (expanded) R.string.generic_collapse else R.string.generic_expand
                    )
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToolTextSection(
                        label = stringResource(R.string.ai_chat_tool_arguments),
                        value = arguments
                    )
                    ToolTextSection(
                        label = stringResource(R.string.ai_chat_tool_result),
                        value = summary,
                        valueColor = statusColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTextSection(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = valueColor
        ) {
            Text(
                modifier = Modifier.padding(8.dp),
                text = truncateToolText(value).ifEmpty {
                    stringResource(R.string.ai_chat_tool_empty)
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    providerConfigured: Boolean,
    onSubmit: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OwnOutlinedTextField(
                modifier = Modifier.weight(1f),
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.ai_chat_input_placeholder),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = false,
                minLines = 1,
                maxLines = 6,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSubmit() }
                )
            )

            if (generating) {
                FilledTonalButton(onClick = onStop) {
                    Text(stringResource(R.string.ai_chat_action_stop))
                }
            } else {
                Button(
                    onClick = onSubmit,
                    enabled = value.isNotBlank() && providerConfigured
                ) {
                    Text(stringResource(R.string.ai_chat_action_send))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    if (providerConfigured) {
                        R.string.ai_chat_input_hint
                    } else {
                        R.string.ai_chat_input_no_provider_hint
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .alpha(0.7f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.ai_chat_input_counter, value.length),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryDialog(
    sessions: List<ChatSession>,
    currentId: String?,
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.ai_chat_history_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.generic_close)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (sessions.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        text = stringResource(R.string.ai_chat_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = sessions,
                            key = { _, session -> session.id }
                        ) { _, session ->
                            val isCurrent = session.id == currentId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .clickable { onSelect(session) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            modifier = Modifier.weight(1f),
                                            text = session.title.ifBlank {
                                                stringResource(R.string.ai_chat_history_untitled)
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isCurrent) {
                                            Text(
                                                text = stringResource(R.string.ai_chat_history_current),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = remember(session.updatedAt) {
                                            formatter.format(Date(session.updatedAt))
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = session.preview().ifBlank {
                                            stringResource(R.string.ai_chat_history_preview_empty)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = { onDelete(session) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_outlined),
                                        contentDescription = stringResource(R.string.generic_delete)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Markdown

private sealed interface MarkdownBlock {
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val indent: Int, val marker: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

@Composable
private fun MarkdownBody(
    content: String,
    streaming: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val blocks = remember(content, streaming) {
        if (streaming) appendStreamingCursor(parseMarkdown(content)) else parseMarkdown(content)
    }
    val onOpenLink: (String) -> Unit = { url ->
        runCatching { uriHandler.openUri(url) }
        Unit
    }

    androidx.compose.foundation.text.selection.SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Code -> CodeBlock(
                        code = block.code,
                        language = block.language
                    )

                    is MarkdownBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = inlineMarkdown(block.text, colorScheme, onOpenLink),
                            style = style.copy(
                                color = colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    is MarkdownBlock.Bullet -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indent * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = block.marker,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            modifier = Modifier.weight(1f),
                            text = inlineMarkdown(block.text, colorScheme, onOpenLink),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MarkdownBlock.Quote -> Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = inlineMarkdown(block.text, colorScheme, onOpenLink),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )

                    is MarkdownBlock.Paragraph -> Text(
                        text = inlineMarkdown(block.text, colorScheme, onOpenLink),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    code: String,
    language: String
) {
    val clipboard = LocalClipboardManager.current
    val colorScheme = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHighest,
        contentColor = colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = language.ifBlank { stringResource(R.string.ai_chat_code_block) },
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    onClick = {
                        clipboard.setText(AnnotatedString(code.removeSuffix("▌")))
                        copied = true
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (copied) R.string.generic_copied else R.string.generic_copy
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 轻量 Markdown：**粗体**、`行内代码`、标题、列表、引用、可点击链接、围栏代码块。
 * 代码块由 [CodeBlock] 单独渲染，因此每个代码块都有复制按钮。
 */
private fun parseMarkdown(content: String): List<MarkdownBlock> {
    val lines = content.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(text)
        }
        paragraph.clear()
    }

    val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    val bulletRegex = Regex("""^(\s*)[-*+]\s+(.*)$""")
    val orderedRegex = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    val quoteRegex = Regex("""^\s{0,3}>\s?(.*)$""")

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val language = trimmed.removePrefix("```").trim().substringBefore(' ')
            index++
            val code = StringBuilder()
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index++
            }
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(
                language = language,
                code = code.toString()
            )
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            index++
            continue
        }

        val heading = headingRegex.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length,
                text = heading.groupValues[2].trim()
            )
            index++
            continue
        }

        val bullet = bulletRegex.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(
                indent = (bullet.groupValues[1].length / 2).coerceAtMost(4),
                marker = "•",
                text = bullet.groupValues[2].trim()
            )
            index++
            continue
        }

        val ordered = orderedRegex.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(
                indent = (ordered.groupValues[1].length / 2).coerceAtMost(4),
                marker = "${ordered.groupValues[2]}.",
                text = ordered.groupValues[3].trim()
            )
            index++
            continue
        }

        val quote = quoteRegex.matchEntire(line)
        if (quote != null) {
            flushParagraph()
            blocks += MarkdownBlock.Quote(quote.groupValues[1].trim())
            index++
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(trimmed)
        index++
    }
    flushParagraph()
    return blocks
}

private fun inlineMarkdown(
    text: String,
    colorScheme: androidx.compose.material3.ColorScheme,
    onOpenLink: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    val pattern = Regex(
        """\[([^\]]+)]\(([^)\s]+)\)|`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~"""
    )
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        when {
            match.groupValues[1].isNotEmpty() -> {
                val label = match.groupValues[1]
                val url = match.groupValues[2]
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = colorScheme.secondary,
                                textDecoration = TextDecoration.Underline
                            )
                        ),
                        linkInteractionListener = { onOpenLink(url) }
                    )
                ) {
                    append(label)
                }
            }

            match.groupValues[3].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant,
                        background = colorScheme.surfaceVariant
                    )
                ) {
                    append(match.groupValues[3])
                }
            }

            match.groupValues[4].isNotEmpty() -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[4])
                }
            }

            match.groupValues[5].isNotEmpty() -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[5])
                }
            }

            match.groupValues[6].isNotEmpty() -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(match.groupValues[6])
                }
            }

            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private fun appendStreamingCursor(blocks: List<MarkdownBlock>): List<MarkdownBlock> {
    if (blocks.isEmpty()) {
        return listOf(MarkdownBlock.Paragraph("▌"))
    }
    val last = blocks.last()
    val updated = when (last) {
        is MarkdownBlock.Code -> last.copy(code = last.code + "▌")
        is MarkdownBlock.Heading -> last.copy(text = last.text + "▌")
        is MarkdownBlock.Bullet -> last.copy(text = last.text + "▌")
        is MarkdownBlock.Quote -> last.copy(text = last.text + "▌")
        is MarkdownBlock.Paragraph -> last.copy(text = last.text + "▌")
    }
    return blocks.dropLast(1) + updated
}

private fun truncateToolText(text: String, maxLength: Int = 800): String {
    val trimmed = text.trim()
    return if (trimmed.length <= maxLength) {
        trimmed
    } else {
        trimmed.take(maxLength) + "…"
    }
}


private fun ChatMode.labelRes(): Int = when (this) {
    ChatMode.CHAT -> R.string.ai_mode_chat
    ChatMode.WEB -> R.string.ai_mode_web
    ChatMode.AUTO -> R.string.ai_mode_auto
}


@Composable
private fun ReasoningPanel(
    text: String,
    maxWidth: Dp,
    initiallyExpanded: Boolean = false
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.widthIn(max = maxWidth)) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = stringResource(
                    if (expanded) R.string.ai_chat_reasoning_hide else R.string.ai_chat_reasoning_show
                ),
                style = MaterialTheme.typography.labelSmall
            )
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = text,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
