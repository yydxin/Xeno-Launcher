/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.movtery.zalithlauncher.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.RichTooltipColors
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipScope
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ExposedDropdownMenu as MaterialExposedDropdownMenu
import androidx.compose.material3.PlainTooltip as MaterialPlainTooltip
import androidx.compose.material3.RichTooltip as MaterialRichTooltip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** A handle only, never a main-window GraphicsLayer passed into another window. */
@Stable
private class WindowGlassState {
    var backdrop by mutableStateOf<LayerBackdrop?>(null)
}

/**
 * Material owns the actual window, position, animation, insets and dismiss logic. This modifier
 * is materialized on its layout INSIDE that window, so rememberGraphicsLayer uses that window's
 * GraphicsContext, not the caller's. Its source drawing node records only the wallpaper pixels;
 * it never records drawContent(), the dialog, its buttons, the scrim or another window's layer.
 *
 * There is deliberately no Image/background draw to the window canvas. The wallpaper is recorded
 * offscreen before the surface draws. Thus even a full-screen sheet does not cover its scrim with
 * a rectangular copy of the wallpaper. layerBackdrop supplies local coordinates and its custom
 * onDraw records the same wallpaper-only source, without replaying it onto the window canvas.
 */
private fun Modifier.independentWindowSurface(
    state: WindowGlassState,
    shape: Shape,
    surfaceEnabled: Boolean
): Modifier = composed {
    val globallyEnabled = LocalGlassEnabled.current
    val tier = LocalGlassTier.current
    val wallpaper = LocalGlassWallpaper.current
    if (!globallyEnabled || tier == GlassTier.FROST) {
        SideEffect { state.backdrop = null }
        return@composed if (globallyEnabled && surfaceEnabled) frostedSurface(shape) else this
    }

    val geometry = wallpaper?.geometry
    val snapshot = remember(wallpaper, geometry) { mutableStateOf<GlassWallpaperSnapshot?>(null) }
    val screenOrigin = remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(wallpaper, geometry) {
        snapshot.value = try {
            wallpaper?.snapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
    val drawWallpaper: ContentDrawScope.() -> Unit = remember(snapshot, screenOrigin) {
        {
            val image = snapshot.value
            val origin = screenOrigin.value
            if (image != null && origin != null) {
                // Both are physical screen pixels: do not scale a cropped wallpaper to the popup.
                drawImage(image.image, topLeft = image.origin - origin)
            }
        }
    }
    val backdrop = rememberLayerBackdrop(onDraw = drawWallpaper)
    val ready = snapshot.value != null && screenOrigin.value != null
    SideEffect { state.backdrop = if (ready) backdrop else null }
    DisposableEffect(backdrop) {
        onDispose { if (state.backdrop === backdrop) state.backdrop = null }
    }

    val source = this
        .onGloballyPositioned { coordinates ->
            screenOrigin.value = coordinates.positionOnScreen().takeIf {
                it.x.isFinite() && it.y.isFinite()
            }
        }
        .drawWithContent {
            // Record BEFORE drawing consumers. No empty-layer first frame and no feedback loop.
            // This draw node is a source, not a visible rectangular wallpaper background.
            val image = snapshot.value
            val origin = screenOrigin.value
            if (image != null && origin != null && size.width > 0f && size.height > 0f) {
                backdrop.graphicsLayer.record(
                    size = IntSize(size.width.roundToInt(), size.height.roundToInt())
                ) {
                    drawImage(image.image, topLeft = image.origin - origin)
                }
            }
            drawContent()
        }
        .layerBackdrop(backdrop)

    if (!surfaceEnabled) source
    else if (!ready) source.frostedSurface(shape)
    else source.glassSurface(shape = shape, backdrop = backdrop)
}

/** No layout wrapper: ColumnScope/RowScope, menu intrinsics and sheet weights remain unchanged. */
@Composable
private fun WindowGlassLocals(state: WindowGlassState, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGlassBackdrop provides state.backdrop,
        LocalGlassSurfaceScope provides GlassScope.Dialogs,
        content = content
    )
}

private fun windowSlot(
    state: WindowGlassState,
    content: (@Composable () -> Unit)?
): (@Composable () -> Unit)? = if (content == null) null else {
    { WindowGlassLocals(state, content) }
}

/** Generic Dialog content host; the source is local to the content window, never the caller. */
@Composable
fun GlassWindowBackdropHost(content: @Composable () -> Unit) {
    val state = remember { WindowGlassState() }
    Box(
        modifier = Modifier.independentWindowSurface(state, RectangleShape, surfaceEnabled = false),
        propagateMinConstraints = true
    ) {
        WindowGlassLocals(state, content)
    }
}

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        if (LocalGlassEnabled.current) GlassWindowBackdropHost(content)
        else content()
    }
}

/** Material's full alert-dialog signature and native layout; snapshot completion never swaps windows. */
@Composable
fun GlassMaterialAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    val glass = isGlassActive(GlassScope.Dialogs)
    val effectiveShape = glassShape(shape, glass)
    val state = remember { WindowGlassState() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { WindowGlassLocals(state, confirmButton) },
        modifier = modifier.independentWindowSurface(state, effectiveShape, glass),
        dismissButton = windowSlot(state, dismissButton),
        icon = windowSlot(state, icon),
        title = windowSlot(state, title),
        text = windowSlot(state, text),
        shape = effectiveShape,
        containerColor = if (glass) Color.Transparent else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        properties = properties
    )
}

/** Compatibility with the old Material arbitrary-content AlertDialog overload. */
@Composable
fun GlassMaterialAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties
    ) {
        if (LocalGlassEnabled.current) GlassWindowBackdropHost(content)
        else content()
    }
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = MenuDefaults.DefaultMenuProperties,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = isGlassActive(GlassScope.Dialogs)
    val effectiveShape = glassShape(shape, glass)
    val state = remember { WindowGlassState() }
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.independentWindowSurface(state, effectiveShape, glass),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = effectiveShape,
        containerColor = if (glass) Color.Transparent else containerColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        border = border
    ) {
        val columnScope = this
        WindowGlassLocals(state) { content(columnScope) }
    }
}

/** Delegating the native extension retains anchor type, focusability, keyboard and popup placement. */
@Composable
fun ExposedDropdownMenuBoxScope.GlassExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    matchAnchorWidth: Boolean = true,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = isGlassActive(GlassScope.Dialogs)
    val effectiveShape = glassShape(shape, glass)
    val state = remember { WindowGlassState() }
    MaterialExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.independentWindowSurface(state, effectiveShape, glass),
        scrollState = scrollState,
        matchAnchorWidth = matchAnchorWidth,
        shape = effectiveShape,
        containerColor = if (glass) Color.Transparent else containerColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        border = border
    ) {
        val columnScope = this
        WindowGlassLocals(state) { content(columnScope) }
    }
}

@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = isGlassActive(GlassScope.Dialogs)
    val effectiveShape = glassShape(shape, glass)
    val state = remember { WindowGlassState() }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.independentWindowSurface(state, effectiveShape, glass),
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = effectiveShape,
        containerColor = if (glass) Color.Transparent else containerColor,
        contentColor = contentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        scrimColor = scrimColor,
        dragHandle = windowSlot(state, dragHandle),
        contentWindowInsets = contentWindowInsets,
        properties = properties
    ) {
        val columnScope = this
        WindowGlassLocals(state) { content(columnScope) }
    }
}

@Composable
fun TooltipScope.GlassRichTooltip(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    caretShape: Shape? = null,
    maxWidth: Dp = TooltipDefaults.richTooltipMaxWidth,
    shape: Shape = TooltipDefaults.richTooltipContainerShape,
    colors: RichTooltipColors = TooltipDefaults.richTooltipColors(),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 3.dp,
    text: @Composable () -> Unit
) {
    // Material's dynamic caret outline is private; retain its native rendering when requested.
    // Current launcher call sites do not request carets.
    val glass = isGlassActive(GlassScope.Dialogs) && caretShape == null
    val effectiveShape = glassShape(shape, glass)
    val state = remember { WindowGlassState() }
    MaterialRichTooltip(
        modifier = modifier.independentWindowSurface(state, effectiveShape, glass),
        title = windowSlot(state, title),
        action = windowSlot(state, action),
        caretShape = caretShape,
        maxWidth = maxWidth,
        shape = effectiveShape,
        colors = if (glass) colors.copy(containerColor = Color.Transparent) else colors,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        text = { WindowGlassLocals(state, text) }
    )
}

@Composable
fun TooltipScope.GlassPlainTooltip(
    modifier: Modifier = Modifier,
    caretShape: Shape? = null,
    maxWidth: Dp = TooltipDefaults.plainTooltipMaxWidth,
    shape: Shape = TooltipDefaults.plainTooltipContainerShape,
    contentColor: Color = TooltipDefaults.plainTooltipContentColor,
    containerColor: Color = TooltipDefaults.plainTooltipContainerColor,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    // Keep a requested Material caret intact rather than clipping it to the glass body outline.
    val glass = isGlassActive(GlassScope.Dialogs) && caretShape == null
    val effectiveShape = glassShape(shape, glass)
    val state = remember { WindowGlassState() }
    MaterialPlainTooltip(
        modifier = modifier.independentWindowSurface(state, effectiveShape, glass),
        caretShape = caretShape,
        maxWidth = maxWidth,
        shape = effectiveShape,
        contentColor = contentColor,
        containerColor = if (glass) Color.Transparent else containerColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = shadowElevation,
        content = { WindowGlassLocals(state, content) }
    )
}
