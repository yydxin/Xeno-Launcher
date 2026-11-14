/*
 * Zalith Launcher 2 Community
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.components

import com.movtery.zalithlauncher.ui.glass.GlassToast as Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.movtery.zalithlauncher.ui.glass.GlassMaterialAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import com.movtery.zalithlauncher.ui.glass.GlassSurface as Surface
import androidx.compose.material3.Text
import com.movtery.zalithlauncher.ui.glass.GlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.enums.PerformancePreset
import com.movtery.zalithlauncher.setting.preset.PerformancePresetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动的性能预设引导。
 *
 * 使用 Compose 对话框而不是平台 AlertDialog：部分机型/主题下平台的 item 列表不渲染，
 * 会导致只剩标题和正文、无法点选。这里每个选项都是可点击卡片，必然可见可点。
 */
@Composable
fun PerformancePresetGuideDialog() {
    var visible by remember { mutableStateOf(!PerformancePresetManager.isSelected()) }
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Text(text = stringResource(R.string.performance_preset_guide_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.performance_preset_guide_message),
                    style = MaterialTheme.typography.bodyMedium
                )

                PerformancePreset.entries.forEach { preset ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                visible = false
                                scope.launch(Dispatchers.IO) {
                                    PerformancePresetManager.apply(context, preset)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            R.string.performance_preset_applied,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(all = 12.dp)) {
                            Text(
                                text = stringResource(preset.titleRes),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(preset.summaryRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    visible = false
                    //跳过引导时视为已完成选择，不再反复弹出；参数保持当前默认值
                    PerformancePresetManager.markSelected()
                }
            ) {
                Text(text = stringResource(R.string.performance_preset_later))
            }
        }
    )
}
