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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformReleaseType
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.MCIM_CURSEFORGE_API
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.curseForgeCDNUrl
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredModrinthSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredPlatformSearcher
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPlatform
import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.safeBodyAsText
import com.movtery.zalithlauncher.utils.string.parseInstantOrNull
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * 在线平台的新版整合包检测。
 *
 * - Modrinth：公开 API（走项目既有镜像机制），按 game_versions + loaders 过滤，
 *   默认只选 release；只有当前安装的就是 beta/alpha 时才允许对应预发布版本；
 * - CurseForge：项目既有的 MCIM 镜像 API，按 gameVersion 客户端过滤，同样遵守发布渠道规则；
 * - MultiMC / MCBBS 没有在线版本源，由调用方如实标记，不在此处猜测。
 */
object PackUpdateSource {
    private const val TAG = "PackUpdateSource"

    /** 找到的新版整合包及其可下载主文件 */
    data class RemotePack(
        val platform: ModpackPlatform,
        val versionName: String,
        val versionId: String,
        val downloadUrl: String,
        val fileName: String,
        val downloadSha1: String?,
        val downloadSize: Long,
        val releaseType: PlatformReleaseType,
        val gameVersion: String?,
        val loader: String?
    )

    /**
     * 查询指定来源记录对应的最新兼容版本。
     * @param gameVersion 当前实例的 Minecraft 版本
     * @param loaderKey 规范化后的加载器（fabric/quilt/forge/neoforge），null 时不按加载器过滤
     */
    suspend fun resolveLatest(
        meta: ModpackMeta,
        gameVersion: String?,
        loaderKey: String?
    ): RemotePack {
        if (gameVersion.isNullOrBlank()) {
            throw IllegalStateException("无法确定该实例的 Minecraft 版本，不能检查整合包更新")
        }
        return when (val platform = meta.platformType()) {
            ModpackPlatform.MODRINTH -> resolveModrinth(meta, gameVersion, loaderKey)
            ModpackPlatform.CURSEFORGE -> resolveCurseForge(meta, gameVersion)
            ModpackPlatform.MULTIMC, ModpackPlatform.MCBBS ->
                throw UnsupportedOperationException("${platform.displayName} 没有在线更新来源")
            null -> throw IllegalStateException("来源记录中的平台无效：${meta.platform}")
        }
    }

    /**
     * 从加载器标识（可能是 FABRIC、fabric-loader、forge-47.2.0 等）提取 Modrinth 使用的加载器键。
     */
    fun loaderKey(raw: String?): String? {
        val lower = raw?.trim()?.lowercase()
        if (lower.isNullOrBlank()) return null
        return when {
            lower.contains("neoforge") -> "neoforge"
            lower.contains("quilt") -> "quilt"
            lower.contains("fabric") -> "fabric"
            lower.contains("forge") -> "forge"
            else -> null
        }
    }

    private suspend fun resolveModrinth(
        meta: ModpackMeta,
        gameVersion: String,
        loaderKey: String?
    ): RemotePack {
        val projectId = meta.projectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("来源记录缺少 Modrinth 项目 ID，无法检查更新")

        val currentType = currentModrinthReleaseType(meta)
        val versions = try {
            mirroredPlatformSearcher(
                searchers = mirroredModrinthSource(),
                printLog = false
            ) { searcher ->
                searcher.getVersionsChunk(projectID = projectId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("获取 Modrinth 整合包版本列表失败：${readableMessage(e)}", e)
        }

        if (versions.isEmpty()) {
            throw IllegalStateException("Modrinth 项目 $projectId 没有任何版本")
        }

        val candidates = versions.filter { version ->
            version.gameVersions.contains(gameVersion) &&
                    (loaderKey == null || version.loaders.any { it.equals(loaderKey, ignoreCase = true) }) &&
                    isReleaseAllowed(currentType, version.versionType)
        }.sortedByDescending { version ->
            runCatching { version.platformDatePublished() }.getOrDefault(Instant.EPOCH)
        }

        if (candidates.isEmpty()) {
            val filter = buildString {
                append("Minecraft ").append(gameVersion)
                loaderKey?.let { append(" / ").append(it) }
                if (currentType == PlatformReleaseType.RELEASE) append(" / 稳定版")
            }
            throw IllegalStateException("没有找到与 $filter 兼容的整合包版本")
        }

        for (candidate in candidates) {
            val initialized = try {
                candidate.initFile(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warning(TAG, "无法初始化 Modrinth 版本文件：${candidate.id}", e)
                false
            }
            if (!initialized) continue

            val url = try {
                candidate.platformDownloadUrl()
            } catch (e: Throwable) {
                Logger.warning(TAG, "Modrinth 版本缺少可下载文件：${candidate.id}", e)
                continue
            }
            if (url.isBlank()) continue

            return RemotePack(
                platform = ModpackPlatform.MODRINTH,
                versionName = candidate.versionNumber.ifBlank { candidate.name },
                versionId = candidate.id,
                downloadUrl = url,
                fileName = candidate.platformFileName().ifBlank { "pack.mrpack" },
                downloadSha1 = candidate.platformSha1(),
                downloadSize = candidate.platformFileSize(),
                releaseType = candidate.versionType,
                gameVersion = gameVersion,
                loader = loaderKey
            )
        }

        throw IllegalStateException("找到的整合包版本都没有可下载的主文件")
    }

    private suspend fun resolveCurseForge(
        meta: ModpackMeta,
        gameVersion: String
    ): RemotePack {
        val projectId = meta.projectId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("来源记录缺少 CurseForge 项目 ID，无法检查更新")

        val currentType = currentCurseForgeReleaseType(meta)
        val response = getJson("$MCIM_CURSEFORGE_API/mods/$projectId/files?pageSize=100")
        val files = response.getAsJsonArray("data")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
            ?: throw IllegalStateException("CurseForge 文件列表为空")

        val candidates = files.filter { file ->
            val gameVersions = file.getAsJsonArray("gameVersions")
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                .orEmpty()
            gameVersions.contains(gameVersion) &&
                    isReleaseAllowed(currentType, file.releaseType())
        }.sortedByDescending { file ->
            parseInstantOrNull(file.string("fileDate").orEmpty()) ?: Instant.EPOCH
        }

        val latest = candidates.firstOrNull()
            ?: throw IllegalStateException(
                "没有找到与 Minecraft $gameVersion 兼容的 CurseForge 文件" +
                        if (currentType == PlatformReleaseType.RELEASE) "（beta/alpha 已过滤）" else ""
            )

        val fileId = latest.get("id")?.takeIf { !it.isJsonNull }?.asLong
            ?: throw IllegalStateException("CurseForge 文件缺少 ID")
        val fileName = latest.string("fileName").orEmpty().ifBlank { "pack.zip" }
        val downloadUrl = latest.string("downloadUrl").orEmpty().ifBlank {
            curseForgeCDNUrl(fileId.toInt(), fileName)
        }
        val sha1 = latest.getAsJsonArray("hashes")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
            ?.firstOrNull { hash -> hash.get("algo")?.takeIf { !it.isJsonNull }?.asInt == 1 }
            ?.string("value")
        val size = latest.get("fileLength")?.takeIf { !it.isJsonNull }?.asLong ?: 0L

        return RemotePack(
            platform = ModpackPlatform.CURSEFORGE,
            versionName = latest.string("displayName").orEmpty().ifBlank { fileName },
            versionId = fileId.toString(),
            downloadUrl = downloadUrl,
            fileName = fileName,
            downloadSha1 = sha1,
            downloadSize = size,
            releaseType = latest.releaseType(),
            gameVersion = gameVersion,
            loader = null
        )
    }

    private suspend fun currentModrinthReleaseType(meta: ModpackMeta): PlatformReleaseType {
        val versionId = meta.versionId?.takeIf { it.isNotBlank() }
            ?: return inferReleaseType(meta.versionName)
        return try {
            mirroredPlatformSearcher(
                searchers = mirroredModrinthSource(),
                printLog = false
            ) { searcher ->
                searcher.getVersion(versionId)
            }.versionType
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.warning(TAG, "无法确认当前 Modrinth 版本的发布渠道，按名称推断：${meta.versionName}", e)
            inferReleaseType(meta.versionName)
        }
    }

    private suspend fun currentCurseForgeReleaseType(meta: ModpackMeta): PlatformReleaseType {
        val projectId = meta.projectId?.takeIf { it.isNotBlank() }
        val fileId = meta.versionId?.takeIf { it.isNotBlank() }?.toLongOrNull()
        if (projectId == null || fileId == null) return inferReleaseType(meta.versionName)
        return try {
            val response = getJson("$MCIM_CURSEFORGE_API/mods/$projectId/files/$fileId")
            val code = response.getAsJsonObject("data")
                ?.get("releaseType")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
                ?: return inferReleaseType(meta.versionName)
            runCatching { PlatformReleaseType.fromCurseForgeCode(code) }
                .getOrDefault(inferReleaseType(meta.versionName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.warning(TAG, "无法确认当前 CurseForge 文件的发布渠道，按名称推断：${meta.versionName}", e)
            inferReleaseType(meta.versionName)
        }
    }

    private fun inferReleaseType(versionName: String?): PlatformReleaseType {
        val lower = versionName?.lowercase().orEmpty()
        return when {
            lower.contains("alpha") -> PlatformReleaseType.ALPHA
            lower.contains("beta") -> PlatformReleaseType.BETA
            else -> PlatformReleaseType.RELEASE
        }
    }

    /**
     * 当前是稳定版时只接受稳定版；当前已经是 beta/alpha 时允许不高于当前渠道的预发布版本。
     */
    private fun isReleaseAllowed(
        current: PlatformReleaseType,
        candidate: PlatformReleaseType
    ): Boolean {
        if (current == PlatformReleaseType.RELEASE) {
            return candidate == PlatformReleaseType.RELEASE
        }
        return candidate.ordinal <= current.ordinal
    }

    private suspend fun getJson(url: String): JsonObject {
        val text = try {
            GLOBAL_CLIENT.get(url).safeBodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("请求 CurseForge 接口失败：${readableMessage(e)}", e)
        }
        return try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Throwable) {
            throw IllegalStateException("CurseForge 接口返回了无法解析的数据", e)
        }
    }

    private fun JsonObject.releaseType(): PlatformReleaseType {
        val code = get("releaseType")?.takeIf { !it.isJsonNull }?.let {
            runCatching { it.asInt }.getOrNull()
        } ?: return PlatformReleaseType.RELEASE
        return runCatching { PlatformReleaseType.fromCurseForgeCode(code) }
            .getOrDefault(PlatformReleaseType.RELEASE)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    private fun readableMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
}
