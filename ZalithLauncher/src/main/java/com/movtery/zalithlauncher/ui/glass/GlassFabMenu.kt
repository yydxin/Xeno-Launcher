@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.movtery.zalithlauncher.ui.glass

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButtonMenuScope
import androidx.compose.material3.ToggleFloatingActionButtonScope
import androidx.compose.material3.FloatingActionButtonMenuItem as MaterialFloatingActionButtonMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Material retains expansion, morphing, menu positioning and all interaction semantics. */
@Composable
fun GlassToggleFloatingActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ToggleFloatingActionButtonScope.() -> Unit
) {
    if (isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current) {
        val radius = LocalGlassConfig.current.cornerRadius.dp
        androidx.compose.material3.ToggleFloatingActionButton(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier.glassScopeSurface(true, CircleShape),
            containerColor = { Color.Transparent }, containerCornerRadius = { radius }, content = content
        )
    } else {
        androidx.compose.material3.ToggleFloatingActionButton(
            checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, content = content
        )
    }
}

@Composable
fun FloatingActionButtonMenuScope.GlassFloatingActionButtonMenuItem(
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isGlassActive(GlassScope.Buttons) && !LocalInsideGlassBar.current) {
        MaterialFloatingActionButtonMenuItem(
            onClick = onClick, text = text, icon = icon,
            modifier = modifier.glassScopeSurface(true, CircleShape),
            containerColor = Color.Transparent
        )
    } else {
        MaterialFloatingActionButtonMenuItem(onClick = onClick, text = text, icon = icon, modifier = modifier)
    }
}
