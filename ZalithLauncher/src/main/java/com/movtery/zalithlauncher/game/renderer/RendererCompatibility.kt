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

package com.movtery.zalithlauncher.game.renderer

import androidx.annotation.StringRes
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.plugin.Plugin
import com.movtery.zalithlauncher.utils.device.GpuInfo

/**
 * 渲染器适用场景标注，以及已知的“性能较差的组合”提醒。
 *
 * 说明：这里的判断只用于在选择页给出提示，不会强制改变用户选择。
 */
object RendererCompatibility {
    /**
     * 返回渲染器的适用场景说明（设备档次 / GPU 家族 / 兼容性）
     */
    @StringRes
    fun scenarioRes(renderer: RendererInterface): Int? {
        val id = renderer.getRendererId()

        return when {
            isMobileGlues(renderer) -> R.string.renderer_scenario_mobileglues
            id == "opengles2" -> R.string.renderer_scenario_gl4es
            id == "opengles3" -> R.string.renderer_scenario_nggl4es
            id.contains("zink", ignoreCase = true) -> R.string.renderer_scenario_kopper_zink
            id.contains("virgl", ignoreCase = true) -> R.string.renderer_scenario_virgl
            id.contains("freedreno", ignoreCase = true) -> R.string.renderer_scenario_freedreno
            id.contains("panfrost", ignoreCase = true) -> R.string.renderer_scenario_panfrost
            renderer is Plugin -> R.string.renderer_scenario_plugin
            else -> null
        }
    }

    /** 是否为 MobileGlues（插件渲染器，ID 或名称包含 mobileglues） */
    fun isMobileGlues(renderer: RendererInterface): Boolean {
        return renderer.getRendererId().contains("mobileglues", ignoreCase = true) ||
                renderer.getRendererName().contains("mobileglues", ignoreCase = true)
    }

    /**
     * 是否为 Mali-G57 家族（Helio G99 等）。
     * 上游 Issue #1681 报告该 GPU 在 2.4.10+ 搭配 MobileGlues 时出现 3-5 倍掉帧。
     */
    fun isMaliG57Class(): Boolean {
        val renderer = GpuInfo.rendererName() ?: return false
        return renderer.contains("Mali-G57", ignoreCase = true)
    }

    /** 命中已知问题组合时需要提示用户 */
    fun shouldWarn(renderer: RendererInterface): Boolean {
        return isMobileGlues(renderer) && isMaliG57Class()
    }
}
