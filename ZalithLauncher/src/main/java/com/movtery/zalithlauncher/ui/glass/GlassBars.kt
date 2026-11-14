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

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material owns layout, insets, scrolling, interactions and accessibility in both modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val glass = isGlassActive(GlassScope.TopBar)
    androidx.compose.material3.TopAppBar(
        title = { BarGlassScope { title() } },
        modifier = modifier.glassScopeSurface(glass, RectangleShape),
        navigationIcon = { BarGlassScope { navigationIcon() } },
        actions = { BarGlassScope { actions() } },
        expandedHeight = expandedHeight,
        windowInsets = windowInsets,
        // Both states must be transparent: an opaque scrolled color hides the backdrop.
        colors = if (glass) colors.copy(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ) else colors,
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun GlassNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
    colors: NavigationRailItemColors = NavigationRailItemDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) {
    // The rail/drawer container already provides the glass; items never add a second layer.
    androidx.compose.material3.NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        modifier = modifier,
        enabled = enabled,
        label = label,
        alwaysShowLabel = alwaysShowLabel,
        colors = colors,
        interactionSource = interactionSource
    )
}

@Composable
fun GlassNavigationDrawerItem(
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    shape: Shape = CircleShape,
    colors: NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) {
    androidx.compose.material3.NavigationDrawerItem(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        badge = badge,
        shape = shape,
        colors = colors,
        interactionSource = interactionSource
    )
}

/**
 * Uses the real Material host, preserving its queue, timeout, transitions and a11y semantics.
 * A supplied snackbar slot remains entirely caller-owned, just as in Material.
 */
@Composable
fun GlassSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { GlassHostSnackbar(it) }
) {
    androidx.compose.material3.SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = snackbar
    )
}

@Composable
private fun GlassHostSnackbar(data: SnackbarData) {
    val glass = isGlassActive(GlassScope.Cards)
    if (!glass) {
        androidx.compose.material3.Snackbar(snackbarData = data)
        return
    }

    val shape = SnackbarDefaults.shape
    // The data-based Material Snackbar adds 12dp external padding internally. Draw only
    // behind its actual surface, not across that margin or across an empty host.
    Box {
        Box(
            Modifier
                .matchParentSize()
                .padding(12.dp)
                .glassScopeSurface(glass, glassShape(shape, glass))
        )
        androidx.compose.material3.Snackbar(
            snackbarData = data,
            shape = glassShape(shape, glass),
            containerColor = Color.Transparent,
            // Inverse-surface text is unsuitable once the inverse opaque container is gone.
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.primary,
            actionContentColor = MaterialTheme.colorScheme.primary,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val glass = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        modifier = modifier.glassScopeSurface(glass, shape),
        shape = glassShape(shape, glass),
        containerColor = if (glass) Color.Transparent else containerColor,
        contentColor = contentColor,
        elevation = if (glass) FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp
        ) else elevation,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
private fun BarGlassScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInsideGlassBar provides true, content = content)
}

/** Preserve custom icon/text/badge colors; only the background is glass-aware. */
private class GlassDrawerItemColors(
    private val original: NavigationDrawerItemColors
) : NavigationDrawerItemColors by original {
    @Composable
    override fun containerColor(selected: Boolean): State<Color> {
        val color = original.containerColor(selected).value
        return rememberUpdatedState(
            if (selected) color.glassIndicatorColor() else Color.Transparent
        )
    }
}

/** Multiply instead of replacing alpha, so an intentionally transparent indicator stays so. */
private fun Color.glassIndicatorColor(): Color = copy(alpha = alpha * 0.18f)
