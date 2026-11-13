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

package com.movtery.zalithlauncher.game.download.modpack.update

import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.ForgeBuildVersion
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.forge.ForgeVersions
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.neoforge.NeoForgeVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.quilt.QuiltVersions
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionInfo
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.string.compareVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "LoaderUpdateChecker"

/**
 * 模组加载器更新检查与安装桥接。
 *
 * 检查使用项目真实的加载器版本 API；安装复用 [GameInstaller.modifyVersion]（项目现有的“修改版本”入口），
 * 不自行拼装版本 JSON 或写入 libraries。
 *
 * 自动安装只在实例恰好只有一个受支持加载器时执行；Forge + OptiFine 等多加载器组合会被跳过并说明原因，
 * 以免在 JSON 合并时丢掉其它加载器。
 */
internal object LoaderUpdateChecker {
    private val SUPPORTED_LOADERS = setOf(
        ModLoader.FABRIC,
        ModLoader.QUILT,
        ModLoader.FORGE,
        ModLoader.NEOFORGE
    )

    data class CheckResult(
        val update: ModpackUpdateService.LoaderUpdate?,
        val note: String
    )

    data class InstallResult(
        val installed: Boolean,
        val note: String
    )

    suspend fun check(version: Version): CheckResult {
        val info = version.getVersionInfo()
            ?: return CheckResult(null, "无法读取版本信息，已跳过加载器更新检查")
        val minecraftVersion = info.minecraftVersion
        if (minecraftVersion.isBlank()) {
            return CheckResult(null, "无法确定 Minecraft 版本，已跳过加载器更新检查")
        }

        val ordered = orderedLoaders(info)
        val supported = ordered.filter { it.loader in SUPPORTED_LOADERS }
        val unsupported = info.loaderInfos.filter { it.loader.isLoader && it.loader !in SUPPORTED_LOADERS }
        val notes = mutableListOf<String>()

        unsupported.forEach { loaderInfo ->
            notes += "${loaderInfo.loader.displayName} 没有稳定的在线更新源"
        }

        if (supported.isEmpty()) {
            if (unsupported.isEmpty()) notes += "该实例没有安装模组加载器"
            return CheckResult(null, notes.joinToString("；"))
        }

        for (loaderInfo in supported) {
            val current = loaderInfo.version
            if (current.isBlank()) {
                notes += "${loaderInfo.loader.displayName} 当前版本未知，无法比较"
                continue
            }

            val latest = try {
                latestStable(loaderInfo.loader, minecraftVersion, current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warning(TAG, "检查 ${loaderInfo.loader.displayName} 最新版本失败", e)
                notes += "${loaderInfo.loader.displayName} 更新检查失败：${readableMessage(e)}"
                continue
            }

            if (latest == null) {
                notes += "没有找到与 Minecraft $minecraftVersion 兼容的 ${loaderInfo.loader.displayName} 版本"
                continue
            }

            if (isNewer(loaderInfo.loader, current, latest)) {
                return CheckResult(
                    update = ModpackUpdateService.LoaderUpdate(
                        loader = loaderInfo.loader.displayName,
                        currentVersion = current,
                        latestVersion = latest
                    ),
                    note = notes.joinToString("；")
                )
            }
            notes += "${loaderInfo.loader.displayName} 已是最新版本（$current）"
        }

        return CheckResult(null, notes.joinToString("；"))
    }

    /**
     * 真正执行加载器更新。失败会抛异常，由整合包更新流程统一回滚。
     */
    suspend fun install(
        version: Version,
        update: ModpackUpdateService.LoaderUpdate,
        onProgress: (String) -> Unit
    ): InstallResult {
        val info = version.getVersionInfo()
            ?: return InstallResult(false, "无法读取版本信息，已跳过加载器更新")
        val loader = ModLoader.entries.firstOrNull { it.displayName == update.loader }
            ?: return InstallResult(false, "无法识别加载器 ${update.loader}，已跳过自动更新")
        if (loader !in SUPPORTED_LOADERS) {
            return InstallResult(false, "${update.loader} 没有可自动安装的稳定版本，已跳过自动更新")
        }

        val installedLoaders = info.loaderInfos.filter { it.loader.isLoader }
        val supported = installedLoaders.filter { it.loader in SUPPORTED_LOADERS }
        if (installedLoaders.size != 1 ||
            supported.size != 1 ||
            supported[0].loader != loader
        ) {
            return InstallResult(
                false,
                "该实例安装了多个加载器或主加载器不是 ${update.loader}，" +
                        "为避免版本 JSON 丢失其它加载器，已跳过自动更新"
            )
        }

        val minecraftVersion = info.minecraftVersion
        if (minecraftVersion.isBlank()) {
            return InstallResult(false, "无法确定 Minecraft 版本，已跳过加载器更新")
        }

        val gameInfo = try {
            buildDownloadInfo(loader, minecraftVersion, version.getVersionName(), update.latestVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return InstallResult(false, "无法准备 ${update.loader} 安装信息：${readableMessage(e)}")
        } ?: return InstallResult(
            false,
            "加载器版本列表中没有 ${update.loader} ${update.latestVersion}，已跳过自动更新"
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val installer = GameInstaller(
            context = GlobalContext,
            info = gameInfo,
            scope = scope,
            targetGameFolder = File(version.getGameHome())
        )
        val stageText = "正在更新加载器 ${update.loader}（${update.currentVersion} → ${update.latestVersion}）"

        try {
            onProgress(stageText)
            val completion = CompletableDeferred<Unit>()
            installer.modifyVersion(
                isRunning = {
                    completion.completeExceptionally(
                        IllegalStateException("已有其他安装任务正在运行，请稍后再试")
                    )
                },
                onModified = { completion.complete(Unit) },
                onError = { error -> completion.completeExceptionally(error) }
            )

            if (!completion.isCompleted) {
                val started = withTimeoutOrNull(15_000) {
                    installer.tasksFlow.first { it.isNotEmpty() }
                } != null
                if (!started) {
                    installer.cancelInstall(clearTarget = false)
                    throw IllegalStateException("加载器安装任务未能启动")
                }
            }

            coroutineScope {
                //安装期间持续上报阶段，让调用方的取消请求能被及时观察到
                val ticker = launch {
                    while (true) {
                        delay(1_000)
                        onProgress(stageText)
                    }
                }
                try {
                    completion.await()
                } finally {
                    ticker.cancel()
                }
            }

            return InstallResult(
                installed = true,
                note = "加载器 ${update.loader} 已从 ${update.currentVersion} 更新到 ${update.latestVersion}"
            )
        } catch (e: CancellationException) {
            installer.cancelInstall(clearTarget = false)
            throw e
        } catch (e: Throwable) {
            installer.cancelInstall(clearTarget = false)
            throw IllegalStateException(
                "加载器更新失败：${readableMessage(e)}",
                e
            )
        } finally {
            scope.cancel()
        }
    }

    private fun orderedLoaders(info: VersionInfo): List<VersionInfo.LoaderInfo> =
        info.loaderInfos.filter { it.loader.isLoader }.sortedBy { loaderInfo ->
            VersionInfo.PRIMARY_PRIORITY.indexOf(loaderInfo.loader)
                .let { if (it < 0) Int.MAX_VALUE else it }
        }

    private suspend fun latestStable(
        loader: ModLoader,
        minecraftVersion: String,
        current: String
    ): String? {
        val allowPreRelease = isPreRelease(current)
        return when (loader) {
            ModLoader.FABRIC -> {
                FabricVersions.fetchFabricLoaderList(minecraftVersion)
                    ?.filter { allowPreRelease || it.stable }
                    ?.maxWithOrNull(Comparator { a, b -> a.version.compareVersion(b.version) })
                    ?.version
            }
            ModLoader.QUILT -> {
                QuiltVersions.fetchQuiltLoaderList(minecraftVersion)
                    ?.filter {
                        allowPreRelease || !isPreRelease(it.version)
                    }
                    ?.maxWithOrNull(Comparator { a, b -> a.version.compareVersion(b.version) })
                    ?.version
            }
            ModLoader.FORGE -> {
                ForgeVersions.fetchForgeList(minecraftVersion)
                    ?.firstOrNull {
                        allowPreRelease || !isPreRelease(it.versionName)
                    }
                    ?.versionName
            }
            ModLoader.NEOFORGE -> {
                NeoForgeVersions.fetchNeoForgeList(gameVersion = minecraftVersion)
                    ?.firstOrNull {
                        allowPreRelease || !it.isBeta
                    }
                    ?.versionName
            }
            else -> null
        }
    }

    private fun isNewer(loader: ModLoader, current: String, latest: String): Boolean =
        when (loader) {
            ModLoader.FORGE, ModLoader.NEOFORGE ->
                ForgeBuildVersion.parse(latest) > ForgeBuildVersion.parse(current)
            else -> latest.compareVersion(current) > 0
        }

    private fun isPreRelease(version: String): Boolean {
        val lower = version.lowercase()
        return lower.contains("beta") || lower.contains("alpha")
    }

    private suspend fun buildDownloadInfo(
        loader: ModLoader,
        minecraftVersion: String,
        versionName: String,
        latestVersion: String
    ): GameDownloadInfo? = when (loader) {
        ModLoader.FABRIC -> {
            val version = FabricVersions.fetchFabricLoaderList(minecraftVersion, force = true)
                ?.find { it.version == latestVersion }
            version?.let {
                GameDownloadInfo(
                    gameVersion = minecraftVersion,
                    customVersionName = versionName,
                    fabric = it
                )
            }
        }
        ModLoader.QUILT -> {
            val version = QuiltVersions.fetchQuiltLoaderList(minecraftVersion, force = true)
                ?.find { it.version == latestVersion }
            version?.let {
                GameDownloadInfo(
                    gameVersion = minecraftVersion,
                    customVersionName = versionName,
                    quilt = it
                )
            }
        }
        ModLoader.FORGE -> {
            val version = ForgeVersions.fetchForgeList(minecraftVersion)
                ?.find { it.versionName == latestVersion }
            version?.let {
                GameDownloadInfo(
                    gameVersion = minecraftVersion,
                    customVersionName = versionName,
                    forge = it
                )
            }
        }
        ModLoader.NEOFORGE -> {
            val version = NeoForgeVersions.fetchNeoForgeList(
                force = true,
                gameVersion = minecraftVersion
            )?.find { it.versionName == latestVersion }
            version?.let {
                GameDownloadInfo(
                    gameVersion = minecraftVersion,
                    customVersionName = versionName,
                    neoforge = it
                )
            }
        }
        else -> null
    }

    private fun readableMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
}
