/*
 * Zalith Launcher 2
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

package com.movtery.zalithlauncher.game.renderer.renderers

import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesConfig
import com.movtery.zalithlauncher.setting.AllSettings

/**
 * 内置的 MobileGlues 渲染器。
 *
 * 预编译的 libmobileglues.so 由构建时放入 jniLibs，本类只负责：
 * - 渲染器元数据（id / 唯一标识 / 名称 / 版本范围）；
 * - 启动游戏时注入的环境变量（官方 FCL 插件 manifest 的 pojavEnv）；
 * - 指向 [MobileGluesConfig.configDir] 的 MG_DIR_PATH。
 *
 * 配置读写与诊断分别见 [MobileGluesConfig] 与
 * com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesDiagnostics。
 */
object MobileGluesRenderer : RendererInterface {
    /** 渲染器库与 EGL 实现共用同一个 .so */
    const val LIBRARY_NAME = "libmobileglues.so"

    /**
     * 固定 UUID，用于持久化所选渲染器；
     * 一旦发布过就不能再改，否则用户的选择会失效。
     */
    private const val UNIQUE_IDENTIFIER = "6af4a530-a7aa-4a81-8b99-c16e9b565bd4"

    override fun getRendererId(): String = "opengles3"

    override fun getUniqueIdentifier(): String = UNIQUE_IDENTIFIER

    override fun getRendererName(): String = "MobileGlues"

    override fun getRendererSummary(): String =
        "内置 MobileGlues 2.x（GL 转 GLES），预编译 libmobileglues.so；" +
                "配置入口：设置 → 渲染器 → MobileGlues 右侧齿轮。"

    override fun getMinMCVersion(): String = "1.17"

    override fun getMaxMCVersion(): String? = null

    /**
     * 官方 FCL 插件 manifest 的 pojavEnv：
     * `LIBGL_ES=3:POJAV_RENDERER=opengles3:POJAVEXEC_EGL=libmobileglues.so:LIBGL_EGL=libmobileglues.so:MG_COUNT_LAUNCH=1`
     *
     * POJAV_RENDERER 由 GameLauncher.setRendererEnv 统一写入，这里不重复；
     * MG_DIR_PATH 指向应用外部目录，避免申请 MANAGE_EXTERNAL_STORAGE。
     */
    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        buildMap {
            put("LIBGL_ES", "3")
            put("MG_COUNT_LAUNCH", "1")
            put("MG_DIR_PATH", MobileGluesConfig.configDir.absolutePath)
            put("LIBGL_EGL", LIBRARY_NAME)
            put("POJAVEXEC_EGL", LIBRARY_NAME)
        }
    }

    /**
     * 内置渲染器不使用插件的“预加载库”列表：
     * GameLauncher.dlopenEngine() 只会对 RendererPluginManager.selectedRendererPlugin
     * 读取该列表，内置渲染器统一通过 getRendererLibrary() 单独 dlopen。
     */
    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = LIBRARY_NAME

    override fun getRendererEGL(): String = LIBRARY_NAME

    /**
     * 当前是否选中了 MobileGlues。
     * 先看全局设置，再看 Renderers 已解析的当前渲染器（版本独立配置会在启动时写入这里）；
     * Renderers 未初始化时不会抛异常。
     */
    fun isCurrent(): Boolean {
        if (AllSettings.renderer.state == UNIQUE_IDENTIFIER) return true
        return try {
            Renderers.isCurrentRendererValid() &&
                    Renderers.getCurrentRenderer().getUniqueIdentifier() == UNIQUE_IDENTIFIER
        } catch (_: IllegalStateException) {
            false
        }
    }
}
