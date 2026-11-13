package com.movtery.zalithlauncher.ai.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** AI 工具请求切换界面；由 MainScreen 消费，避免工具直接持有 Activity。 */
object AiNavigationBridge {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val requests = _requests.asSharedFlow()

    fun request(screen: String): Boolean = _requests.tryEmit(screen)
}

/**
 * 让 AI 打开启动器内的页面。只做导航，不修改任何设置。
 */
object OpenScreenTool : AgentTool {
    override val name: String = "open_screen"
    override val description: String =
        "打开启动器里的页面，供用户查看或继续操作。可用 screen：home（主页）、versions（实例列表）、" +
            "settings（启动器设置）、download（下载页）、download_game（下载游戏）、download_modpack（整合包下载）、" +
            "download_mod（模组下载）、accounts（账号管理）、java（Java 管理）、controls（控制设置）、about（关于）、ai（AI 聊天）。"
    override val parametersSchema: JsonObject = objectSchema(
        required = listOf("screen"),
        properties = mapOf(
            "screen" to stringSchema(
                description = "要打开的页面",
                enumValues = listOf(
                    "home", "versions", "settings", "download", "download_game",
                    "download_modpack", "download_mod", "accounts", "java",
                    "controls", "about", "ai"
                )
            )
        )
    )
    override val riskLevel: RiskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(args: JsonObject): ToolResult {
        val screen = args.optText("screen")?.lowercase()
            ?: return failResult("缺少 screen 参数")
        val accepted = setOf(
            "home", "versions", "settings", "download", "download_game",
            "download_modpack", "download_mod", "accounts", "java",
            "controls", "about", "ai"
        )
        if (screen !in accepted) return failResult("不支持的页面：$screen")
        if (!AiNavigationBridge.request(screen)) return failResult("界面切换请求排队失败，请稍后重试")
        return okResult(
            summary = "已请求打开页面：$screen",
            body = JsonObject().apply { addProperty("screen", screen) }
        )
    }
}
