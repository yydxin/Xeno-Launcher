/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.launcherMMKV

private const val MMKV_FORK_NOTICE_SHOWN = "xzlc.fork_notice.shown"

/**
 * 首次启动时提示“非官方修改版”，只弹一次。
 * 顶栏另有常驻角标，保证用户随时能看到。
 */
@Composable
fun ForkNoticeDialogIfNeeded() {
    var shown by remember { mutableStateOf(launcherMMKV().decodeBool(MMKV_FORK_NOTICE_SHOWN, false)) }
    if (shown) return
    SimpleAlertDialog(
        title = stringResource(R.string.xzlc_fork_notice_title),
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.xzlc_fork_notice_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "GPL-3.0 · 非官方修改版",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmText = stringResource(R.string.xzlc_fork_notice_confirm),
        onDismiss = {
            launcherMMKV().encode(MMKV_FORK_NOTICE_SHOWN, true)
            shown = true
        }
    )
}
