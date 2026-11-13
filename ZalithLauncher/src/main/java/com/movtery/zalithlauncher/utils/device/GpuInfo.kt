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

package com.movtery.zalithlauncher.utils.device

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "GpuInfo"

/**
 * 轻量的 GPU 信息读取：通过一次离屏 EGL 上下文读取 GL_VENDOR / GL_RENDERER。
 * 结果会缓存，避免在选择渲染器等场景反复创建上下文。
 */
object GpuInfo {
    private val cached: Pair<String?, String?>? by lazy { query() }

    /** 例如 "Adreno (TM) 740"、"Mali-G57 MC2" */
    fun rendererName(): String? = cached?.second

    /** 例如 "Qualcomm"、"ARM" */
    fun vendorName(): String? = cached?.first

    private fun query(): Pair<String?, String?>? {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Logger.error(TAG, "Failed to get EGL display")
            return null
        }
        if (!EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) {
            Logger.error(TAG, "Failed to initialize EGL")
            return null
        }

        val eglAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, eglAttributes, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            EGL14.eglTerminate(eglDisplay)
            return null
        }

        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            eglDisplay,
            configs[0]!!,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(eglDisplay)
            return null
        }

        return try {
            if (!EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    context
                )
            ) {
                return null
            }
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            Logger.debug(TAG, "GPU vendor=$vendor renderer=$renderer")
            vendor to renderer
        } finally {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroyContext(eglDisplay, context)
            EGL14.eglTerminate(eglDisplay)
        }
    }
}
