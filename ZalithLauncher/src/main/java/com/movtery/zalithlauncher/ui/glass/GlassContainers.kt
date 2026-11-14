package com.movtery.zalithlauncher.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One rendering path for cards, custom surfaces and independent-window contents. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    scope: GlassScope = LocalGlassSurfaceScope.current,
    content: @Composable () -> Unit
) {
    val glass = isGlassActive(scope)
    androidx.compose.material3.Surface(
        modifier = modifier.glassScopeSurface(glass, shape), shape = glassShape(shape, glass),
        color = if (glass) Color.Transparent else color, contentColor = contentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = if (glass) 0.dp else shadowElevation, border = border, content = content
    )
}

@Composable
fun GlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    scope: GlassScope = LocalGlassSurfaceScope.current,
    content: @Composable () -> Unit
) {
    val glass = isGlassActive(scope)
    androidx.compose.material3.Surface(
        onClick = onClick, modifier = modifier.glassScopeSurface(glass, shape), enabled = enabled,
        shape = glassShape(shape, glass), color = if (glass) Color.Transparent else color, contentColor = contentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = if (glass) 0.dp else shadowElevation, border = border,
        interactionSource = interactionSource, content = content
    )
}

@Composable
fun GlassSurface(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val glass = isGlassActive(LocalGlassSurfaceScope.current)
    androidx.compose.material3.Surface(
        selected = selected, onClick = onClick, modifier = modifier.glassScopeSurface(glass, shape),
        enabled = enabled, shape = glassShape(shape, glass), color = if (glass) Color.Transparent else color,
        contentColor = contentColor, tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = if (glass) 0.dp else shadowElevation, border = border,
        interactionSource = interactionSource, content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = isGlassActive(LocalGlassSurfaceScope.current)
    androidx.compose.material3.Card(
        modifier = modifier.glassScopeSurface(glass, shape), shape = glassShape(shape, glass),
        colors = if (glass) colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent) else colors,
        elevation = if (glass) CardDefaults.cardElevation() else elevation, border = border, content = content
    )
}

@Composable
fun GlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = isGlassActive(LocalGlassSurfaceScope.current)
    androidx.compose.material3.Card(
        onClick = onClick, modifier = modifier.glassScopeSurface(glass, shape), enabled = enabled,
        shape = glassShape(shape, glass), colors = if (glass) colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent) else colors,
        elevation = if (glass) CardDefaults.cardElevation() else elevation, border = border,
        interactionSource = interactionSource, content = content
    )
}
