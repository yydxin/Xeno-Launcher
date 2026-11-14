package com.movtery.zalithlauncher.ui.upgrade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.movtery.zalithlauncher.ui.glass.GlassMaterialAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.movtery.zalithlauncher.ui.glass.GlassTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.components.MarkdownView
import com.movtery.zalithlauncher.update.XenoUpdateManager
import com.movtery.zalithlauncher.update.XenoUpdateState

/**
 * 自有 GitHub Releases 更新对话框。状态全部来自 [XenoUpdateManager]，不访问上游服务。
 */
@Composable
fun XenoUpdateDialog() {
    val context = LocalContext.current
    when (val state = XenoUpdateManager.state) {
        is XenoUpdateState.Checking -> {
            if (state.manual) {
                AlertDialog(
                    onDismissRequest = { XenoUpdateManager.dismiss() },
                    title = { Text(stringResource(R.string.xeno_update_checking)) },
                    text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
                    confirmButton = {}
                )
            }
        }
        is XenoUpdateState.NoUpdate -> {
            if (state.manual) {
                AlertDialog(
                    onDismissRequest = { XenoUpdateManager.dismiss() },
                    title = { Text(stringResource(R.string.xeno_update_check)) },
                    text = { Text(stringResource(R.string.xeno_update_no_release, BuildConfig.VERSION_NAME.substringBefore("-"))) },
                    confirmButton = {
                        TextButton(onClick = { XenoUpdateManager.dismiss() }) {
                            Text(stringResource(R.string.xeno_update_close))
                        }
                    }
                )
            }
        }
        is XenoUpdateState.Available -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = { XenoUpdateManager.dismiss() },
                title = { Text(stringResource(R.string.xeno_update_available, info.release.versionName)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.xeno_update_current, info.currentVersion) +
                                "\n" + stringResource(R.string.xeno_update_latest_version, info.release.versionName),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (info.release.body.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                MarkdownView(content = info.release.body)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { XenoUpdateManager.downloadAndInstall(context, info) }) {
                        Text(stringResource(R.string.xeno_update_update_now))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { XenoUpdateManager.ignore(info) }) {
                            Text(stringResource(R.string.xeno_update_ignore))
                        }
                        TextButton(onClick = { XenoUpdateManager.dismiss() }) {
                            Text(stringResource(R.string.xeno_update_later))
                        }
                    }
                }
            )
        }
        is XenoUpdateState.Downloading -> {
            val total = state.total.coerceAtLeast(1L)
            val percent = (state.received * 100 / total).coerceIn(0L, 100L).toInt()
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.xeno_update_downloading, percent)) },
                text = {
                    LinearProgressIndicator(
                        progress = { state.received.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { XenoUpdateManager.cancelDownload() }) {
                        Text(stringResource(R.string.xeno_update_cancel))
                    }
                }
            )
        }
        is XenoUpdateState.Error -> {
            if (!state.manual) return
            AlertDialog(
                onDismissRequest = { XenoUpdateManager.dismiss() },
                title = { Text(stringResource(R.string.xeno_update_check)) },
                text = { Text(stringResource(R.string.xeno_update_error, state.message)) },
                confirmButton = {
                    TextButton(onClick = { XenoUpdateManager.check(context, manual = true) }) {
                        Text(stringResource(R.string.xeno_update_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { XenoUpdateManager.dismiss() }) {
                        Text(stringResource(R.string.xeno_update_close))
                    }
                }
            )
        }
        XenoUpdateState.Idle -> Unit
    }
}
