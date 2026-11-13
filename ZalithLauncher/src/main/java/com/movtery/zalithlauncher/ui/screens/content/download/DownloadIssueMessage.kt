/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.screens.content.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.download.engine.ChecksumMismatchException
import com.movtery.zalithlauncher.game.download.engine.findHttpCode
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把下载/安装异常归类为可读文案：
 * 网络失败 / 服务端拒绝（401/403/429）/ 资源不存在（404）/ 校验失败 / 空间不足 / 已取消。
 *
 * 返回 null 表示未命中这些类别，由调用方沿用原有兜底逻辑。
 */
@Composable
fun downloadIssueMessage(throwable: Throwable): String? {
    if (throwable is CancellationException) {
        return stringResource(R.string.xzlc_download_issue_cancelled)
    }
    val code = throwable.findHttpCode()
    return when {
        throwable.containsCause<ChecksumMismatchException>() ->
            stringResource(R.string.xzlc_download_issue_hash)

        code == 401 || code == 403 ->
            stringResource(R.string.xzlc_download_issue_refused)

        code == 404 ->
            stringResource(R.string.xzlc_download_issue_not_found)

        code == 429 ->
            stringResource(R.string.xzlc_download_issue_rate_limited)

        throwable.isNoSpaceError() ->
            stringResource(R.string.xzlc_download_issue_space)

        else -> null
    }
}

private inline fun <reified T : Throwable> Throwable.containsCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }

private fun Throwable.isNoSpaceError(): Boolean = generateSequence(this) { it.cause }.any { cause ->
    val text = cause.message.orEmpty()
    cause is IOException && (
        text.contains("ENOSPC", ignoreCase = true) ||
            text.contains("No space left", ignoreCase = true) ||
            text.contains("There is not enough space", ignoreCase = true)
        )
}
