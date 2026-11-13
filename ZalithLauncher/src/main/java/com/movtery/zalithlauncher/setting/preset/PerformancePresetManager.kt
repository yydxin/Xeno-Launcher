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

package com.movtery.zalithlauncher.setting.preset

import android.content.Context
import com.movtery.zalithlauncher.game.launch.MCOptions
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.PerformancePreset
import com.movtery.zalithlauncher.utils.device.checkVulkanSupport
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.platform.bytesToMB
import com.movtery.zalithlauncher.utils.platform.getMaxMemoryForSettings
import com.movtery.zalithlauncher.utils.platform.getTotalMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val TAG = "PerformancePreset"

/**
 * 社区版性能预设的落地逻辑。
 *
 * 设计原则：
 * 1. 预设只写入“默认值”，用户随后在设置页的修改不会被覆盖；
 * 2. 渲染器只在尚未选择（空白）时给出推荐，不强制改变老用户的选择；
 * 3. JVM 参数只在用户未自定义时写入，且使用公开的、经过大量社区验证的 G1GC 参数；
 * 4. 渲染距离与帧率只作为“上限”写入对应版本的 options.txt，只会调低不会调高。
 */
object PerformancePresetManager {
    /**
     * 内置 G1GC JVM 参数预设（Aikar's Flags，Minecraft 服务端/客户端社区广泛验证的 G1 调优参数）。
     *
     * 参考：https://docs.papermc.io/paper/aikars-flags
     * 说明：这些参数针对 Minecraft 的“大量短命对象 + 周期性 GC”负载设计，目标是降低 GC 停顿
     * 与 GC 线程对游戏主线程的抢占；它们不改变堆大小（-Xmx 由启动器按内存设置单独传入）。
     */
    const val G1_JVM_ARGS =
        "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
        "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
        "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M " +
        "-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 " +
        "-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 " +
        "-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 " +
        "-XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

    /** 当前选中的预设，未选择时为 null */
    fun current(): PerformancePreset? {
        val name = AllSettings.performancePreset.getValue()
        return PerformancePreset.entries.firstOrNull { it.name == name }
    }

    /** 是否已经完成过首次启动的预设选择 */
    fun isSelected(): Boolean = AllSettings.performancePresetSelected.getValue()

    /** 根据设备能力推荐渲染器 ID */
    fun recommendRenderer(context: Context, preset: PerformancePreset): String {
        return when (preset.rendererMode) {
            PerformancePreset.RendererMode.GL4ES -> RENDERER_GL4ES
            PerformancePreset.RendererMode.NGGL4ES -> RENDERER_NGGL4ES
            PerformancePreset.RendererMode.BEST_AVAILABLE -> {
                if (checkVulkanSupport(context.packageManager)) RENDERER_KOPPER_ZINK else RENDERER_NGGL4ES
            }
        }
    }

    /**
     * 应用预设（耗时操作：读取设备内存信息）
     *
     * @param onlyIfRendererEmpty 仅在用户尚未选择渲染器时写入推荐渲染器
     */
    suspend fun apply(
        context: Context,
        preset: PerformancePreset,
        onlyIfRendererEmpty: Boolean = true
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val totalMb = getTotalMemory(context).bytesToMB()
            val maxAllowed = getMaxMemoryForSettings(context)
            val lowerBound = min(1024, maxAllowed)
            val upperBound = maxOf(lowerBound, min(preset.memoryMaxMb, maxAllowed))
            val targetMemory = (totalMb * preset.memoryPercent / 100.0)
                .toInt()
                .coerceIn(lowerBound, upperBound)

            AllSettings.ramAllocation.save(targetMemory)
            AllSettings.presetRenderDistance.save(preset.renderDistanceCap)
            AllSettings.presetMaxFps.save(preset.fpsCap)

            if (!onlyIfRendererEmpty || AllSettings.renderer.getValue().isBlank()) {
                AllSettings.renderer.save(recommendRenderer(context, preset))
            }

            // 用户已经自定义过 JVM 参数时不覆盖
            if (AllSettings.jvmArgs.getValue().isBlank()) {
                AllSettings.jvmArgs.save(G1_JVM_ARGS)
            }

            AllSettings.performancePreset.save(preset.name)
            AllSettings.performancePresetSelected.save(true)

            Logger.info(
                TAG,
                "Applied preset ${preset.name}: memory=${targetMemory}MB, " +
                "renderer=${AllSettings.renderer.getValue()}, " +
                "renderDistanceCap=${preset.renderDistanceCap}, fpsCap=${preset.fpsCap}"
            )
        }.onFailure {
            Logger.error(TAG, "Failed to apply performance preset ${preset.name}", it)
        }
    }

    /** 标记已完成选择（用户取消引导时使用，避免反复弹窗） */
    fun markSelected() {
        AllSettings.performancePresetSelected.save(true)
    }

    /**
     * 将预设的渲染距离/帧率“上限”应用到当前版本的 options.txt。
     * 只会调低，不会调高用户已有的设置。
     */
    fun applyGameCaps() {
        val preset = current() ?: return
        runCatching {
            var changed = false

            MCOptions.get("renderDistance")?.toIntOrNull()?.let { current ->
                if (current > preset.renderDistanceCap) {
                    MCOptions.set("renderDistance", preset.renderDistanceCap.toString())
                    changed = true
                }
            }

            if (preset.fpsCap > 0) {
                MCOptions.get("maxFps")?.toIntOrNull()?.let { current ->
                    if (current > preset.fpsCap) {
                        MCOptions.set("maxFps", preset.fpsCap.toString())
                        changed = true
                    }
                }
            }

            if (changed) MCOptions.save()
        }.onFailure {
            Logger.warning(TAG, "Failed to apply render distance / fps caps", it)
        }
    }

    const val RENDERER_GL4ES = "opengles2"
    const val RENDERER_NGGL4ES = "opengles3"
    const val RENDERER_KOPPER_ZINK = "opengles3_desktopgl_zink_kopper"
}
