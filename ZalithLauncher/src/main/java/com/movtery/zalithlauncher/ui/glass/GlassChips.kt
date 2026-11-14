/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.movtery.zalithlauncher.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ChipElevation
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MultiChoiceSegmentedButtonRowScope
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.SegmentedButton as MaterialSegmentedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Material 1.5.0-alpha29 shape overload, including its arrangement/padding parameters. */
@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
    horizontalArrangement: Arrangement.Horizontal = FilterChipDefaults.horizontalArrangement(),
    contentPadding: PaddingValues = FilterChipDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null
) {
    // Disabled chips retain the complete Material disabled palette, outline and shape.
    val glass = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current && enabled
    val effectiveShape = glassShape(shape, glass)
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.glassScopeSurface(glass, effectiveShape),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = effectiveShape,
        colors = if (glass) colors.copy(
            containerColor = Color.Transparent,
            selectedContainerColor = selectionTint(colors.selectedContainerColor)
        ) else colors,
        elevation = elevation,
        border = border,
        horizontalArrangement = horizontalArrangement,
        contentPadding = contentPadding,
        interactionSource = interactionSource
    )
}

@Composable
fun GlassAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = AssistChipDefaults.shape,
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
    elevation: ChipElevation? = AssistChipDefaults.assistChipElevation(),
    border: BorderStroke? = AssistChipDefaults.assistChipBorder(enabled),
    horizontalArrangement: Arrangement.Horizontal = AssistChipDefaults.horizontalArrangement(),
    contentPadding: PaddingValues = AssistChipDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null
) {
    val glass = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current && enabled
    val effectiveShape = glassShape(shape, glass)
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier.glassScopeSurface(glass, effectiveShape),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = effectiveShape,
        colors = if (glass) colors.copy(containerColor = Color.Transparent) else colors,
        elevation = elevation,
        border = border,
        horizontalArrangement = horizontalArrangement,
        contentPadding = contentPadding,
        interactionSource = interactionSource
    )
}

@Composable
fun SingleChoiceSegmentedButtonRowScope.GlassSegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SegmentedButtonColors = SegmentedButtonDefaults.colors(),
    border: BorderStroke = SegmentedButtonDefaults.borderStroke(
        color = colors.materialBorderColor(enabled, selected)
    ),
    contentPadding: PaddingValues = SegmentedButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit = { SegmentedButtonDefaults.Icon(selected) },
    label: @Composable () -> Unit
) {
    val glass = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current && enabled
    val effectiveShape = glassShape(shape, glass)
    MaterialSegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = effectiveShape,
        modifier = modifier.glassScopeSurface(glass, effectiveShape),
        enabled = enabled,
        colors = colors.withGlassContainers(glass),
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        icon = icon,
        label = label
    )
}

@Composable
fun MultiChoiceSegmentedButtonRowScope.GlassSegmentedButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SegmentedButtonColors = SegmentedButtonDefaults.colors(),
    border: BorderStroke = SegmentedButtonDefaults.borderStroke(
        color = colors.materialBorderColor(enabled, checked)
    ),
    contentPadding: PaddingValues = SegmentedButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit = { SegmentedButtonDefaults.Icon(checked) },
    label: @Composable () -> Unit
) {
    val glass = isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current && enabled
    val effectiveShape = glassShape(shape, glass)
    MaterialSegmentedButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shape = effectiveShape,
        modifier = modifier.glassScopeSurface(glass, effectiveShape),
        enabled = enabled,
        colors = colors.withGlassContainers(glass),
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        icon = icon,
        label = label
    )
}

/** Keep the caller's selection hue/alpha, capped so it cannot hide the glass backdrop. */
private fun selectionTint(color: Color): Color =
    if (color == Color.Unspecified) Color.Transparent
    else color.copy(alpha = color.alpha.coerceAtMost(0.18f))

private fun SegmentedButtonColors.withGlassContainers(enabled: Boolean): SegmentedButtonColors =
    if (enabled) copy(
        activeContainerColor = selectionTint(activeContainerColor),
        inactiveContainerColor = Color.Transparent
    ) else this

/** Public color fields reproduce Material's internal borderColor(enabled, active) selector. */
private fun SegmentedButtonColors.materialBorderColor(enabled: Boolean, active: Boolean): Color =
    when {
        enabled && active -> activeBorderColor
        enabled -> inactiveBorderColor
        active -> disabledActiveBorderColor
        else -> disabledInactiveBorderColor
    }
