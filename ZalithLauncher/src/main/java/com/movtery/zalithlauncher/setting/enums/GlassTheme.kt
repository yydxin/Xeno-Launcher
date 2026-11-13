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
 * 外观主题。
 * 新增主题时在此追加，`GlassThemeSettings` 会自动列出。
 */
enum class GlassTheme {
    /** 默认：使用现有的 Material3 卡片/背景 */
    DEFAULT,

    /** 液态玻璃：卡片等表面用 backdrop 折射 */
    LIQUID_GLASS
}
