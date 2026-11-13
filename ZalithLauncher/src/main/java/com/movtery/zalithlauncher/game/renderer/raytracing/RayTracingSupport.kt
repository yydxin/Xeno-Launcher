/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.renderer.raytracing

import android.os.Build
import com.movtery.zalithlauncher.utils.device.GpuInfo
import com.movtery.zalithlauncher.utils.device.VulkanChecker
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RayTracingSupport"

/**
 * 光追所需的 Vulkan 能力（全部按运行时查询结果判断，不按芯片型号硬编码）
 *
 * 说明：Minecraft 26.2 起才有原生 Vulkan 后端（见 [com.movtery.zalithlauncher.utils.device.VulkanRequirements]），
 * 光追只在这条路径上提供；GL/MobileGlues 路径不参与光追。
 */
object RayTracingRequirements {
    /** 加速结构（BLAS/TLAS），硬件光追的基础 */
    const val VK_KHR_ACCELERATION_STRUCTURE = "VK_KHR_acceleration_structure"

    /** 光线查询（rayQuery，在任意着色器里内联光追） */
    const val VK_KHR_RAY_QUERY = "VK_KHR_ray_query"

    /** 加速结构构建所需的分帧延迟主机操作 */
    const val VK_KHR_DEFERRED_HOST_OPERATIONS = "VK_KHR_deferred_host_operations"

    /** 完整光追管线（可选：辅助 Mod 现阶段只用 rayQuery） */
    const val VK_KHR_RAY_TRACING_PIPELINE = "VK_KHR_ray_tracing_pipeline"

    /** 首个提供 Vulkan 后端的 Minecraft 版本 */
    const val MIN_MC_VERSION = "26.2"
}

/** 阻止开启光追的原因（用于界面提示，按顺序显示） */
enum class RayTracingBlocker {
    /** 设备/驱动完全没有 Vulkan，或 Vulkan 初始化失败 */
    NO_VULKAN,

    /** 不是高通 Adreno（现阶段只计划适配 Adreno 的硬件光追） */
    NOT_ADRENO,

    /** 缺少 VK_KHR_acceleration_structure */
    NO_ACCELERATION_STRUCTURE,

    /** 缺少 VK_KHR_ray_query */
    NO_RAY_QUERY
}

/**
 * 一次光追能力检测的结果。
 *
 * 只做只读查询，不创建任何 Vulkan 设备；检测失败也不会抛出异常。
 */
data class RayTracingCapability(
    val gpuName: String?,
    val socModel: String?,
    val vulkanVersion: String?,
    val extensions: List<String>,
    val checkedAt: Long
) {
    val hasAccelerationStructure: Boolean
        get() = extensions.any { it.equals(RayTracingRequirements.VK_KHR_ACCELERATION_STRUCTURE, ignoreCase = true) }

    val hasRayQuery: Boolean
        get() = extensions.any { it.equals(RayTracingRequirements.VK_KHR_RAY_QUERY, ignoreCase = true) }

    val hasDeferredHostOperations: Boolean
        get() = extensions.any { it.equals(RayTracingRequirements.VK_KHR_DEFERRED_HOST_OPERATIONS, ignoreCase = true) }

    val hasRayTracingPipeline: Boolean
        get() = extensions.any { it.equals(RayTracingRequirements.VK_KHR_RAY_TRACING_PIPELINE, ignoreCase = true) }

    /** 是否为高通 Adreno（只读 GL_RENDERER 字符串，兼容 "Adreno (TM) 740"） */
    val isAdreno: Boolean
        get() = gpuName?.contains("Adreno", ignoreCase = true) == true

    /** 阻止开启光追的全部原因，为空表示硬件/驱动层面可用 */
    val blockers: List<RayTracingBlocker>
        get() = buildList {
            if (vulkanVersion == null) add(RayTracingBlocker.NO_VULKAN)
            if (!isAdreno) add(RayTracingBlocker.NOT_ADRENO)
            if (!hasAccelerationStructure) add(RayTracingBlocker.NO_ACCELERATION_STRUCTURE)
            if (!hasRayQuery) add(RayTracingBlocker.NO_RAY_QUERY)
        }

    /** 硬件与驱动是否满足光追的最低要求 */
    val hardwareSupported: Boolean
        get() = blockers.isEmpty()

    /** 缺少的关键扩展，用于诊断展示 */
    val missingExtensions: List<String>
        get() = buildList {
            if (!hasAccelerationStructure) add(RayTracingRequirements.VK_KHR_ACCELERATION_STRUCTURE)
            if (!hasRayQuery) add(RayTracingRequirements.VK_KHR_RAY_QUERY)
            if (!hasDeferredHostOperations) add(RayTracingRequirements.VK_KHR_DEFERRED_HOST_OPERATIONS)
        }
}

/**
 * 光追能力检测入口。
 *
 * - 使用系统 Vulkan 驱动做只读查询（与"优先使用系统 Vulkan 驱动"开关一致）；
 * - 失败时返回一个"全不支持"的结果，绝不抛异常；
 * - 不做缓存，由界面层决定何时重新检测。
 */
object RayTracingSupport {
    suspend fun detect(): RayTracingCapability = withContext(Dispatchers.IO) {
        val gpu = runCatching { GpuInfo.rendererName() }.getOrNull()
        val soc = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        }.getOrNull()

        val capabilities = runCatching {
            VulkanChecker.checkCapabilities(
                driverPath = null,
                nativeDir = null,
                cacheDir = null
            )
        }.onFailure { e ->
            Logger.warning(TAG, "Vulkan capability check failed", e)
        }.getOrNull()

        RayTracingCapability(
            gpuName = gpu,
            socModel = soc,
            vulkanVersion = capabilities?.versionString,
            extensions = capabilities?.extensions ?: emptyList(),
            checkedAt = System.currentTimeMillis()
        )
    }
}
