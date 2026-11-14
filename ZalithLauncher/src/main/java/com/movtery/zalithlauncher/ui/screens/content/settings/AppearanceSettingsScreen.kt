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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.GlassTheme
import com.movtery.zalithlauncher.ui.base.BaseScreen
import com.movtery.zalithlauncher.ui.components.AnimatedColumn
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.glass.GlassTierOverride
import com.movtery.zalithlauncher.ui.screens.NestedNavKey
import com.movtery.zalithlauncher.ui.screens.NormalNavKey
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.EnumSettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard

/**
 * 外观设置：主题（默认 / 液态玻璃）、玻璃应用范围、性能与档位。
 *
 * 说明：顶栏/底栏目前还没有接入玻璃（见调试工坊的诊断说明），这里保留开关但标注状态，
 * 不做"打开却没效果"的假开关。
 */
@Composable
fun AppearanceSettingsScreen(
    key: NestedNavKey.Settings,
    settingsScreenKey: TitledNavKey?,
    mainScreenKey: TitledNavKey?
) {
    BaseScreen(
        Triple(key, mainScreenKey, false),
        Triple(NormalNavKey.Settings.Appearance, settingsScreenKey, false)
    ) { isVisible ->
        val tier = AllSettings.glassTierOverride.state.resolve()

        AnimatedColumn(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScrollWithBar(state = rememberScrollState())
                .padding(all = 12.dp),
            isVisible = isVisible
        ) { columnScope ->
            AnimatedItem(columnScope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    EnumSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        unit = AllSettings.glassTheme,
                        entries = GlassTheme.entries,
                        title = "主题",
                        summary = "切换后立即生效，无需重启",
                        getRadioText = { theme ->
                            when (theme) {
                                GlassTheme.DEFAULT -> "默认"
                                GlassTheme.LIQUID_GLASS -> "液态玻璃"
                            }
                        },
                        getRadioEnable = { true }
                    )

                    SettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Bottom,
                        title = "当前渲染档位",
                        summary = tier.displayName,
                        onClick = {}
                    )
                }
            }

            AnimatedItem(columnScope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    Text(
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        text = "玻璃应用范围",
                        style = MaterialTheme.typography.titleSmall
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        unit = AllSettings.glassScopeCards,
                        title = "卡片",
                        summary = "首页拖拽卡片、设置、下载、账号、文件管理与消息卡片"
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.glassScopeDialogs,
                        title = "对话框与弹出层",
                        summary = "确认框、编辑框、菜单、提示与底部弹层；独立窗口采样同一壁纸"
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.glassScopeTopBar,
                        title = "顶栏",
                        summary = "主界面顶部导航栏，切换立即生效"
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Middle,
                        unit = AllSettings.glassScopeSideBar,
                        title = "侧栏与导航",
                        summary = "设置、下载、版本与文件管理分类导航"
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Bottom,
                        unit = AllSettings.glassScopeButtons,
                        title = "按钮",
                        summary = "启动、操作与图标按钮，独立于卡片和顶栏开关"
                    )
                }
            }

            AnimatedItem(columnScope) { yOffset ->
                SettingsCardColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) }
                ) {
                    Text(
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        text = "性能",
                        style = MaterialTheme.typography.titleSmall
                    )

                    SwitchSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Top,
                        unit = AllSettings.glassAutoDowngrade,
                        title = "低性能自动关闭玻璃",
                        summary = "检测到低内存设备时回退为默认主题；游戏运行期间始终暂停玻璃"
                    )

                    EnumSettingsCard(
                        modifier = Modifier.fillMaxWidth(),
                        position = CardPosition.Bottom,
                        unit = AllSettings.glassTierOverride,
                        entries = GlassTierOverride.entries,
                        title = "渲染档位",
                        summary = "自动 = 按系统版本选择；也可强制降档预览效果",
                        getRadioText = { override ->
                            when (override) {
                                GlassTierOverride.AUTO -> "自动"
                                GlassTierOverride.AGSL -> "强制 AGSL"
                                GlassTierOverride.RENDER_EFFECT -> "强制 RenderEffect"
                                GlassTierOverride.FROST -> "强制磨砂"
                            }
                        },
                        getRadioEnable = { true }
                    )
                }
            }

            AnimatedItem(columnScope) { yOffset ->
                SettingsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(x = 0, y = yOffset.roundToPx()) },
                    position = CardPosition.Single,
                    title = "参数与预设请到「玻璃调试工坊」",
                    summary = stringResource(R.string.appearance_studio_hint),
                    onClick = {}
                )
            }
        }
    }
}
