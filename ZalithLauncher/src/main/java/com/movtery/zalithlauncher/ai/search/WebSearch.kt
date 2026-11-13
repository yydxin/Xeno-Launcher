/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.search

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.ai.AiSettingsStore
import com.movtery.zalithlauncher.ai.WebSearchProvider
import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import com.movtery.zalithlauncher.utils.network.safeBodyAsText
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI

/** 一条网页搜索结果 */
data class WebResult(
    val title: String,
    val url: String,
    val snippet: String
)

/** 搜索配置/网络/解析错误。message 已分类为可直接展示的中文，不含 API Key。 */
class WebSearchException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 联网搜索客户端。
 *
 * 服务商：
 * - TAVILY：POST https://api.tavily.com/search，body 含 api_key/query/max_results
 * - BRAVE：GET https://api.search.brave.com/res/v1/web/search，X-Subscription-Token
 * - SERPER：POST https://google.serper.dev/search，X-API-KEY，body {q,num}
 * - CUSTOM：用户 endpoint，POST {"query","count"}，Authorization: Bearer <key>
 *
 * 所有请求复用项目已有的 GLOBAL_CLIENT（OkHttp 引擎 + 镜像/超时配置）。
 * 解析失败时如实报错，绝不返回伪造结果。
 */
object WebSearchClient {
    private const val TAVILY_URL = "https://api.tavily.com/search"
    private const val BRAVE_URL = "https://api.search.brave.com/res/v1/web/search"
    private const val SERPER_URL = "https://google.serper.dev/search"

    private val gson = Gson()

    suspend fun search(query: String, count: Int): List<WebResult> {
        val keyword = query.trim()
        if (keyword.isEmpty()) throw WebSearchException("搜索关键词不能为空。")
        val safeCount = count.coerceIn(1, 10)

        val config = runCatching { AiSettingsStore.searchConfig() }.getOrElse { e ->
            throw WebSearchException("读取联网搜索配置失败：${e.message ?: e::class.java.simpleName}", e)
        }
        val apiKey = runCatching { AiSettingsStore.searchApiKey() }.getOrElse { e ->
            throw WebSearchException("读取搜索服务商 API Key 失败：${e.message ?: e::class.java.simpleName}", e)
        }.trim()
        if (apiKey.isEmpty()) {
            throw WebSearchException(
                "未配置搜索服务商：请到「设置 → AI 服务 → 联网搜索」选择服务商并填写、保存 API Key。"
            )
        }

        return try {
            when (config.provider) {
                WebSearchProvider.TAVILY -> searchTavily(keyword, safeCount, apiKey)
                WebSearchProvider.BRAVE -> searchBrave(keyword, safeCount, apiKey)
                WebSearchProvider.SERPER -> searchSerper(keyword, safeCount, apiKey)
                WebSearchProvider.CUSTOM -> searchCustom(keyword, safeCount, apiKey, config.endpoint)
            }.distinctBy { it.url }.take(safeCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebSearchException) {
            throw e
        } catch (e: ResponseException) {
            throw httpError(config.provider, e)
        } catch (e: IOException) {
            throw WebSearchException("联网搜索网络请求失败：${e.message ?: "连接中断"}", e)
        } catch (e: Throwable) {
            throw WebSearchException("联网搜索失败：${e.message ?: e::class.java.simpleName}", e)
        }
    }

    // ------------------------------------------------------------------ providers

    private suspend fun searchTavily(query: String, count: Int, apiKey: String): List<WebResult> {
        val body = JsonObject().apply {
            addProperty("api_key", apiKey)
            addProperty("query", query)
            addProperty("max_results", count)
        }
        val text = GLOBAL_CLIENT.post(TAVILY_URL) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(gson.toJson(body))
        }.safeBodyAsText()
        val root = parseObject(text, "Tavily")
        val results = root.getAsJsonArray("results")
            ?: throw WebSearchException("Tavily 返回格式异常：缺少 results 数组。")
        return results.mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val url = item.text("url") ?: return@mapNotNull null
            WebResult(
                title = item.text("title").orEmpty().ifBlank { url },
                url = url,
                snippet = item.text("content") ?: item.text("snippet").orEmpty()
            )
        }
    }

    private suspend fun searchBrave(query: String, count: Int, apiKey: String): List<WebResult> {
        val text = GLOBAL_CLIENT.get(BRAVE_URL) {
            header("X-Subscription-Token", apiKey)
            header("Accept", "application/json")
            parameter("q", query)
            parameter("count", count)
        }.safeBodyAsText()
        val root = parseObject(text, "Brave")
        val results = root.getAsJsonObject("web")?.getAsJsonArray("results")
            ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val url = item.text("url") ?: return@mapNotNull null
            WebResult(
                title = item.text("title").orEmpty().ifBlank { url },
                url = url,
                snippet = item.text("description").orEmpty()
            )
        }
    }

    private suspend fun searchSerper(query: String, count: Int, apiKey: String): List<WebResult> {
        val body = JsonObject().apply {
            addProperty("q", query)
            addProperty("num", count)
        }
        val text = GLOBAL_CLIENT.post(SERPER_URL) {
            header("X-API-KEY", apiKey)
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(gson.toJson(body))
        }.safeBodyAsText()
        val root = parseObject(text, "Serper")
        val results = root.getAsJsonArray("organic") ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val url = item.text("link") ?: item.text("url") ?: return@mapNotNull null
            WebResult(
                title = item.text("title").orEmpty().ifBlank { url },
                url = url,
                snippet = item.text("snippet").orEmpty()
            )
        }
    }

    private suspend fun searchCustom(
        query: String,
        count: Int,
        apiKey: String,
        endpoint: String
    ): List<WebResult> {
        val url = endpoint.trim()
        if (!isSafeHttpUrl(url)) {
            throw WebSearchException("自定义搜索服务商的 endpoint 未配置或不是合法的 http/https 链接。")
        }
        val body = JsonObject().apply {
            addProperty("query", query)
            addProperty("count", count)
        }
        val text = GLOBAL_CLIENT.post(url) {
            header("Authorization", "Bearer $apiKey")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(gson.toJson(body))
        }.safeBodyAsText()
        val element = runCatching { JsonParser.parseString(text) }.getOrElse { e ->
            throw WebSearchException("无法解析自定义搜索服务返回的 JSON：${e.message ?: "格式错误"}", e)
        }
        val items = findCustomResults(element)
            ?: throw WebSearchException(
                "无法解析自定义搜索服务返回的 JSON：未找到 results/organic/items/data/hits 数组。" +
                        "请让服务端返回常见的搜索结果数组。"
            )
        return items.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val urlValue = obj.text("url") ?: obj.text("link") ?: obj.text("href")
                ?: return@mapNotNull null
            val title = obj.text("title") ?: obj.text("name") ?: obj.text("heading").orEmpty()
            val snippet = obj.text("snippet") ?: obj.text("description") ?: obj.text("content")
                ?: obj.text("summary").orEmpty()
            WebResult(title = title.ifBlank { urlValue }, url = urlValue, snippet = snippet)
        }
    }

    // ------------------------------------------------------------------ parsing

    private fun findCustomResults(element: JsonElement): List<JsonElement>? {
        if (element.isJsonArray) {
            return element.asJsonArray.toList()
        }
        val root = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        listOf("results", "organic", "items", "data", "hits").forEach { key ->
            root.get(key)?.takeIf { it.isJsonArray }?.let { return it.asJsonArray.toList() }
        }
        root.getAsJsonObject("web")?.get("results")?.takeIf { it.isJsonArray }?.let {
            return it.asJsonArray.toList()
        }
        //有些自定义服务会把结果放在 data.results
        root.getAsJsonObject("data")?.get("results")?.takeIf { it.isJsonArray }?.let {
            return it.asJsonArray.toList()
        }
        return null
    }

    private fun parseObject(text: String, provider: String): JsonObject {
        if (text.isBlank()) throw WebSearchException("$provider 返回了空响应。")
        val element = runCatching { JsonParser.parseString(text) }.getOrElse { e ->
            throw WebSearchException("无法解析 $provider 返回的 JSON：${e.message ?: "格式错误"}", e)
        }
        return element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw WebSearchException("$provider 返回的不是 JSON 对象，无法解析搜索结果。")
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun isSafeHttpUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo.isNullOrBlank()
    }.getOrDefault(false)

    // ------------------------------------------------------------------ errors

    private fun httpError(provider: WebSearchProvider, e: ResponseException): WebSearchException {
        val code = e.response.status.value
        val name = provider.displayName
        val message = when (code) {
            401, 403 -> "$name 鉴权失败（HTTP $code）：请检查搜索 API Key 是否正确、是否有权限。"
            429 -> "$name 请求被限流（HTTP 429），请稍后再试或降低搜索频率。"
            in 500..599 -> "$name 服务暂时不可用（HTTP $code），请稍后重试。"
            400, 422 -> "$name 拒绝了搜索请求（HTTP $code），请检查搜索关键词或配置。"
            else -> "$name 返回 HTTP $code。"
        }
        return WebSearchException(message, e)
    }
}
