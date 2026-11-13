package com.movtery.zalithlauncher.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式解析/URL/错误体的 JVM 回归测试。
 * 覆盖服务商常见脏数据：null usage、空 delta、空 choices、HTML 错误体、注释行、[DONE]。
 */
class AiParsingTest {

    @Test
    fun `done comment empty are ignored`() {
        assertNull(OpenAiEventParser.parse("[DONE]"))
        assertNull(OpenAiEventParser.parse(": keep-alive"))
        assertNull(OpenAiEventParser.parse(""))
        assertNull(OpenAiEventParser.parse("{}"))
    }

    @Test
    fun `null usage and null delta do not crash`() {
        val delta = OpenAiEventParser.parse(
            """{"choices":[{"delta":null,"finish_reason":null}],"usage":null}"""
        )
        assertNull(delta?.content)
        assertNull(delta?.usage)
    }

    @Test
    fun `empty choices do not crash`() {
        val delta = OpenAiEventParser.parse("""{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}""")
        assertNotNull(delta)
        assertEquals(1L, delta!!.usage?.prompt)
        assertEquals(2L, delta.usage?.completion)
    }

    @Test
    fun `content reasoning and tool call fragments`() {
        val text = OpenAiEventParser.parse(
            """{"choices":[{"delta":{"content":"hello","reasoning_content":"think"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        )!!
        assertEquals("hello", text.content)
        assertEquals("think", text.reasoning)
        assertEquals("stop", text.finishReason)
        assertEquals(10L, text.usage?.prompt)

        val tool = OpenAiEventParser.parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_instances","arguments":"{}"}}]}}]}"""
        )!!
        assertEquals(1, tool.toolCalls.size)
        assertEquals("call_1", tool.toolCalls[0].id)
        assertEquals("get_instances", tool.toolCalls[0].name)
        assertEquals("{}", tool.toolCalls[0].arguments)
    }

    @Test
    fun `tool calls null is safe`() {
        val delta = OpenAiEventParser.parse(
            """{"choices":[{"delta":{"content":"x","tool_calls":null}}]}"""
        )!!
        assertTrue(delta.toolCalls.isEmpty())
        assertEquals("x", delta.content)
    }

    @Test
    fun `html and non json error bodies are readable`() {
        assertEquals("oops", extractApiErrorMessage("""{"error":{"message":"oops"}}"""))
        assertEquals("401 Unauthorized", extractApiErrorMessage("<html><body>401 Unauthorized</body></html>"))
        assertNull(extractApiErrorMessage(""))
    }

    @Test
    fun `url joining handles v1 slashes and pasted endpoints`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            joinApiUrl("https://api.deepseek.com", "chat/completions")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            joinApiUrl("https://api.deepseek.com/v1/", "chat/completions")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            joinApiUrl("https://api.deepseek.com/v1/chat/completions/", "chat/completions")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            joinApiUrl("https://api.deepseek.com/v1/v1", "chat/completions")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            joinApiUrl("https://api.anthropic.com/v1", "messages")
        )
    }

    @Test
    fun `anthropic events parse without crashes`() {
        val start = AnthropicEventParser.parse(
            """{"type":"message_start","message":{"usage":{"input_tokens":7}}}"""
        ) as AnthropicEvent.MessageStart
        assertEquals(7L, start.promptTokens)

        val block = AnthropicEventParser.parse(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"x"}}"""
        ) as AnthropicEvent.BlockStart
        assertEquals("tool_use", block.type)

        val text = AnthropicEventParser.parse(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""
        ) as AnthropicEvent.BlockDelta
        assertEquals("hi", text.text)

        val think = AnthropicEventParser.parse(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}"""
        ) as AnthropicEvent.BlockDelta
        assertEquals("hmm", think.thinking)

        val done = AnthropicEventParser.parse(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}"""
        ) as AnthropicEvent.MessageDelta
        assertEquals("end_turn", done.stopReason)
        assertEquals(3L, done.outputTokens)

        val error = AnthropicEventParser.parse(
            """{"type":"error","error":{"message":"bad key"}}"""
        ) as AnthropicEvent.ErrorEvent
        assertEquals("bad key", error.message)
    }
}
