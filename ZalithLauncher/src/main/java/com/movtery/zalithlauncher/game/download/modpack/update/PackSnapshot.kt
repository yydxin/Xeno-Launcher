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
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.MCIM_CURSEFORGE_API
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.curseForgeCDNUrl
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackMeta
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPaths
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPlatform
import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.safeBodyAsText
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PackSnapshot"

/**
 * 新版整合包清单中的一个文件。
 * 二选一：`source` 是包内自带文件（overrides），或 `urls` 是清单声明的下载地址。
 */
internal data class PackEntry(
    val path: String,
    val source: File? = null,
    val urls: List<String> = emptyList(),
    val sha1: String? = null,
    val size: Long = 0L
)

internal data class StagedPack(
    val platform: ModpackPlatform,
    val name: String,
    val version: String,
    val gameVersion: String?,
    val loader: String?,
    val entries: List<PackEntry>
)

/**
 * 没有来源记录时，本地清单仅用于展示。
 * 注意：这里刻意不读取 projectID/fileID，绝不据此推断更新来源。
 */
internal data class LocalPackHint(
    val name: String,
    val version: String,
    val kind: String,
    val gameVersion: String?,
    val loader: String?
)

/** 旧清单基线：路径 → 清单已知的旧 SHA-1；null 表示只知道文件存在、不知道哈希 */
internal data class Baseline(
    val hashes: Map<String, String?>
)

internal enum class ConflictKind {
    /** config/ 下与旧清单哈希不一致，用户修改过 */
    CONFIG_MODIFIED,

    /** 新清单要写入的路径在旧清单之外，属于用户文件 */
    USER_FILE
}

internal data class UpdateConflict(
    val path: String,
    val kind: ConflictKind,
    val newPath: String
)

internal data class WriteOp(
    val path: String,
    val destPath: String,
    val entry: PackEntry,
    val conflict: ConflictKind?
)

internal data class ChangeSet(
    val added: List<String>,
    val changed: List<String>,
    val removed: List<String>,
    val writes: List<WriteOp>,
    val conflicts: List<UpdateConflict>,
    /** 旧清单里被用户修改过的 config 文件，更新时保留不删 */
    val keptUserConfig: List<String>,
    val skippedProtected: List<String>,
    val userFilesKept: Int,
    /** 需要网络下载的字节数（不含新版整合包压缩包本身） */
    val downloadBytes: Long
) {
    val isEmpty: Boolean get() = writes.isEmpty() && removed.isEmpty()
    val operationCount: Int get() = writes.size + removed.size
}

/**
 * 新版清单解析、旧基线读取与文件级 diff。
 *
 * 新清单来源：下载的新版整合包解压后的 `modrinth.index.json` / `manifest.json`；
 * 旧基线来源：本地清单（若存在）优先，其余由来源记录 `fileScope + 本地哈希` 补齐。
 */
internal object PackSnapshot {
    private const val HASH_PROGRESS_STEP = 50
    private const val DIFF_PROGRESS_STEP = 100

    // ---------------- 新版清单 ----------------

    suspend fun stage(
        archive: File,
        workDir: File,
        expectedPlatform: ModpackPlatform,
        onProgress: (String) -> Unit = {}
    ): StagedPack = withContext(Dispatchers.IO) {
        val extracted = File(workDir, "extracted")
        if (extracted.exists()) extracted.deleteRecursively()
        extracted.mkdirs()
        PackArchive.extractZip(archive, extracted, onProgress)

        val root = PackPlanParser.resolveRoot(extracted)
        val detected = PackPlanParser.detect(
            root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        ) ?: throw IllegalStateException("无法识别新版整合包格式：缺少 modrinth.index.json 或 manifest.json")
        if (detected != expectedPlatform) {
            throw IllegalStateException(
                "新版整合包格式为 ${detected.displayName}，与来源记录 ${expectedPlatform.displayName} 不一致"
            )
        }

        when (detected) {
            ModpackPlatform.MODRINTH -> stageModrinth(root, onProgress)
            ModpackPlatform.CURSEFORGE -> stageCurseForge(root, onProgress)
            else -> throw IllegalStateException("${detected.displayName} 整合包没有可用的在线更新清单")
        }
    }

    private suspend fun stageModrinth(
        root: File,
        onProgress: (String) -> Unit
    ): StagedPack = withContext(Dispatchers.IO) {
        val index = readJson(File(root, "modrinth.index.json"))
        val entries = LinkedHashMap<String, PackEntry>()

        addOverrides(root, "overrides", entries, onProgress)
        addOverrides(root, "client-overrides", entries, onProgress)

        val downloads = mutableListOf<PackEntry>()
        index.getAsJsonArray("files")?.forEach { element ->
            val file = element.asJsonObject
            val env = file.getAsJsonObject("env")
            if (env?.string("client").equals("unsupported", ignoreCase = true)) return@forEach
            val path = file.string("path") ?: return@forEach
            val urls = file.getAsJsonArray("downloads")
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                .orEmpty()
            if (urls.isEmpty()) return@forEach
            downloads += PackEntry(
                path = path,
                source = null,
                urls = urls,
                sha1 = file.getAsJsonObject("hashes")?.string("sha1"),
                size = file.get("fileSize")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            )
        }
        //下载文件与 overrides 同路径时，下载文件优先（与安装顺序一致）
        downloads.forEach { entries[it.path] = it }

        val dependencies = index.getAsJsonObject("dependencies")
        val loaderRaw = dependencies?.entrySet()
            ?.firstOrNull { it.key != "minecraft" }
            ?.let { "${it.key} ${it.value.asString}" }

        StagedPack(
            platform = ModpackPlatform.MODRINTH,
            name = index.string("name") ?: "Modrinth 整合包",
            version = index.string("versionNumber").orEmpty(),
            gameVersion = dependencies?.string("minecraft"),
            loader = normalizeLoaderName(loaderRaw),
            entries = entries.values.sortedBy { it.path }
        )
    }

    private suspend fun stageCurseForge(
        root: File,
        onProgress: (String) -> Unit
    ): StagedPack = withContext(Dispatchers.IO) {
        val manifest = readJson(File(root, "manifest.json"))
        val entries = LinkedHashMap<String, PackEntry>()

        val overridesName = manifest.string("overrides") ?: "overrides"
        addOverrides(root, overridesName, entries, onProgress)
        if (overridesName != "client-overrides") {
            addOverrides(root, "client-overrides", entries, onProgress)
        }

        val manifestFiles = manifest.getAsJsonArray("files")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
            .orEmpty()
        if (manifestFiles.isEmpty()) {
            throw IllegalStateException("CurseForge 清单没有记录任何文件")
        }

        val total = manifestFiles.size
        val counter = AtomicInteger(0)
        val cacheMutex = Mutex()
        val folderCache = mutableMapOf<String, String>()

        suspend fun folderFor(projectId: String): String {
            cacheMutex.withLock { folderCache[projectId] }?.let { return it }
            val folder = resolveCurseForgeFolder(projectId)
            cacheMutex.withLock { folderCache[projectId] = folder }
            return folder
        }

        val semaphore = Semaphore(4)
        val resolved = coroutineScope {
            manifestFiles.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val index = counter.incrementAndGet()
                        if (index % 5 == 0 || index == total) {
                            onProgress("正在解析 CurseForge 文件（$index/$total）")
                        }
                        try {
                            resolveCurseForgeItem(item) { projectId -> folderFor(projectId) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            val required = item.get("required")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                            if (required) {
                                throw IllegalStateException(
                                    "无法解析 CurseForge 文件 ${item.string("projectID")}/${item.string("fileID")}：" +
                                            readableMessage(e),
                                    e
                                )
                            }
                            Logger.warning(
                                TAG,
                                "跳过无法解析的非必需 CurseForge 文件：${item.string("projectID")}/${item.string("fileID")}",
                                e
                            )
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        resolved.forEach { entries[it.path] = it }

        val minecraft = manifest.getAsJsonObject("minecraft")
        val modLoaders = minecraft?.getAsJsonArray("modLoaders")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
            .orEmpty()
        val primaryLoader = modLoaders.firstOrNull {
            it.get("primary")?.takeIf { value -> !value.isJsonNull }?.asBoolean == true
        } ?: modLoaders.firstOrNull()

        StagedPack(
            platform = ModpackPlatform.CURSEFORGE,
            name = manifest.string("name") ?: "CurseForge 整合包",
            version = manifest.string("version").orEmpty(),
            gameVersion = minecraft?.string("version"),
            loader = normalizeLoaderName(primaryLoader?.string("id")),
            entries = entries.values.sortedBy { it.path }
        )
    }

    private suspend fun resolveCurseForgeItem(
        item: JsonObject,
        folderFor: suspend (String) -> String
    ): PackEntry {
        val projectId = item.string("projectID")
            ?: throw IllegalStateException("清单项缺少 projectID")
        val fileId = item.string("fileID")
            ?: throw IllegalStateException("清单项缺少 fileID")
        val fileIdNumber = fileId.toIntOrNull()
            ?: throw IllegalStateException("非法的 CurseForge 文件 ID：$fileId")
        val fallbackName = item.string("fileName")
        val manifestUrl = item.string("url")

        val data = getJsonObject("$MCIM_CURSEFORGE_API/mods/$projectId/files/$fileId")
            .getAsJsonObject("data")
            ?: throw IllegalStateException("CurseForge 文件接口没有返回 data")

        val fileName = data.string("fileName")
            ?: fallbackName
            ?: throw IllegalStateException("CurseForge 文件缺少文件名")
        val folder = folderFor(projectId)
        val url = data.string("downloadUrl")
            ?.takeIf { it.isNotBlank() }
            ?: manifestUrl?.takeIf { it.isNotBlank() }
            ?: curseForgeCDNUrl(fileIdNumber, fileName)
        val sha1 = data.getAsJsonArray("hashes")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
            ?.firstOrNull { hash -> hash.get("algo")?.takeIf { !it.isJsonNull }?.asInt == 1 }
            ?.string("value")

        return PackEntry(
            path = "$folder/$fileName",
            source = null,
            urls = listOf(url),
            sha1 = sha1,
            size = data.get("fileLength")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        )
    }

    private suspend fun resolveCurseForgeFolder(projectId: String): String {
        return try {
            val classId = getJsonObject("$MCIM_CURSEFORGE_API/mods/$projectId")
                .getAsJsonObject("data")
                ?.get("classId")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
            when (classId) {
                12 -> "resourcepacks"
                6552 -> "shaderpacks"
                else -> "mods"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.warning(TAG, "无法获取 CurseForge 项目类别，按 mods/ 处理：$projectId", e)
            "mods"
        }
    }

    private suspend fun getJsonObject(url: String): JsonObject {
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

    private fun addOverrides(
        root: File,
        folderName: String,
        entries: MutableMap<String, PackEntry>,
        onProgress: (String) -> Unit
    ) {
        val folder = File(root, folderName)
        if (!folder.isDirectory) return
        var count = 0
        folder.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val path = file.relativeTo(folder).invariantSeparatorsPath
                if (path.isBlank() || ModpackPaths.isMetadataPath(path)) return@forEach
                count++
                entries[path] = PackEntry(
                    path = path,
                    source = file,
                    urls = emptyList(),
                    sha1 = hashFile(file),
                    size = file.length()
                )
                if (count % HASH_PROGRESS_STEP == 0) {
                    onProgress("正在读取整合包自带文件（$count 个）")
                }
            }
    }

    // ---------------- 旧基线 ----------------

    /**
     * 旧基线：
     * - 来源记录 fileScope 中的路径 + 本地文件哈希；
     * - 如果实例里保留了旧的 modrinth.index.json，则用其中声明的 SHA-1 覆盖，从而能够识别用户修改；
     * - CurseForge 的 manifest.json 不提供路径，不做猜测。
     */
    fun localBaseline(instanceDir: File, meta: ModpackMeta): Baseline {
        val hashes = LinkedHashMap<String, String?>()
        meta.fileScope.forEach { rawPath ->
            val path = ModpackPaths.normalize(rawPath) ?: return@forEach
            if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                return@forEach
            }
            val file = ModpackPaths.resolve(instanceDir, path)
            hashes[path] = file?.takeIf { it.isFile }?.let { hashFile(it) }
        }

        val localIndex = File(instanceDir, "modrinth.index.json")
        if (localIndex.isFile) {
            runCatching {
                val index = readJson(localIndex)
                index.getAsJsonArray("files")?.forEach fileLoop@{ element ->
                    val file = runCatching { element.asJsonObject }.getOrNull() ?: return@fileLoop
                    val path = ModpackPaths.normalize(file.string("path") ?: return@fileLoop)
                        ?: return@fileLoop
                    if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                        return@fileLoop
                    }
                    val sha1 = file.getAsJsonObject("hashes")?.string("sha1") ?: return@fileLoop
                    hashes[path] = sha1
                }
            }.onFailure { error ->
                Logger.warning(TAG, "读取本地 modrinth.index.json 失败，仅使用 fileScope 作为旧基线。", error)
            }
        }

        return Baseline(hashes)
    }

    /**
     * 没有来源记录时，只从本地清单读取展示用的名称/版本。
     */
    fun readLocalHint(instanceDir: File): LocalPackHint? {
        val modrinthIndex = File(instanceDir, "modrinth.index.json")
        if (modrinthIndex.isFile) {
            val hint = runCatching {
                val index = readJson(modrinthIndex)
                val dependencies = index.getAsJsonObject("dependencies")
                val loaderRaw = dependencies?.entrySet()
                    ?.firstOrNull { it.key != "minecraft" }
                    ?.let { "${it.key} ${it.value.asString}" }
                LocalPackHint(
                    name = index.string("name") ?: "Modrinth 整合包",
                    version = index.string("versionNumber").orEmpty(),
                    kind = "Modrinth",
                    gameVersion = dependencies?.string("minecraft"),
                    loader = normalizeLoaderName(loaderRaw)
                )
            }.onFailure { error ->
                Logger.warning(TAG, "读取本地 modrinth.index.json 展示信息失败。", error)
            }.getOrNull()
            if (hint != null) return hint
        }

        val manifestFile = File(instanceDir, "manifest.json")
        if (manifestFile.isFile) {
            return runCatching {
                val manifest = readJson(manifestFile)
                val minecraft = manifest.getAsJsonObject("minecraft")
                val loaders = minecraft?.getAsJsonArray("modLoaders")
                    ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
                    .orEmpty()
                val primary = loaders.firstOrNull {
                    it.get("primary")?.takeIf { value -> !value.isJsonNull }?.asBoolean == true
                } ?: loaders.firstOrNull()
                LocalPackHint(
                    name = manifest.string("name") ?: "CurseForge 整合包",
                    version = manifest.string("version").orEmpty(),
                    kind = "CurseForge",
                    gameVersion = minecraft?.string("version"),
                    loader = normalizeLoaderName(primary?.string("id"))
                )
            }.onFailure { error ->
                Logger.warning(TAG, "读取本地 manifest.json 展示信息失败。", error)
            }.getOrNull()
        }

        return null
    }

    fun hasLocalManifest(instanceDir: File): Boolean =
        File(instanceDir, "modrinth.index.json").isFile || File(instanceDir, "manifest.json").isFile

    // ---------------- diff ----------------

    fun diff(
        instanceDir: File,
        baseline: Baseline,
        staged: StagedPack,
        onProgress: (String) -> Unit = {}
    ): ChangeSet {
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val writes = mutableListOf<WriteOp>()
        val conflicts = mutableListOf<UpdateConflict>()
        val keptUserConfig = mutableListOf<String>()
        val skippedProtected = mutableListOf<String>()
        var downloadBytes = 0L

        val normalizedEntries = LinkedHashMap<String, PackEntry>()
        staged.entries.forEach { entry ->
            val path = ModpackPaths.normalize(entry.path)
            if (path == null ||
                ModpackPaths.isProtectedPath(path) ||
                ModpackPaths.isMetadataPath(path)
            ) {
                skippedProtected += entry.path
                return@forEach
            }
            normalizedEntries[path] = entry
        }

        normalizedEntries.entries.sortedBy { it.key }.forEachIndexed { index, (path, entry) ->
            if (index % DIFF_PROGRESS_STEP == 0) {
                onProgress("正在比较整合包文件（${index}/${normalizedEntries.size}）")
            }
            val target = ModpackPaths.resolve(instanceDir, path)
            if (target == null || target.isDirectory) {
                skippedProtected += path
                return@forEachIndexed
            }

            val exists = target.isFile
            val localSha1 = if (exists) hashFile(target) else null
            val newSha1 = entry.sha1 ?: entry.source?.takeIf { it.isFile }?.let { hashFile(it) }

            if (exists) {
                val same = when {
                    newSha1 != null && localSha1 != null ->
                        newSha1.equals(localSha1, ignoreCase = true)
                    entry.source != null ->
                        ModpackUpdater.sameContent(entry.source, target)
                    else -> false
                }
                if (same) return@forEachIndexed

                changed += path
                val baselineKnown = baseline.hashes.containsKey(path)
                val baselineSha1 = baseline.hashes[path]
                val configModified = ModpackPaths.isConfigPath(path) &&
                        baselineSha1 != null &&
                        localSha1 != null &&
                        !baselineSha1.equals(localSha1, ignoreCase = true)
                val conflictKind = when {
                    configModified -> ConflictKind.CONFIG_MODIFIED
                    !baselineKnown -> ConflictKind.USER_FILE
                    else -> null
                }
                val destPath = if (conflictKind != null) "$path.new" else path
                if (conflictKind != null) {
                    conflicts += UpdateConflict(path = path, kind = conflictKind, newPath = destPath)
                }
                writes += WriteOp(path = path, destPath = destPath, entry = entry, conflict = conflictKind)
            } else {
                added += path
                writes += WriteOp(path = path, destPath = path, entry = entry, conflict = null)
            }

            if (entry.urls.isNotEmpty()) {
                downloadBytes += entry.size.coerceAtLeast(0L)
            }
        }

        baseline.hashes.entries.sortedBy { it.key }.forEach { (path, expectedSha1) ->
            if (path in normalizedEntries) return@forEach
            if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                return@forEach
            }
            val target = ModpackPaths.resolve(instanceDir, path) ?: return@forEach
            if (!target.isFile) return@forEach

            val localSha1 = hashFile(target)
            if (ModpackPaths.isConfigPath(path) &&
                expectedSha1 != null &&
                !expectedSha1.equals(localSha1, ignoreCase = true)
            ) {
                //用户修改过的 config 文件：即使新清单不再包含，也保留
                keptUserConfig += path
                return@forEach
            }
            removed += path
        }

        val destinationCollision = writes.groupBy { it.destPath }
            .entries.firstOrNull { it.value.size > 1 }
            ?.key
        if (destinationCollision != null) {
            throw IllegalStateException("更新目标路径冲突：$destinationCollision")
        }

        return ChangeSet(
            added = added.distinct().sorted(),
            changed = changed.distinct().sorted(),
            removed = removed.distinct().sorted(),
            writes = writes.sortedBy { it.destPath },
            conflicts = conflicts.sortedBy { it.path },
            keptUserConfig = keptUserConfig.distinct().sorted(),
            skippedProtected = skippedProtected.distinct().sorted(),
            userFilesKept = countUserFiles(instanceDir, baseline.hashes.keys),
            downloadBytes = downloadBytes
        )
    }

    // ---------------- IO / 工具 ----------------

    private fun hashFile(file: File): String = ModpackUpdater.sha1(file)

    private fun countUserFiles(instanceDir: File, baselinePaths: Set<String>): Int {
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(instanceDir)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            dir.listFiles()?.forEach fileLoop@{ file ->
                val relative = file.relativeTo(instanceDir).invariantSeparatorsPath
                if (file.isDirectory) {
                    if (!ModpackPaths.isProtectedPath(relative) &&
                        !ModpackPaths.isMetadataPath(relative)
                    ) {
                        stack.add(file)
                    }
                    return@fileLoop
                }
                val path = ModpackPaths.normalize(relative) ?: return@fileLoop
                if (ModpackPaths.isProtectedPath(path) || ModpackPaths.isMetadataPath(path)) {
                    return@fileLoop
                }
                if (path !in baselinePaths) count++
            }
        }
        return count
    }

    private fun normalizeLoaderName(raw: String?): String? {
        val key = PackUpdateSource.loaderKey(raw)
        if (key != null) {
            ModLoader.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let {
                return it.name
            }
        }
        return raw?.substringBefore(' ')?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun readJson(file: File): JsonObject =
        JsonParser.parseString(file.readText()).asJsonObject

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    private fun readableMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
}
