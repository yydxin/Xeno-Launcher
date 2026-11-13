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

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movtery.zalithlauncher.game.launch.GameService
import kotlinx.coroutines.delay

val LocalGlassGameRunning = androidx.compose.runtime.staticCompositionLocalOf { false }

private fun Context.glassGameAction() = "$packageName.GLASS_GAME_STATE_CHANGED"

/** 游戏在 :game 进程，不能用普通单例/Compose State 作为跨进程状态来源。 */
fun notifyGlassGameState(context: Context) {
    context.sendBroadcast(Intent(context.glassGameAction()).setPackage(context.packageName))
}

@Suppress("DEPRECATION") // Android O+ 仍允许查询本应用自己的服务。
private fun Context.isGlassGameRunning(): Boolean = runCatching {
    getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == GameService::class.java.name }
}.getOrDefault(false)

@Composable
fun rememberGlassGameRunning(): Boolean {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var running by remember(context) { mutableStateOf(context.isGlassGameRunning()) }
    DisposableEffect(context, owner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                running = context.isGlassGameRunning()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(context.glassGameAction()), ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) running = context.isGlassGameRunning()
        }
        owner.lifecycle.addObserver(observer)
        running = context.isGlassGameRunning()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(running) {
        // 退出服务广播可能早于系统移除记录；同时处理游戏进程被杀、没有 onDestroy 的情况。
        while (running) {
            delay(500)
            running = context.isGlassGameRunning()
        }
    }
    return running
}
