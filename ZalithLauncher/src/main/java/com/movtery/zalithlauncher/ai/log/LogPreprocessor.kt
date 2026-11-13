/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ai.log

/**
 * 把 Minecraft 日志压缩成适合模型阅读的片段。
 *
 * 保留：
 * - 异常首行、`Caused by`、`Exception in thread`、依赖缺失、`requires`、`Incompatible mod set`、`java.lang.` 等关键行；
 * - 关键行之后的少量堆栈上下文；
 * - 文件末尾 [tailLines] 行；
 * - 连续重复行只保留一次；
 * - 结果超过 [maxChars] 时截断并注明。
 */
object LogPreprocessor {

    private val EXCEPTION_HEAD = Regex(
        """^\s*(?:(?:Exception in thread|Caused by:)\s*)?(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)(?::|\s|$)"""
    )

    private val STACK_FRAME = Regex("""^\s+at\s+[\w.$/]+\(.*\)\s*$""")
    private val MORE_FRAMES = Regex("""^\s*\.\.\.\s+\d+\s+more\s*$""")

    private val KEYWORDS = listOf(
        "caused by",
        "exception in thread",
        "missing or unsupported mandatory dependencies",
        "incompatible mod set",
        "java.lang.",
        "java.lang.classnotfoundexception",
        "java.lang.noclassdeffounderror",
        "mod ... requires",
        "requires",
        "unsupported class version",
        "mixin apply failed",
        "failed to load mod",
        "crash report",
        "outofmemoryerror",
        "stackoverflowerror"
    )

    /**
     * @param text 原始日志
     * @param tailLines 额外保留的末尾行数，默认 200
     * @param maxChars 输出字符上限，默认 8000
     */
    fun preprocess(text: String, tailLines: Int = 200, maxChars: Int = 8000): String {
        if (text.isBlank()) return ""
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val safeTail = tailLines.coerceIn(0, 10_000)
        val safeMax = maxChars.coerceAtLeast(256)

        val selected = LinkedHashSet<Int>()
        val problemIndices = collectProblemIndices(lines)
        problemIndices.forEach { index ->
            selected.add(index)
            //异常行之后紧跟的堆栈对定位问题很有价值，最多带 4 行
            var context = 0
            var cursor = index + 1
            while (cursor < lines.size && context < 4) {
                val line = lines[cursor]
                if (STACK_FRAME.matches(line) || MORE_FRAMES.matches(line)) {
                    selected.add(cursor)
                    cursor++
                    context++
                } else {
                    break
                }
            }
        }

        val tailStart = (lines.size - safeTail).coerceAtLeast(0)
        for (index in tailStart until lines.size) {
            selected.add(index)
        }

        val ordered = selected.sorted()
        val cleaned = ArrayList<String>(ordered.size)
        for (index in ordered) {
            val line = lines[index]
            if (cleaned.isNotEmpty() && cleaned.last() == line) continue
            cleaned.add(line)
        }

        //去掉首尾空行
        while (cleaned.isNotEmpty() && cleaned.first().isBlank()) cleaned.removeAt(0)
        while (cleaned.isNotEmpty() && cleaned.last().isBlank()) cleaned.removeAt(cleaned.lastIndex)
        if (cleaned.isEmpty()) return ""

        val joined = cleaned.joinToString("\n")
        if (joined.length <= safeMax) return joined

        val note = "\n... [日志过长，已截断：原始 ${text.length} 字符 / ${lines.size} 行；" +
                "仅保留异常关键行与末尾日志] ...\n"
        if (safeMax <= note.length + 80) {
            return joined.take(safeMax)
        }

        val budget = safeMax - note.length
        val headBudget = budget * 2 / 5
        val tailBudget = budget - headBudget

        val head = ArrayList<String>()
        var headUsed = 0
        for (line in cleaned) {
            if (headUsed + line.length + 1 > headBudget) break
            head.add(line)
            headUsed += line.length + 1
        }

        val tail = ArrayList<String>()
        var tailUsed = 0
        for (index in cleaned.lastIndex downTo 0) {
            val line = cleaned[index]
            if (tailUsed + line.length + 1 > tailBudget) break
            tail.add(0, line)
            tailUsed += line.length + 1
        }

        val result = buildString {
            if (head.isNotEmpty()) append(head.joinToString("\n"))
            append(note)
            if (tail.isNotEmpty()) append(tail.joinToString("\n"))
        }
        if (head.isEmpty() && tail.isEmpty()) {
            //单行超长日志：无法按行装入 head/tail 预算，退化为字符截断
            return joined.take(safeMax)
        }
        return if (result.length <= safeMax) result else result.take(safeMax)
    }

    /**
     * 只提取问题行（不带末尾日志），用于工具结果里的“关键行”摘要。
     */
    fun collectProblems(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = ArrayList<String>()
        for (line in collectProblemIndices(lines).map { lines[it] }) {
            if (line.isBlank()) continue
            if (result.isNotEmpty() && result.last() == line) continue
            result.add(line)
            if (result.size >= 100) break
        }
        return result
    }

    // ------------------------------------------------------------------ internals

    private fun collectProblemIndices(lines: List<String>): List<Int> {
        val result = ArrayList<Int>()
        lines.forEachIndexed { index, rawLine ->
            if (isProblemLine(rawLine)) result.add(index)
        }
        return result
    }

    private fun isProblemLine(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty()) return false
        if (EXCEPTION_HEAD.containsMatchIn(line)) return true
        val lower = line.lowercase()
        return KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
