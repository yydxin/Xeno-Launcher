/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.setting.enums

/**
 * 光追质量预设（仅影响下发给光追 Mod 的参数，由 Mod 侧执行）
 */
enum class RayTracingQuality {
    /** 低：光线数最少、距离最短、分辨率缩放最大，适合中端机 */
    LOW,

    /** 中：默认档，手机端建议值 */
    MEDIUM,

    /** 高：光线数与距离更高，仅建议在散热良好的旗舰机上使用 */
    HIGH
}
