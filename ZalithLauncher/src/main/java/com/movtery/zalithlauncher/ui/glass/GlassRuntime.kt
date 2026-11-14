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

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.collectLatest

/** 只限制背景重新录制；内容布局、触摸与滚动仍按系统帧率运行。 */
internal class GlassFrameLimiter {
    private var lastRecorded = Long.MIN_VALUE

    fun shouldRecord(now: Long, throttled: Boolean): Boolean {
        if (!throttled || lastRecorded == Long.MIN_VALUE || now - lastRecorded >= 33_000_000L) {
            lastRecorded = now
            return true
        }
        return false
    }
}

@Stable
internal class GlassScrollRuntime : NestedScrollConnection {
    var lastScrollNanos by mutableLongStateOf(0L)
    var frameNanos by mutableLongStateOf(0L)

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed != Offset.Zero || available != Offset.Zero) {
            lastScrollNanos = SystemClock.elapsedRealtimeNanos()
        }
        return Offset.Zero
    }
}

private val LocalGlassScroll = staticCompositionLocalOf<GlassScrollRuntime?> { null }

@Composable
fun GlassRuntimeHost(enabled: Boolean, content: @Composable () -> Unit) {
    RegisterNativeGlassBridge()
    val runtime = remember { GlassScrollRuntime() }
    val throttle = enabled && LocalGlassConfig.current.throttleWhileScrolling
    LaunchedEffect(runtime, throttle) {
        if (throttle) snapshotFlow { runtime.lastScrollNanos }.collectLatest { last ->
            if (last != 0L) {
                // 连续拖动和惯性滚动均会刷新 last；静止后补一帧恢复全速。
                do {
                    withFrameNanos { runtime.frameNanos = it }
                } while (SystemClock.elapsedRealtimeNanos() - runtime.lastScrollNanos < 120_000_000L)
            }
        }
    }
    CompositionLocalProvider(LocalGlassScroll provides runtime) {
        Box(Modifier.fillMaxSize().then(if (throttle) Modifier.nestedScroll(runtime) else Modifier)) {
            content()
            GlassToastHost()
        }
    }
}

/** 必须只挂在纯背景节点；缓存层与采样该背景的玻璃表面不能互相嵌套。 */
@Composable
fun Modifier.throttledGlassBackground(backdrop: LayerBackdrop): Modifier {
    val runtime = LocalGlassScroll.current
    val throttle = LocalGlassConfig.current.throttleWhileScrolling
    val wallpaper = LocalGlassWallpaper.current
    val cache = rememberGraphicsLayer()
    DisposableEffect(wallpaper, cache) {
        wallpaper?.layer = cache
        onDispose { wallpaper?.let { if (it.layer === cache) it.layer = null } }
    }
    val limiter = remember(cache) { GlassFrameLimiter() }
    var recordedSize = remember(cache) { IntSize.Zero }
    return onGloballyPositioned {
        wallpaper?.geometry = androidx.compose.ui.geometry.Rect(it.positionOnScreen(),
            androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.drawWithContent {
        runtime?.frameNanos // 只订阅绘制失效，不触发页面重组。
        val now = SystemClock.elapsedRealtimeNanos()
        val scrolling = throttle && runtime != null && runtime.lastScrollNanos != 0L &&
                now - runtime.lastScrollNanos < 120_000_000L
        val currentSize = IntSize(size.width.toInt(), size.height.toInt())
        if (recordedSize != currentSize || limiter.shouldRecord(now, scrolling)) {
            cache.record { this@drawWithContent.drawContent() }
            recordedSize = currentSize
        }
        drawLayer(cache)
    }.layerBackdrop(backdrop)
}
