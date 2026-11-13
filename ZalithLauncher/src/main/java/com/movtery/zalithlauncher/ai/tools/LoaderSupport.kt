/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.tools

import com.movtery.zalithlauncher.game.addons.modloader.AddonVersion
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersion
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.legacyfabric.LegacyFabricVersion
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.legacyfabric.LegacyFabricVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.quilt.QuiltVersion
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.quilt.QuiltVersions
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.forge.ForgeVersion
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.forge.ForgeVersions
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.neoforge.NeoForgeVersion
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.neoforge.NeoForgeVersions
import com.movtery.zalithlauncher.game.addons.modloader.optifine.OptiFineVersion
import com.movtery.zalithlauncher.game.addons.modloader.optifine.OptiFineVersions
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.version.installed.Version
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/** 加载器版本查询与更新计划共用逻辑 */
internal object LoaderSupport {

    data class Candidate(
        val version: String,
        val stable: Boolean,
        val addon: AddonVersion
    )

    data class Check(
        val loader: ModLoader,
        val currentVersion: String,
        val latestVersion: String?,
        val hasUpdate: Boolean,
        val error: String? = null
    )

    class ResolveException(message: String) : Exception(message)

    data class Resolved(
        val downloadInfo: GameDownloadInfo,
        val targetVersion: String,
        val previousVersion: String?,
        val alreadyLatest: Boolean,
        val loaderName: String
    )

    /**
     * 查询同 Minecraft 版本可用的加载器版本。
     * @throws ResolveException 不支持该加载器或查询失败
     */
    suspend fun candidates(loader: ModLoader, minecraftVersion: String): List<Candidate> {
        val list = try {
            when (loader) {
                ModLoader.FABRIC -> FabricVersions.fetchFabricLoaderList(minecraftVersion)?.map {
                    Candidate(displayVersion(it), it.stable && !isBetaLabel(it.version), it)
                }
                ModLoader.QUILT -> QuiltVersions.fetchQuiltLoaderList(minecraftVersion)?.map {
                    Candidate(displayVersion(it), !isBetaLabel(it.version), it)
                }
                ModLoader.LEGACY_FABRIC -> LegacyFabricVersions.fetchFabricLoaderList(minecraftVersion)?.map {
                    Candidate(displayVersion(it), it.stable && !isBetaLabel(it.version), it)
                }
                ModLoader.FORGE -> ForgeVersions.fetchForgeList(minecraftVersion)?.map {
                    Candidate(displayVersion(it), !isBetaLabel(it.versionName), it)
                }
                ModLoader.NEOFORGE -> NeoForgeVersions.fetchNeoForgeList(
                    gameVersion = minecraftVersion
                )?.map {
                    Candidate(displayVersion(it), !it.isBeta && !isBetaLabel(it.versionName), it)
                }
                ModLoader.OPTIFINE -> OptiFineVersions.fetchOptiFineList(
                    gameVersion = minecraftVersion
                )?.map {
                    Candidate(displayVersion(it), !it.isPreview, it)
                }
                else -> throw ResolveException(
                    "当前项目没有可复用的 ${loader.displayName} 更新入口（仅支持 Fabric、Quilt、Forge、NeoForge、OptiFine）。"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResolveException) {
            throw e
        } catch (e: Throwable) {
            throw ResolveException("查询 ${loader.displayName} 版本失败：${e.message ?: e::class.java.simpleName}")
        }
        return list.orEmpty()
    }

    /**
     * 检查实例中已安装加载器的可用更新。
     */
    suspend fun check(version: Version): List<Check> {
        val info = version.getVersionInfo()
            ?: return listOf(Check(ModLoader.UNKNOWN, "", null, false, "版本信息不可解析"))
        if (info.loaderInfos.isEmpty()) return emptyList()
        return info.loaderInfos.map { installed ->
            val current = installed.version
            try {
                val all = candidates(installed.loader, info.minecraftVersion)
                val pool = if (isBetaLabel(current)) all else all.filter { it.stable }
                val latest = pool.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                Check(
                    loader = installed.loader,
                    currentVersion = current,
                    latestVersion = latest?.version,
                    hasUpdate = latest != null && compareVersions(latest.version, current) > 0
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Check(
                    loader = installed.loader,
                    currentVersion = current,
                    latestVersion = null,
                    hasUpdate = false,
                    error = e.message ?: e::class.java.simpleName
                )
            }
        }
    }

    /**
     * 解析一次加载器更新/安装所需的 GameDownloadInfo。
     * 会保留实例中其它已安装加载器（OptiFine 等）的当前版本。
     */
    suspend fun resolve(
        version: Version,
        targetLoader: ModLoader,
        requestedVersion: String?
    ): Resolved {
        val info = version.getVersionInfo()
            ?: throw ResolveException("实例「${version.getVersionName()}」的版本信息不可解析，无法更新加载器。")
        if (info.minecraftVersion.isBlank()) {
            throw ResolveException("实例「${version.getVersionName()}」缺少 Minecraft 版本信息，无法更新加载器。")
        }
        val mc = info.minecraftVersion
        val targetCandidates = candidates(targetLoader, mc)
        if (targetCandidates.isEmpty()) {
            throw ResolveException("没有找到与 Minecraft $mc 兼容的 ${targetLoader.displayName} 版本。")
        }
        val installedTarget = info.loaderInfos.firstOrNull { it.loader == targetLoader }

        val targetCandidate = if (requestedVersion.isNullOrBlank()) {
            val pool = if (isBetaLabel(installedTarget?.version.orEmpty())) {
                targetCandidates
            } else {
                targetCandidates.filter { it.stable }
            }
            pool.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                ?: throw ResolveException("没有找到与 Minecraft $mc 兼容的 ${targetLoader.displayName} 稳定版。")
        } else {
            findCandidate(targetCandidates, requestedVersion)
                ?: throw ResolveException(
                    "找不到 ${targetLoader.displayName} $requestedVersion（Minecraft $mc）。"
                )
        }

        val previous = installedTarget?.version
        val alreadyLatest = previous != null && compareVersions(targetCandidate.version, previous) <= 0 &&
                requestedVersion.isNullOrBlank()

        val addons = linkedMapOf<ModLoader, AddonVersion>()
        info.loaderInfos.forEach { installed ->
            if (installed.loader == targetLoader) return@forEach
            val addon = when (installed.loader) {
                ModLoader.FABRIC,
                ModLoader.QUILT,
                ModLoader.LEGACY_FABRIC,
                ModLoader.FORGE,
                ModLoader.NEOFORGE,
                ModLoader.OPTIFINE -> {
                    val all = candidates(installed.loader, mc)
                    findCandidate(all, installed.version)
                        ?: throw ResolveException(
                            "无法解析实例中已安装的 ${installed.loader.displayName} ${installed.version}，" +
                                    "为避免破坏原有加载器，已中止更新。"
                        )
                }
                else -> throw ResolveException(
                    "实例还包含暂不支持自动重建的加载器 ${installed.loader.displayName}，已中止更新。"
                )
            }
            addons[installed.loader] = addon.addon
        }
        addons[targetLoader] = targetCandidate.addon

        val downloadInfo = GameDownloadInfo(
            gameVersion = mc,
            customVersionName = version.getVersionName(),
            optifine = addons[ModLoader.OPTIFINE] as? OptiFineVersion,
            forge = addons[ModLoader.FORGE] as? ForgeVersion,
            neoforge = addons[ModLoader.NEOFORGE] as? NeoForgeVersion,
            fabric = addons[ModLoader.FABRIC] as? FabricVersion,
            legacyFabric = addons[ModLoader.LEGACY_FABRIC] as? LegacyFabricVersion,
            quilt = addons[ModLoader.QUILT] as? QuiltVersion
        )
        return Resolved(
            downloadInfo = downloadInfo,
            targetVersion = targetCandidate.version,
            previousVersion = previous,
            alreadyLatest = alreadyLatest,
            loaderName = targetLoader.displayName
        )
    }

    fun isBetaLabel(version: String): Boolean =
        Regex("""(?i)(alpha|beta|preview|snapshot|(^|[.\-])pre\d*|[.\-]rc\d*)""")
            .containsMatchIn(version)

    /** 解析加载器版本号用于比较，兼容 0.16.10、54.0.26、21.1.80 这类格式 */
    fun compareVersions(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)
        val size = max(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return a.compareTo(b, ignoreCase = true)
    }

    fun findCandidate(candidates: List<Candidate>, version: String): Candidate? {
        if (version.isBlank()) return null
        candidates.firstOrNull { it.version == version }?.let { return it }
        candidates.firstOrNull { runCatching { it.addon.isVersion(version) }.getOrDefault(false) }
            ?.let { return it }
        candidates.firstOrNull { it.addon.getAddonVersion() == version }?.let { return it }
        //最后再按数字比较一次，兼容 21.1.80 / 21.1.80.0 这类差异
        return candidates.firstOrNull { compareVersions(it.version, version) == 0 }
    }

    fun displayVersion(addon: AddonVersion): String = when (addon) {
        is FabricVersion -> addon.version
        is QuiltVersion -> addon.version
        is LegacyFabricVersion -> addon.version
        is ForgeVersion -> addon.versionName
        is NeoForgeVersion -> addon.versionName
        is OptiFineVersion -> addon.version
        else -> addon.getAddonVersion()
    }

    private fun numbers(version: String): List<Int> =
        Regex("""\d+""").findAll(version)
            .mapNotNull { it.value.toIntOrNull() }
            .take(8)
            .toList()
}
