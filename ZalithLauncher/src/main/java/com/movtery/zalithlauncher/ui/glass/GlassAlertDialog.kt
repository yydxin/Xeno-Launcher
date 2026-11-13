/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.movtery.zalithlauncher.setting.AllSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** 只复制宿主窗口；绝不复制新对话框窗口，也不把截图写到磁盘。 */
private suspend fun capturePage(view: View): ImageBitmap? = suspendCancellableCoroutine { continuation ->
    val activity = view.context.activity()
    if (activity == null || view.width <= 0 || view.height <= 0) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val location = IntArray(2).also(view::getLocationInWindow)
    val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    try {
        PixelCopy.request(activity.window, rect, bitmap, { result ->
            if (result == PixelCopy.SUCCESS && continuation.isActive) {
                continuation.resume(bitmap.asImageBitmap())
            } else {
                bitmap.recycle()
                if (continuation.isActive) continuation.resume(null)
            }
        }, Handler(Looper.getMainLooper()))
    } catch (_: IllegalArgumentException) {
        bitmap.recycle()
        if (continuation.isActive) continuation.resume(null)
    }
}

/** 通用确认框：在窗口内重建当前页面的真实像素，再用独立 backdrop 折射。 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    val eligible = LocalGlassEnabled.current && AllSettings.glassScopeDialogs.state &&
            Build.VERSION.SDK_INT >= 31 && LocalGlassTier.current != GlassTier.FROST
    val view = LocalView.current
    val windowSize = LocalWindowInfo.current.containerSize
    var snapshot by remember(view, windowSize, eligible) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(view, windowSize, eligible) {
        if (eligible) snapshot = capturePage(view)
    }
    val page = snapshot
    if (!eligible || page == null) {
        // 不支持或 PixelCopy 失败时保留可靠的 Material 对话框，不能伪装成玻璃。
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton
        )
        return
    }

    val backdrop = rememberLayerBackdrop()
    val config = LocalGlassConfig.current
    val shape = MaterialTheme.shapes.extraLarge
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 录制背景、压暗遮罩、玻璃卡片是兄弟；卡片不会录制自己。
            Image(
                bitmap = page,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).blur(3.dp)
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } })
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Surface(
                    modifier = Modifier.safeDrawingPadding().padding(24.dp).widthIn(max = 560.dp)
                        .fillMaxWidth().glassSurface(shape, config),
                    shape = shape,
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { title() }
                        Box(Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) { text() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
