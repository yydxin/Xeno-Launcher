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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.movtery.zalithlauncher.ui.glass.GlassButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.renderer.raytracing.RayTracingBlocker
import com.movtery.zalithlauncher.game.renderer.raytracing.RayTracingCapability
import com.movtery.zalithlauncher.game.renderer.raytracing.RayTracingSupport
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.RayTracingQuality
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedColumn
import com.movtery.zalithlauncher.ui.components.MarqueeText
import com.movtery.zalithlauncher.ui.components.TitleAndSummary
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.EnumSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 光线追踪设置页（仅 Minecraft 26.2+ 的原生 Vulkan 渲染后端）
 *
 * 设计约束：
 * - **只做能力检测与参数配置**，不伪造光追效果：真正的光追需要"光追 Mod + 光追光影包"，
 *   这两者不在本版本内，因此总开关默认关闭，且未满足三件套时不写任何启动参数；
 * - 所有判断基于运行时 Vulkan 查询（[RayTracingSupport]），不按芯片型号硬编码；
 * - 检测失败、驱动不支持都只做提示，绝不抛出异常。
 */
@Composable
fun RayTracingSettingsScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?
) {
    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.RayTracing, settingsScreenKey, false)
    ) { isVisible ->
        val coroutineScope = rememberCoroutineScope()

        var capability by remember { mutableStateOf<RayTracingCapability?>(null) }
        var checking by remember { mutableStateOf(false) }

        val startCheck: () -> Unit = {
            coroutineScope.launch {
                checking = true
                capability = withContext(Dispatchers.IO) { RayTracingSupport.detect() }
                checking = false
            }
        }

        LaunchedEffect(Unit) {
            startCheck()
        }

        val caps = capability
        val hardwareSupported = caps?.hardwareSupported == true

        AnimatedColumn(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 12.dp),
            isVisible = isVisible
        ) { columnScope ->
            // ---- 状态 ----
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
                        Text(
                            text = stringResource(R.string.rt_status_title),
                            style = MaterialTheme.typography.titleSmall
                        )

                        val statusText = when {
                            checking -> stringResource(R.string.rt_status_checking)
                            caps == null -> stringResource(R.string.rt_status_checking)
                            hardwareSupported -> stringResource(R.string.rt_status_supported)
                            else -> stringResource(R.string.rt_status_unsupported)
                        }
                        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)

                        caps?.blockers?.forEach { blocker ->
                            Text(
                                text = "· " + blockerText(blocker),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        if (caps != null && !caps.hasRayTracingPipeline) {
                            Text(
                                text = stringResource(R.string.rt_status_no_pipeline_hint),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // ---- 设备信息 ----
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
                        Text(
                            text = stringResource(R.string.rt_device_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InfoLine(
                            label = stringResource(R.string.rt_device_vulkan),
                            value = caps?.vulkanVersion ?: "-"
                        )
                        InfoLine(
                            label = stringResource(R.string.rt_device_gpu),
                            value = caps?.gpuName ?: "-"
                        )
                        InfoLine(
                            label = stringResource(R.string.rt_device_soc),
                            value = caps?.socModel ?: "-"
                        )
                        val missing = caps?.missingExtensions.orEmpty()
                        InfoLine(
                            label = stringResource(R.string.rt_device_missing_ext),
                            value = if (missing.isEmpty()) "-" else missing.joinToString(", ")
                        )
                    }
                }
            }

            // ---- 参数配置 ----
            AnimatedItem(columnScope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        unit = AllSettings.rayTracingEnabled,
                        enabled = hardwareSupported,
                        title = stringResource(R.string.rt_enable_title),
                        summary = stringResource(R.string.rt_enable_summary)
                    )

                    EnumSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rayTracingQuality,
                        entries = RayTracingQuality.entries,
                        title = stringResource(R.string.rt_quality_title),
                        summary = stringResource(R.string.rt_quality_summary),
                        getRadioText = { quality: RayTracingQuality ->
                            when (quality) {
                                RayTracingQuality.LOW -> stringResource(R.string.rt_quality_low)
                                RayTracingQuality.MEDIUM -> stringResource(R.string.rt_quality_medium)
                                RayTracingQuality.HIGH -> stringResource(R.string.rt_quality_high)
                            }
                        },
                        getRadioEnable = { _: RayTracingQuality -> hardwareSupported }
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rtEffectShadows,
                        enabled = hardwareSupported,
                        title = stringResource(R.string.rt_effect_shadows),
                        summary = stringResource(R.string.rt_effect_shadows_summary)
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.rtEffectReflections,
                        enabled = hardwareSupported,
                        title = stringResource(R.string.rt_effect_reflections),
                        summary = stringResource(R.string.rt_effect_reflections_summary)
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Bottom,
                        unit = AllSettings.rtEffectAmbientOcclusion,
                        enabled = hardwareSupported,
                        title = stringResource(R.string.rt_effect_ao),
                        summary = stringResource(R.string.rt_effect_ao_summary)
                    )
                }
            }

            // ---- 说明与重新检测 ----
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
                        TitleAndSummary(
                            title = stringResource(R.string.rt_notice_title),
                            summary = stringResource(R.string.rt_notice_summary)
                        )
                        Text(
                            text = stringResource(R.string.rt_heat_warning),
                            style = MaterialTheme.typography.labelSmall
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !checking,
                            onClick = { startCheck() }
                        ) {
                            MarqueeText(text = stringResource(R.string.rt_recheck))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun blockerText(blocker: RayTracingBlocker): String {
    return when (blocker) {
        RayTracingBlocker.NO_VULKAN -> stringResource(R.string.rt_blocker_no_vulkan)
        RayTracingBlocker.NOT_ADRENO -> stringResource(R.string.rt_blocker_not_adreno)
        RayTracingBlocker.NO_ACCELERATION_STRUCTURE -> stringResource(R.string.rt_blocker_no_as)
        RayTracingBlocker.NO_RAY_QUERY -> stringResource(R.string.rt_blocker_no_ray_query)
    }
}
