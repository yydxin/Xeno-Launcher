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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Material-compatible button entry points. Only the container is replaced in glass mode;
 * Material still owns content/disabled colors, input semantics, ripple and interactions.
 * The shared scope gate also observes the global glass state and the buttons setting.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val glassEnabled = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.glassScopeSurface(glassEnabled, shape),
        enabled = enabled,
        shape = glassShape(shape, glassEnabled),
        colors = colors.withGlassContainer(glassEnabled),
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun GlassFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = GlassButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)

@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = GlassButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)

@Composable
fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = GlassButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)

@Composable
fun GlassElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.elevatedShape,
    colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.elevatedButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = GlassButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content
)

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = IconButtonDefaults.standardShape,
    content: @Composable () -> Unit
) {
    val glassEnabled = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.glassScopeSurface(glassEnabled, shape),
        enabled = enabled,
        colors = colors.withGlassContainer(glassEnabled),
        interactionSource = interactionSource,
        shape = glassShape(shape, glassEnabled),
        content = content
    )
}

@Composable
fun GlassFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val glassEnabled = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = modifier.glassScopeSurface(glassEnabled, shape),
        enabled = enabled,
        shape = glassShape(shape, glassEnabled),
        colors = colors.withGlassContainer(glassEnabled),
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun GlassFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val glassEnabled = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.glassScopeSurface(glassEnabled, shape),
        enabled = enabled,
        shape = glassShape(shape, glassEnabled),
        colors = colors.withGlassContainer(glassEnabled),
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun GlassOutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.outlinedShape,
    colors: IconButtonColors = IconButtonDefaults.outlinedIconButtonColors(),
    border: BorderStroke? = IconButtonDefaults.outlinedIconButtonBorder(enabled),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val glassEnabled = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current
    androidx.compose.material3.OutlinedIconButton(
        onClick = onClick,
        modifier = modifier.glassScopeSurface(glassEnabled, shape),
        enabled = enabled,
        shape = glassShape(shape, glassEnabled),
        colors = colors.withGlassContainer(glassEnabled),
        border = border,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
private fun ButtonColors.withGlassContainer(enabled: Boolean): ButtonColors =
    if (enabled) copy(
        containerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        // onPrimary is only readable over an opaque primary container.
        contentColor = if (contentColor == MaterialTheme.colorScheme.onPrimary)
            MaterialTheme.colorScheme.onSurface else contentColor
    ) else this

@Composable
private fun IconButtonColors.withGlassContainer(enabled: Boolean): IconButtonColors =
    if (enabled) copy(
        containerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        contentColor = if (contentColor == MaterialTheme.colorScheme.onPrimary)
            MaterialTheme.colorScheme.onSurface else contentColor
    ) else this
