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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformDependencyType
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformDisplayLabel
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformReleaseType
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformSearchData
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformSearchFilter
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformSortField
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.CurseForgeData
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.CurseForgeFile
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.CurseForgeModLoader
import com.movtery.zalithlauncher.game.download.assets.platform.getProjectByVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getVersionById
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredCurseForgeSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredModrinthSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredPlatformSearcher
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.ModrinthModLoaderCategory
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.ModrinthVersion
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.ModrinthSearcher
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.CurseForgeSearcher
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.min

internal data class SearchItem(
    val platform: Platform,
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val downloads: Long,
    val versions: List<String>,
    val iconUrl: String?
) {
    fun toJson(): JsonObject = JsonObject().apply {
        put("title", title)
        put("slug", slug)
        put("id", id)
        put("platform", platform.name.lowercase())
        put("downloads", downloads)
        put("description", description.take(500))
        add("versions", jsonArray(versions.take(80)))
        put("iconUrl", iconUrl.orEmpty())
    }
}

internal data class ProjectRef(
    val platform: Platform,
    val id: String,
    val title: String,
    val slug: String
)

internal data class PlannedFile(
    val platform: Platform,
    val projectId: String,
    val versionId: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sha1: String?,
    val size: Long,
    val target: File,
    val relativePath: String,
    val dependency: Boolean,
    val depth: Int
)

internal data class InstallPlan(
    val instanceName: String,
    val kind: String,
    val platform: Platform,
    val projectTitle: String,
    val createdAt: Long,
    val files: List<PlannedFile>,
    val warnings: List<String>
) {
    val backupRootPattern: String
        get() = "<instance>/XingLan/ai-backup/<时间戳>/"
}

internal object InstallPlanCache {
    private const val TTL_MILLIS = 10 * 60 * 1000L
    private val map = java.util.concurrent.ConcurrentHashMap<String, InstallPlan>()

    fun key(
        instance: String,
        platform: String,
        kind: String,
        projectId: String?,
        query: String?,
        versionId: String?
    ): String = listOf(
        instance.trim().lowercase(),
        platform.trim().lowercase(),
        kind.trim().lowercase(),
        projectId?.trim().orEmpty().lowercase(),
        query?.trim().orEmpty().lowercase(),
        versionId?.trim().orEmpty().lowercase()
    ).joinToString("|")

    fun put(key: String, plan: InstallPlan) {
        map[key] = plan
    }

    fun get(key: String): InstallPlan? {
        val plan = map[key] ?: return null
        if (System.currentTimeMillis() - plan.createdAt > TTL_MILLIS) {
            map.remove(key)
            return null
        }
        return plan
    }

    fun invalidate(key: String) {
        map.remove(key)
    }
}

internal object PlatformSupport {

    fun platformOf(raw: String?): Platform? = when (raw?.trim()?.lowercase()) {
        "modrinth" -> Platform.MODRINTH
        "curseforge" -> Platform.CURSEFORGE
        else -> null
    }

    fun classesOf(kind: String): PlatformClasses? = when (kind.trim().lowercase()) {
        "mod" -> PlatformClasses.MOD
        "modpack" -> PlatformClasses.MOD_PACK
        "shader" -> PlatformClasses.SHADERS
        "resourcepack" -> PlatformClasses.RESOURCE_PACK
        else -> null
    }

    fun kindFolder(kind: String): VersionFolders = when (kind.trim().lowercase()) {
        "mod" -> VersionFolders.MOD
        "shader" -> VersionFolders.SHADERS
        "resourcepack" -> VersionFolders.RESOURCE_PACK
        else -> VersionFolders.NONE
    }

    fun loaderLabel(platform: Platform, rawLoader: String?): PlatformDisplayLabel? {
        val loader = rawLoader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (platform) {
            Platform.MODRINTH -> ModrinthModLoaderCategory.entries.firstOrNull {
                it.facetValue().equals(loader, true) || it.getDisplayName().equals(loader, true)
            }
            Platform.CURSEFORGE -> CurseForgeModLoader.entries.firstOrNull {
                it.getDisplayName().equals(loader, true) || it.name.equals(loader, true)
            }
        }
    }

    suspend fun search(
        platform: Platform,
        query: String,
        kind: String,
        minecraftVersion: String?,
        loader: String?,
        limit: Int
    ): List<SearchItem> {
        val classes = classesOf(kind) ?: throw IllegalArgumentException("不支持的资源类型：$kind")
        val filter = PlatformSearchFilter(
            searchName = query,
            gameVersion = minecraftVersion.orEmpty(),
            sortField = PlatformSortField.RELEVANCE,
            modloader = loaderLabel(platform, loader),
            index = 0,
            limit = limit.coerceIn(1, 20)
        )
        val result = when (platform) {
            Platform.MODRINTH -> mirroredPlatformSearcher(
                searchers = mirroredModrinthSource(),
                printLog = false
            ) { searcher ->
                searcher.searchAssets(query, filter, classes)
            }
            Platform.CURSEFORGE -> mirroredPlatformSearcher(
                searchers = mirroredCurseForgeSource(),
                printLog = false
            ) { searcher ->
                searcher.searchAssets(query, filter, classes)
            }
        }
        return result.getAssetsPage(classes).data
            .map { it.first }
            .map { it.toSearchItem(classes) }
    }

    private fun PlatformSearchData.toSearchItem(classes: PlatformClasses): SearchItem {
        val slug = when (this) {
            is com.movtery.zalithlauncher.game.download.assets.platform.modrinth.ModrinthSearchResult.ModrinthProject ->
                this.slug.orEmpty()
            is CurseForgeData -> this.slug
            else -> ""
        }
        val versions = when (this) {
            is com.movtery.zalithlauncher.game.download.assets.platform.modrinth.ModrinthSearchResult.ModrinthProject ->
                this.versions.toList()
            is CurseForgeData -> this.latestFilesIndexes.map { it.gameVersion }.distinct()
            else -> emptyList()
        }
        return SearchItem(
            platform = platform(),
            id = platformId(),
            slug = slug,
            title = platformTitle(),
            description = platformDescription(),
            downloads = platformDownloadCount(),
            versions = versions,
            iconUrl = platformIconUrl()
        )
    }

    suspend fun resolveProject(
        platform: Platform,
        projectId: String?,
        query: String?,
        kind: String
    ): ProjectRef {
        val id = projectId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            val project = runCatching { getProjectByVersion(id, platform, printLog = false) }.getOrNull()
            if (project != null) {
                return ProjectRef(
                    platform = platform,
                    id = project.platformId(),
                    title = project.platformTitle(),
                    slug = project.platformSlug()
                )
            }
            if (query.isNullOrBlank()) {
                throw IllegalArgumentException("在 ${platform.displayName} 上找不到项目 ID：$id")
            }
        }
        val searchQuery = query?.trim().orEmpty()
        if (searchQuery.isEmpty()) {
            throw IllegalArgumentException("project_id 和 query 至少提供一个。")
        }
        val hits = search(platform, searchQuery, kind, null, null, 10)
        val first = hits.firstOrNull()
            ?: throw IllegalArgumentException("在 ${platform.displayName} 上搜索不到「$searchQuery」。")
        return ProjectRef(platform, first.id, first.title, first.slug)
    }

    /**
     * 获取项目版本列表（限制为最近的一页，避免为一次安装拉取全部历史版本）。
     */
    private suspend fun fetchVersions(
        platform: Platform,
        projectId: String,
        limit: Int = 100
    ): List<PlatformVersion> = when (platform) {
        Platform.MODRINTH -> mirroredPlatformSearcher(
            searchers = mirroredModrinthSource(),
            printLog = false
        ) { searcher: ModrinthSearcher ->
            searcher.getVersionsChunk(projectId, pageSize = limit, offset = 0)
        }
        Platform.CURSEFORGE -> mirroredPlatformSearcher(
            searchers = mirroredCurseForgeSource(),
            printLog = false
        ) { searcher: CurseForgeSearcher ->
            searcher.getVersions(projectID = projectId, index = 0, pageSize = limit).data.toList()
        }
    }

    private fun loaderMatches(platform: Platform, version: PlatformVersion, loaderName: String?): Boolean {
        if (loaderName.isNullOrBlank()) return true
        val labels = version.platformLoaders()
        if (labels.isEmpty()) return true
        val accepted = when (loaderName.lowercase()) {
            // Quilt 兼容 Fabric 模组
            "quilt" -> setOf("quilt", "fabric")
            "fabric" -> setOf("fabric")
            "forge" -> setOf("forge")
            "neoforge" -> setOf("neoforge")
            "legacy fabric" -> setOf("legacy fabric", "fabric")
            else -> setOf(loaderName.lowercase())
        }
        return labels.any { it.getDisplayName().lowercase() in accepted }
    }

    /** 列出项目最近版本，供 AI 的整合包版本查询使用。 */
    suspend fun listVersions(
        platform: Platform,
        projectId: String,
        limit: Int = 30
    ): List<PlatformVersion> = fetchVersions(platform, projectId, limit = limit.coerceIn(1, 100))

    /**
     * 选择与实例兼容的最新版本；显式 versionId 时直接取该版本。
     */
    suspend fun findCompatibleVersion(
        platform: Platform,
        projectId: String,
        kind: String,
        minecraftVersion: String,
        loaderName: String?,
        versionId: String?
    ): PlatformVersion? {
        if (!versionId.isNullOrBlank()) {
            val explicit = when (platform) {
                Platform.MODRINTH -> getVersionById(versionId, Platform.MODRINTH, printLog = false)
                Platform.CURSEFORGE -> mirroredPlatformSearcher(
                    searchers = mirroredCurseForgeSource(),
                    printLog = false
                ) { searcher: CurseForgeSearcher ->
                    searcher.getVersion(projectId, versionId).data
                }
            }
            val ok = runCatching { explicit.initFile(projectId) }.getOrDefault(false)
            return explicit.takeIf { ok }
        }

        val all = fetchVersions(platform, projectId)
        val compatible = all.mapNotNull { version ->
            val ok = runCatching { version.initFile(projectId) }.getOrDefault(false)
            if (!ok) return@mapNotNull null
            val games = runCatching { version.platformGameVersion().toList() }.getOrDefault(emptyList())
            if (games.isNotEmpty() && minecraftVersion.isNotBlank() && minecraftVersion !in games) {
                return@mapNotNull null
            }
            if (kind.equals("mod", true) && !loaderMatches(platform, version, loaderName)) {
                return@mapNotNull null
            }
            version
        }
        if (compatible.isEmpty()) return null
        val releases = compatible.filter { it.platformReleaseType() == PlatformReleaseType.RELEASE }
        val pool = releases.ifEmpty { compatible }
        return pool.maxByOrNull { runCatching { it.platformDatePublished() }.getOrNull() ?: java.time.Instant.EPOCH }
    }

    /**
     * 递归解析 required 依赖，最多 3 层。
     */
    suspend fun resolveRequiredDependencies(
        root: PlatformVersion,
        platform: Platform,
        kind: String,
        minecraftVersion: String,
        loaderName: String?
    ): Pair<List<PlatformVersion>, List<String>> {
        val result = mutableListOf<PlatformVersion>()
        val warnings = mutableListOf<String>()
        if (!kind.equals("mod", true)) return result to warnings

        val visited = mutableSetOf<String>()
        visited.add("${platform.name}/${root.platformProjectId()}")

        suspend fun walk(version: PlatformVersion, depth: Int) {
            if (depth >= 3) {
                val deps = runCatching { version.platformDependencies() }.getOrDefault(emptyList())
                if (deps.any { it.type == PlatformDependencyType.REQUIRED }) {
                    warnings += "依赖层级达到 3 层上限，已停止继续解析更深层依赖，请手动检查。"
                }
                return
            }
            val dependencies = runCatching { version.platformDependencies() }.getOrDefault(emptyList())
                .filter { it.type == PlatformDependencyType.REQUIRED }
            for (dependency in dependencies) {
                if (dependency.platform != platform) {
                    warnings += "依赖属于 ${dependency.platform.displayName}，当前安装器只处理 " +
                            "${platform.displayName} 依赖，已跳过：${dependency.projectId ?: dependency.versionId}"
                    continue
                }
                var projectId = dependency.projectId
                if (projectId.isNullOrBlank() && !dependency.versionId.isNullOrBlank()) {
                    projectId = runCatching {
                        getVersionById(dependency.versionId!!, platform, printLog = false).platformProjectId()
                    }.getOrNull()
                }
                if (projectId.isNullOrBlank()) {
                    warnings += "依赖缺少项目 ID，已跳过：${dependency.versionId ?: "unknown"}"
                    continue
                }
                val key = "${platform.name}/$projectId"
                if (!visited.add(key)) continue
                val dependencyVersion = findCompatibleVersion(
                    platform = platform,
                    projectId = projectId,
                    kind = "mod",
                    minecraftVersion = minecraftVersion,
                    loaderName = loaderName,
                    versionId = dependency.versionId
                ) ?: throw IllegalArgumentException(
                    "无法解析必需依赖 $projectId（Minecraft $minecraftVersion）的兼容版本。"
                )
                result.add(dependencyVersion)
                walk(dependencyVersion, depth + 1)
            }
        }

        walk(root, 0)
        return result to warnings
    }

    fun buildPlan(
        version: Version,
        kind: String,
        platform: Platform,
        projectId: String?,
        query: String?,
        versionId: String?,
        rootProject: ProjectRef,
        rootVersion: PlatformVersion,
        dependencyVersions: List<PlatformVersion>,
        warnings: List<String>
    ): InstallPlan {
        val classes = classesOf(kind) ?: throw IllegalArgumentException("不支持的资源类型：$kind")
        val targetFolder = kindFolder(kind).getDir(version.getGameDir())
        if (!LauncherPathGuard.isManaged(targetFolder)) {
            throw IllegalArgumentException("目标目录不在启动器管理范围内：${targetFolder.absolutePath}")
        }
        val mc = version.getVersionInfo()?.minecraftVersion.orEmpty()
        val loaderName = version.getVersionInfo()?.primaryLoader?.loader?.displayName

        val planned = mutableListOf<PlannedFile>()
        val names = mutableSetOf<String>()

        fun add(
            versionItem: PlatformVersion,
            depProjectId: String,
            title: String,
            dependency: Boolean,
            depth: Int
        ) {
            val fileName = versionItem.platformFileName().trim()
            val safeName = simpleFileName(fileName)
                ?: throw IllegalArgumentException("平台返回的文件名不安全：$fileName")
            if (!names.add(safeName.lowercase())) {
                throw IllegalArgumentException("多个文件解析到同一文件名，已中止：$safeName")
            }
            val url = versionItem.platformDownloadUrl().trim()
            if (!isSafeHttpUrl(url)) {
                throw IllegalArgumentException("平台返回的下载链接不合法：$url")
            }
            val target = File(targetFolder, safeName)
            if (!LauncherPathGuard.canModify(target, version.getGameDir())) {
                throw IllegalArgumentException("目标文件未通过路径校验：${target.absolutePath}")
            }
            val relative = targetFolder.name.ifBlank { "" }.let { folder ->
                if (folder.isBlank()) safeName else "$folder/$safeName"
            }
            planned += PlannedFile(
                platform = versionItem.platform(),
                projectId = depProjectId,
                versionId = versionItem.platformId(),
                title = title,
                fileName = safeName,
                url = url,
                sha1 = versionItem.platformSha1(),
                size = runCatching { versionItem.platformFileSize() }.getOrDefault(-1L),
                target = target,
                relativePath = relative,
                dependency = dependency,
                depth = depth
            )
        }

        add(rootVersion, rootProject.id, rootProject.title, dependency = false, depth = 0)
        dependencyVersions.forEach { dep ->
            val depProject = dep.platformProjectId().ifBlank { "dependency" }
            add(dep, depProject, depProject, dependency = true, depth = 1)
        }

        return InstallPlan(
            instanceName = version.getVersionName(),
            kind = kind,
            platform = platform,
            projectTitle = rootProject.title,
            createdAt = System.currentTimeMillis(),
            files = planned,
            warnings = warnings.toMutableList().apply {
                if (planned.none { it.dependency } && kind.equals("mod", true)) {
                    add("未发现需要自动安装的 required 依赖（或依赖已内嵌）。")
                }
                if (mc.isBlank()) add("实例缺少 Minecraft 版本信息，兼容性过滤可能不准确。")
                if (loaderName.isNullOrBlank() && kind.equals("mod", true)) {
                    add("实例没有识别到模组加载器，依赖过滤仅按 Minecraft 版本进行。")
                }
            }
        )
    }

    fun planToJson(plan: InstallPlan, includeUrls: Boolean = true): JsonObject {
        val files = JsonArray()
        plan.files.forEach { file ->
            files.add(JsonObject().apply {
                put("fileName", file.fileName)
                put("url", if (includeUrls) file.url else "")
                put("size", file.size)
                put("sizeHuman", if (file.size >= 0) formatBytes(file.size) else "unknown")
                put("sha1", file.sha1.orEmpty())
                put("platform", file.platform.name.lowercase())
                put("projectId", file.projectId)
                put("versionId", file.versionId)
                put("title", file.title)
                put("dependency", file.dependency)
                put("depth", file.depth)
                put("targetPath", file.target.absolutePath)
            })
        }
        return JsonObject().apply {
            put("instance", plan.instanceName)
            put("kind", plan.kind)
            put("platform", plan.platform.name.lowercase())
            put("projectTitle", plan.projectTitle)
            put("createdAt", plan.createdAt)
            add("files", files)
            add("warnings", jsonArray(plan.warnings))
            put("backupRoot", plan.backupRootPattern)
        }
    }

    fun planToConfirmText(plan: InstallPlan): String = buildString {
        appendLine("将向实例「${plan.instanceName}」安装 ${plan.projectTitle}：")
        appendLine("类型：${plan.kind}；平台：${plan.platform.displayName}")
        plan.files.forEach { file ->
            appendLine(
                "- ${file.fileName}（${if (file.size >= 0) formatBytes(file.size) else "大小未知"}，" +
                        "依赖：${if (file.dependency) "是" else "否"}）"
            )
            appendLine("  URL: ${file.url}")
        }
        appendLine("目标目录：${plan.files.firstOrNull()?.target?.parentFile?.absolutePath ?: "未知"}")
        appendLine("同名文件会先备份到 ${plan.backupRootPattern}，不会写入 saves/。")
        plan.warnings.forEach { appendLine("提示：$it") }
    }

    fun planAffectedPaths(plan: InstallPlan): List<String> {
        val paths = mutableListOf<String>()
        plan.files.forEach { file ->
            //无论目标是否已存在，都要让确认弹窗明确列出将被写入的文件
            paths.add(file.target.absolutePath)
        }
        plan.files.firstOrNull()?.target?.parentFile?.absolutePath?.let { paths.add(it) }
        return paths.filter { it.isNotBlank() }.distinct()
    }

    fun limitSearchItems(items: List<SearchItem>, limit: Int): List<SearchItem> =
        items.sortedWith(compareByDescending<SearchItem> { it.downloads }.thenBy { it.title.lowercase() })
            .take(limit)

    fun sourceLimit(limit: Int): Int = min(20, (limit * 2).coerceAtLeast(limit))
}
