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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import com.movtery.zalithlauncher.setting.AllSettings
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/** 当前生效的档位（由 MainActivity 的 Provider 计算后下发） */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.FROST }

/**
 * 给任意表面套上玻璃：把 backdrop 采样后按当前参数做 模糊 → 折射（仅 AGSL）→ 染色。
 *
 * - 档位 [GlassTier.FROST] 时返回 [Modifier]（调用方走磨砂降级）；
 * - 没有 backdrop 时同理，绝不崩溃；
 * - 返回的 Modifier 已按 [shape] 裁剪，可直接贴到 Card/Box 的内容层上。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    config: GlassConfig = LocalGlassConfig.current,
    tint: Color? = null,
    tier: GlassTier = LocalGlassTier.current,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = LocalGlassBackdrop.current
): Modifier {
    backdrop ?: return this
    if (tier == GlassTier.FROST) return this

    val fill = tint ?: Color(config.tintColor)

    return this
        .clip(shape)
        .then(GlassPositionInvalidation)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            highlight = {
                if (config.highlightIntensity > 0f && config.borderWidth > 0f) Highlight(
                    width = config.borderWidth.dp,
                    alpha = (config.highlightIntensity / 2f).coerceIn(0f, 1f)
                ) else null
            },
            shadow = { if (config.shadowRadius > 0f) Shadow(radius = config.shadowRadius.dp) else null },
            effects = {
                blur(config.blurRadius.dp.toPx())
                // lens 只支持 CornerBasedShape / RoundedRectangularShape；其它形状传进去会在
                // 绘制阶段抛 UnsupportedOperationException（整个进程闪退），所以这里再兜一层。
                if (tier == GlassTier.AGSL && shape is CornerBasedShape) {
                    lens(
                        refractionHeight = config.refractionHeight.dp.toPx(),
                        refractionAmount = config.refractionAmount,
                        depthEffect = config.depthEffect,
                        chromaticAberration = config.chromaticAberration
                    )
                }
            },
            onDrawSurface = {
                drawRect(color = fill.copy(alpha = config.tintAlpha))
            }
        )
}

/**
 * 磨砂降级：低版本（<31）没有 RenderEffect 时，用半透明染色 + 描边模拟毛玻璃。
 */
@Composable
fun Modifier.frostedSurface(shape: Shape, config: GlassConfig = LocalGlassConfig.current): Modifier {
    val base = Color(config.tintColor)
    return this
        .clip(shape)
        .background(Brush.linearGradient(listOf(
            base.copy(alpha = (config.tintAlpha + 0.18f).coerceAtMost(0.6f)),
            base.copy(alpha = (config.tintAlpha + 0.08f).coerceAtMost(0.5f))
        )))
        .then(if (config.borderWidth > 0f) Modifier.border(
            config.borderWidth.dp,
            Color.White.copy(alpha = (config.highlightIntensity * 0.3f).coerceIn(0f, 0.6f)), shape
        ) else Modifier)
}

@Suppress("unused")
private val DefaultGlassShape = RoundedCornerShape(28.dp)

/** 已接入的表面统一遵循主题、游戏暂停与设备档位。 */
@Composable
fun Modifier.glassScopeSurface(enabled: Boolean, shape: Shape): Modifier {
    if (!enabled || !LocalGlassEnabled.current) return this
    val outline = glassShape(shape, true)
    return if (LocalGlassTier.current == GlassTier.FROST || LocalGlassBackdrop.current == null) frostedSurface(outline)
    else glassSurface(outline)
}

/**
 * Rectangles stay rectangular and circles stay circular; other rounded shapes follow the studio token.
 * 矩形在这里换成 0 圆角的 [RoundedCornerShape]：视觉上仍是矩形，但它是 CornerBasedShape，
 * backdrop 的 lens 才能接受（直接传 RectangleShape 会在 AGSL 档位绘制时闪退）。
 */
@Composable
fun glassShape(shape: Shape, active: Boolean): Shape = when {
    !active -> shape
    shape == androidx.compose.foundation.shape.CircleShape -> shape
    shape == androidx.compose.ui.graphics.RectangleShape -> RoundedCornerShape(0.dp)
    else -> RoundedCornerShape(LocalGlassConfig.current.cornerRadius.dp)
}

/**
 * Items that belong to an already-glassy bar must not stack a second refraction layer on it.
 * Explicit bars mark their own content, so cards keep their buttons even inside a glass card.
 */
val LocalInsideGlassBar = staticCompositionLocalOf { false }

/** A Box/list panel gets the same renderer without hard-coded alpha in callers. */
@Composable
fun Modifier.glassPanelBackground(shape: Shape, color: Color, scope: GlassScope = LocalGlassSurfaceScope.current): Modifier =
    if (isGlassActive(scope)) glassScopeSurface(true, shape) else clip(shape).background(color)

/** Scope is selected by the semantic container, never inferred from its color/alpha. */
enum class GlassScope { Cards, Buttons, TopBar, SideBar, Dialogs }

/** Independent windows override the default surface scope without coupling button switches. */
val LocalGlassSurfaceScope = staticCompositionLocalOf { GlassScope.Cards }

@Composable
fun isGlassActive(scope: GlassScope): Boolean = LocalGlassEnabled.current && when (scope) {
    GlassScope.Cards -> AllSettings.glassScopeCards.state
    GlassScope.Buttons -> AllSettings.glassScopeButtons.state
    GlassScope.TopBar -> AllSettings.glassScopeTopBar.state
    GlassScope.SideBar -> AllSettings.glassScopeSideBar.state
    GlassScope.Dialogs -> AllSettings.glassScopeDialogs.state
}
