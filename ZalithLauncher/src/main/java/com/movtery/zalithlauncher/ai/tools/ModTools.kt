package com.movtery.zalithlauncher.ai.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 批量启用/禁用模组。禁用通过 `.disabled` 后缀重命名实现，可逆；不读取、不删除模组内容。
 * 只允许操作实例 mods/ 目录内的文件，禁止路径穿越与符号链接逃逸。
 */
object SetModsEnabledTool : AgentTool {
    override val name: String = "set_mods_enabled"
    override val description: String =
        "批量启用或禁用指定实例的模组（可一次禁用多个崩溃嫌疑模组）。只重命名 mods/ 下的文件为 .disabled，不删除文件；" +
            "不读存档、不碰其他目录。实例名省略时使用当前实例。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("files", "enabled"),
        properties = mapOf(
            "instance" to stringSchema("实例名称；省略则使用当前实例"),
            "files" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "mods 目录中的文件名，例如 sodium.jar；可传多个")
                add("items", JsonObject().apply { addProperty("type", "string") })
            },
            "enabled" to booleanSchema("true 启用，false 禁用", default = false)
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.LOW_RISK_WRITE

    override suspend fun execute(args: JsonObject): ToolResult {
        val version = resolveVersion(args.optText("instance")) ?: return notFoundResult(args.optText("instance"))
        val enabled = args.optBoolean("enabled") ?: false
        val array: JsonArray = args.getAsJsonArray("files")
            ?: return failResult("files 必须是数组")
        if (array.size() == 0) return failResult("files 不能为空")

        val dir = modsDir(version).canonicalFile
        if (!dir.isDirectory) return failResult("实例 mods 目录不存在：${dir.absolutePath}")
        val rootPath = dir.toPath()

        val results = JsonArray()
        var okCount = 0
        array.forEach { element ->
            val raw = runCatching { element.asString }.getOrNull()?.trim().orEmpty()
            val item = JsonObject()
            item.addProperty("file", raw)
            if (raw.isBlank() || raw.contains('/') || raw.contains('\\') || File(raw).isAbsolute) {
                item.addProperty("ok", false)
                item.addProperty("error", "非法文件名")
                results.add(item)
                return@forEach
            }
            val baseName = raw.removeSuffix(".disabled")
            val sourceName = if (enabled) "$baseName.disabled" else baseName
            val destName = if (enabled) baseName else "$baseName.disabled"
            val source = File(dir, sourceName)
            val dest = File(dir, destName)
            val sourceCanonical = runCatching { source.canonicalFile }.getOrNull()
            val destCanonical = runCatching { dest.canonicalFile }.getOrNull()
            if (sourceCanonical == null || destCanonical == null ||
                !sourceCanonical.toPath().startsWith(rootPath) ||
                !destCanonical.toPath().startsWith(rootPath)
            ) {
                item.addProperty("ok", false)
                item.addProperty("error", "路径越界")
                results.add(item)
                return@forEach
            }
            when {
                !source.exists() && dest.exists() -> {
                    item.addProperty("ok", true)
                    item.addProperty("changed", false)
                    item.addProperty("message", if (enabled) "已经是启用状态" else "已经是禁用状态")
                    okCount++
                }
                !source.isFile -> {
                    item.addProperty("ok", false)
                    item.addProperty("error", "文件不存在：$sourceName")
                }
                dest.exists() -> {
                    item.addProperty("ok", false)
                    item.addProperty("error", "目标文件已存在：$destName")
                }
                else -> runCatching {
                    Files.move(source.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }.recoverCatching {
                    Files.move(source.toPath(), dest.toPath())
                }.onSuccess {
                    item.addProperty("ok", true)
                    item.addProperty("changed", true)
                    item.addProperty("message", if (enabled) "已启用" else "已禁用")
                    okCount++
                }.onFailure {
                    item.addProperty("ok", false)
                    item.addProperty("error", it.message ?: it::class.java.simpleName)
                }
            }
            results.add(item)
        }

        val body = JsonObject().apply {
            addProperty("instance", version.getVersionName())
            addProperty("okCount", okCount)
            add("results", results)
        }
        return if (okCount > 0) {
            okResult(
                summary = "已${if (enabled) "启用" else "禁用"} $okCount 个模组（实例 ${version.getVersionName()}）",
                body = body
            )
        } else {
            failResult("没有模组被修改，请检查文件名", body)
        }
    }
}
