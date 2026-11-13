/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.movtery.zalithlauncher.ui.glass.frostedSurface
import com.movtery.zalithlauncher.ui.glass.glassSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedColumn
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.glass.GlassConfig
import com.movtery.zalithlauncher.ui.glass.GlassPreset
import com.movtery.zalithlauncher.ui.glass.GlassPresets
import com.movtery.zalithlauncher.ui.glass.GlassTier
import com.movtery.zalithlauncher.ui.glass.GlassTierOverride
import com.movtery.zalithlauncher.ui.glass.LocalGlassBackdrop
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn

/**
 * 玻璃调试工坊（开发者向）：
 * 预览 + 实时参数 + 预设管理（保存/删除/导入/导出）+ 诊断。
 *
 * 预览区自带一份独立的 backdrop，不依赖主界面背景，所以低端设备上也能放心调。
 */
@Composable
fun GlassStudioScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?
) {
    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.GlassStudio, settingsScreenKey, false)
    ) { isVisible ->
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val tier = if (com.movtery.zalithlauncher.ui.glass.LocalGlassGameRunning.current) GlassTier.FROST
        else AllSettings.glassTierOverride.state.resolve()

        var config by remember {
            mutableStateOf(GlassConfig.fromJson(AllSettings.glassConfigJson.state))
        }
        var presetName by remember { mutableStateOf("") }
        var importText by remember { mutableStateOf("") }
        var showImport by remember { mutableStateOf(false) }
        val userPresets = remember {
            mutableStateListOf<GlassPreset>()
        }
        LaunchedEffect(Unit) {
            val loaded = withContext(Dispatchers.IO) { GlassPresets.userPresets() }
            userPresets.clear()
            userPresets.addAll(loaded)
        }

        fun persist(updated: GlassConfig) {
            config = updated.sanitized()
            val extra = GlassConfig.parseUnknown(AllSettings.glassConfigJson.state)
            AllSettings.glassConfigJson.save(GlassConfig.toJson(config, extra))
        }

        val previewBackdrop = rememberLayerBackdrop()

        AnimatedColumn(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 12.dp),
            isVisible = isVisible
        ) { columnScope ->
            // ---------- 预览 ----------
            AnimatedItem(columnScope) { yOffset ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    // 注意：被 layerBackdrop 录制的内容里**不能**包含任何玻璃面板，
                    // 否则面板会去采样"正在录制自己的那个 layer"，形成自引用直接崩溃。
                    // 因此这里拆成两个兄弟节点：背景只负责被录制，面板单独画在上面。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                // 低版本（磨砂档）不录制 backdrop：GraphicsLayer 在旧系统上行为不一致，
                                // 宁可退化成静态染色，也不冒崩溃的风险。
                                if (tier != GlassTier.FROST) Modifier.layerBackdrop(previewBackdrop) else Modifier
                            )
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF1B1B2F), Color(0xFF4B2E63), Color(0xFF0F6B7A))
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = 210.dp, height = 96.dp)
                            .glassPreview(
                                backdrop = previewBackdrop,
                                config = config,
                                tier = tier,
                                shape = RoundedCornerShape(config.cornerRadius.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "玻璃预览", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        text = "档位：${tier.displayName}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // ---------- 参数 ----------
            AnimatedItem(columnScope) { yOffset ->
                SettingsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    position = CardPosition.Single
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "参数", style = MaterialTheme.typography.titleSmall)

                        ParamSlider("模糊半径", config.blurRadius, 0f..60f, "dp",
                            onChange = { persist(config.copy(blurRadius = it)) })
                        ParamSlider("折射高度", config.refractionHeight, 0f..80f, "dp",
                            onChange = { persist(config.copy(refractionHeight = it)) })
                        ParamSlider("折射强度", config.refractionAmount, 0f..1f, "",
                            onChange = { persist(config.copy(refractionAmount = it)) })
                        ParamSlider("染色透明度", config.tintAlpha, 0f..1f, "",
                            onChange = { persist(config.copy(tintAlpha = it)) })
                        ParamSlider("高光强度", config.highlightIntensity, 0f..2f, "",
                            onChange = { persist(config.copy(highlightIntensity = it)) })
                        ParamSlider("阴影半径", config.shadowRadius, 0f..48f, "dp",
                            onChange = { persist(config.copy(shadowRadius = it)) })
                        ParamSlider("圆角", config.cornerRadius, 0f..64f, "dp",
                            onChange = { persist(config.copy(cornerRadius = it)) })

                        ToggleRow("色散（边缘彩边）", config.chromaticAberration) {
                            persist(config.copy(chromaticAberration = it))
                        }
                        ToggleRow("深度折射", config.depthEffect) {
                            persist(config.copy(depthEffect = it))
                        }
                        ToggleRow("滚动时降低刷新", config.throttleWhileScrolling) {
                            persist(config.copy(throttleWhileScrolling = it))
                        }
                    }
                }
            }

            // ---------- 预设 ----------
            AnimatedItem(columnScope) { yOffset ->
                SettingsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    position = CardPosition.Single
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "预设", style = MaterialTheme.typography.titleSmall)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            (GlassPresets.builtIn + userPresets).forEach { preset ->
                                FilterChip(
                                    selected = false,
                                    onClick = { persist(preset.config) },
                                    label = { Text(preset.name) }
                                )
                            }
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = presetName,
                            onValueChange = { presetName = it },
                            label = { Text("预设名称") },
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = presetName.isNotBlank(),
                                onClick = {
                                    val preset = GlassPreset(
                                        id = "user-" + System.currentTimeMillis().toString(36),
                                        name = presetName.trim(),
                                        builtIn = false,
                                        config = config
                                    )
                                    if (GlassPresets.save(preset)) {
                                        userPresets.removeAll { it.id == preset.id }
                                        userPresets.add(preset)
                                        presetName = ""
                                        Toast.makeText(context, "已保存预设", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) { Text("保存为预设") }

                            OutlinedButton(
                                onClick = {
                                    val text = GlassPresets.exportText(
                                        GlassPreset("export", "当前参数", false, config)
                                    )
                                    clipboard.setText(AnnotatedString(text))
                                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                }
                            ) { Text("导出当前") }

                            OutlinedButton(
                                onClick = { showImport = !showImport }
                            ) { Text("导入") }
                        }

                        if (showImport) {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = importText,
                                onValueChange = { importText = it },
                                label = { Text("粘贴预设 JSON") },
                                minLines = 3
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val names = (GlassPresets.builtIn + userPresets).map { it.name }.toSet()
                                    val imported = GlassPresets.importText(importText, names)
                                    if (imported != null && GlassPresets.save(imported)) {
                                        userPresets.add(imported)
                                        importText = ""
                                        showImport = false
                                        Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "导入失败：内容不是有效预设", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("确认导入") }

                                OutlinedButton(onClick = {
                                    val text = clipboard.getText()?.text
                                    if (text.isNullOrBlank()) {
                                        Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                    } else {
                                        importText = text
                                    }
                                }) { Text("从剪贴板读取") }
                            }
                        }

                        if (userPresets.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val target = userPresets.last()
                                    if (GlassPresets.delete(target.id)) {
                                        userPresets.remove(target)
                                        Toast.makeText(context, "已删除 ${target.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("删除最后一个自定义预设") }
                            }
                        }

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                persist(GlassConfig.Default)
                                Toast.makeText(context, "已恢复默认参数", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("恢复默认") }
                    }
                }
            }

            // ---------- 诊断 ----------
            AnimatedItem(columnScope) { yOffset ->
                SettingsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    position = CardPosition.Single
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "诊断", style = MaterialTheme.typography.titleSmall)
                        DiagLine("渲染档位", tier.displayName)
                        DiagLine("系统 API", android.os.Build.VERSION.SDK_INT.toString())
                        DiagLine(
                            "主界面 backdrop",
                            if (LocalGlassBackdrop.current != null) "已捕获" else "未启用/不可用"
                        )
                        DiagLine("设备最高档位", GlassTierOverride.AUTO.resolve().displayName)
                        DiagLine("开销估算", costEstimate(config, tier))
                        DiagLine(
                            "参数摘要",
                            "blur=${config.blurRadius.toInt()} 折射=${config.refractionHeight.toInt()}/${String.format("%.2f", config.refractionAmount)} 染色=${String.format("%.2f", config.tintAlpha)}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.glassPreview(
    backdrop: LayerBackdrop,
    config: GlassConfig,
    tier: GlassTier,
    shape: Shape
): Modifier {
    return if (tier == GlassTier.FROST) frostedSurface(shape, config)
    else glassSurface(shape, config, tier = tier, backdrop = backdrop)
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (suffix.isEmpty()) String.format("%.2f", value) else "${value.toInt()}$suffix",
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 粗略开销估算：只用于给用户一个"高/中/低"的直觉，不是精确性能数据 */
private fun costEstimate(config: GlassConfig, tier: GlassTier): String {
    if (tier == GlassTier.FROST) return "低（磨砂降级）"
    var score = 0
    if (config.blurRadius > 24f) score += 2 else if (config.blurRadius > 12f) score += 1
    if (config.refractionHeight > 30f) score += 2 else if (config.refractionHeight > 12f) score += 1
    if (config.chromaticAberration) score += 1
    if (tier == GlassTier.RENDER_EFFECT) score -= 1
    return when {
        score >= 4 -> "高（建议只在旗舰机使用）"
        score >= 2 -> "中"
        else -> "低"
    }
}
