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

package com.movtery.zalithlauncher.setting.enums

import androidx.annotation.StringRes

/**
 * 社区版性能预设。
 *
 * 预设只提供“默认值”，应用后用户仍可在设置页逐项修改；
 * 参数取值依据见 docs/PERFORMANCE.md（社区调优经验汇总）。
 *
 * @param memoryPercent 建议分配内存占设备物理内存的百分比
 * @param memoryMaxMb 建议分配内存的硬上限（避免小内存设备被 -Xmx 压垮）
 * @param rendererMode 渲染器推荐策略
 * @param renderDistanceCap 渲染距离上限（区块），写入该版本 options.txt 时只会调低不会调高
 * @param fpsCap 帧率上限，0 表示不限制（交给垂直同步/屏幕刷新率）
 */
enum class PerformancePreset(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val memoryPercent: Int,
    val memoryMaxMb: Int,
    val rendererMode: RendererMode,
    val renderDistanceCap: Int,
    val fpsCap: Int
) {
    SMOOTH(
        titleRes = com.movtery.zalithlauncher.R.string.performance_preset_smooth_title,
        summaryRes = com.movtery.zalithlauncher.R.string.performance_preset_smooth_summary,
        memoryPercent = 25,
        memoryMaxMb = 2048,
        rendererMode = RendererMode.GL4ES,
        renderDistanceCap = 6,
        fpsCap = 60
    ),
    BALANCED(
        titleRes = com.movtery.zalithlauncher.R.string.performance_preset_balanced_title,
        summaryRes = com.movtery.zalithlauncher.R.string.performance_preset_balanced_summary,
        memoryPercent = 35,
        memoryMaxMb = 3072,
        rendererMode = RendererMode.NGGL4ES,
        renderDistanceCap = 10,
        fpsCap = 90
    ),
    HIGH_QUALITY(
        titleRes = com.movtery.zalithlauncher.R.string.performance_preset_quality_title,
        summaryRes = com.movtery.zalithlauncher.R.string.performance_preset_quality_summary,
        memoryPercent = 45,
        memoryMaxMb = 4096,
        rendererMode = RendererMode.BEST_AVAILABLE,
        renderDistanceCap = 16,
        fpsCap = 0
    );

    enum class RendererMode(@StringRes val labelRes: Int) {
        /** GL4ES：兼容性最好，适合低端/GLES2 设备 */
        GL4ES(com.movtery.zalithlauncher.R.string.performance_renderer_gl4es),
        /** NGGL4ES：GLES3 实现，中端设备默认 */
        NGGL4ES(com.movtery.zalithlauncher.R.string.performance_renderer_nggl4es),
        /** 优先 Vulkan + Kopper Zink，不支持时回退 NGGL4ES */
        BEST_AVAILABLE(com.movtery.zalithlauncher.R.string.performance_renderer_best)
    }
}
