package io.orangerabbit.clanker.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The decoder maps a single OpenAI-style SSE `data:` payload to zero or more [ChatEvent]s.
 * Framing (splitting the byte stream on blank lines, stripping the `data: ` prefix) happens
 * upstream in the Ktor SSE layer; this unit owns only the per-payload JSON → event mapping,
 * which is where the subtle bugs live.
 */
class OpenAiSseDecoderTest {

    private val decoder = OpenAiSseDecoder()

    @Test
    fun contentDeltaBecomesTextDelta() {
        val payload =
            """{"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
        assertEquals(listOf(ChatEvent.TextDelta("Hello")), decoder.decode(payload))
    }

    @Test
    fun doneSentinelYieldsNoEvents() {
        assertEquals(emptyList(), decoder.decode("[DONE]"))
    }

    @Test
    fun emptyDeltaYieldsNoEvents() {
        val payload = """{"choices":[{"index":0,"delta":{},"finish_reason":null}]}"""
        assertEquals(emptyList(), decoder.decode(payload))
    }

    @Test
    fun stopFinishReasonBecomesFinished() {
        val payload = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        assertEquals(listOf(ChatEvent.Finished(FinishReason.Stop)), decoder.decode(payload))
    }

    @Test
    fun toolCallsFinishReasonMapsToToolCalls() {
        val payload = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        assertEquals(listOf(ChatEvent.Finished(FinishReason.ToolCalls)), decoder.decode(payload))
    }

    @Test
    fun lengthFinishReasonMapsToLength() {
        val payload = """{"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}"""
        assertEquals(listOf(ChatEvent.Finished(FinishReason.Length)), decoder.decode(payload))
    }

    @Test
    fun toolCallFragmentBecomesToolCallDelta() {
        val payload = """
            {"choices":[{"index":0,"delta":{"tool_calls":[
            {"index":0,"id":"call_1","type":"function",
            "function":{"name":"get_weather","arguments":"{\"loc"}}]},"finish_reason":null}]}
        """.trimIndent().replace("\n", "")
        assertEquals(
            listOf(ChatEvent.ToolCallDelta(index = 0, id = "call_1", name = "get_weather", argsFragment = "{\"loc")),
            decoder.decode(payload),
        )
    }

    @Test
    fun toolCallArgsContinuationFragmentHasNullIdAndName() {
        val payload = """
            {"choices":[{"index":0,"delta":{"tool_calls":[
            {"index":0,"function":{"arguments":"ation\":\"NYC\"}"}}]},"finish_reason":null}]}
        """.trimIndent().replace("\n", "")
        assertEquals(
            listOf(ChatEvent.ToolCallDelta(index = 0, id = null, name = null, argsFragment = "ation\":\"NYC\"}")),
            decoder.decode(payload),
        )
    }

    @Test
    fun reasoningDeltaBecomesReasoningEvent() {
        val payload =
            """{"choices":[{"index":0,"delta":{"reasoning":"Let me think"},"finish_reason":null}]}"""
        val events = decoder.decode(payload)
        assertEquals(1, events.size)
        val ev = events.single()
        assertTrue(ev is ChatEvent.ReasoningDelta)
        assertEquals("Let me think", ev.block.text)
    }

    @Test
    fun imageDeltaBecomesImageEvent() {
        val dataUrl = "data:image/png;base64,iVBORw0KGgo="
        val payload =
            """{"choices":[{"index":0,"delta":{"images":[{"type":"image_url","image_url":{"url":"$dataUrl"}}]},"finish_reason":null}]}"""
        assertEquals(listOf(ChatEvent.ImageDelta(dataUrl)), decoder.decode(payload))
    }

    @Test
    fun imageAndTextInSameDeltaEmitBothInOrder() {
        val dataUrl = "data:image/png;base64,AAAA"
        val payload =
            """{"choices":[{"index":0,"delta":{"content":"here","images":[{"type":"image_url","image_url":{"url":"$dataUrl"}}]},"finish_reason":null}]}"""
        assertEquals(
            listOf(ChatEvent.TextDelta("here"), ChatEvent.ImageDelta(dataUrl)),
            decoder.decode(payload),
        )
    }

    @Test
    fun usageOnlyChunkBecomesUsageReport() {
        val payload = """
            {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"cost":0.00012}}
        """.trimIndent()
        assertEquals(
            listOf(
                ChatEvent.UsageReport(
                    Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15, costUsd = 0.00012),
                ),
            ),
            decoder.decode(payload),
        )
    }

    @Test
    fun finalChunkWithBothContentAndFinishEmitsTextThenFinished() {
        val payload =
            """{"choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}"""
        assertEquals(
            listOf(ChatEvent.TextDelta("!"), ChatEvent.Finished(FinishReason.Stop)),
            decoder.decode(payload),
        )
    }

    @Test
    fun malformedJsonBecomesFailedEvent() {
        val events = decoder.decode("{not valid json")
        assertEquals(1, events.size)
        assertTrue(events.single() is ChatEvent.Failed)
    }

    @Test
    fun apiErrorObjectBecomesFailedEvent() {
        val payload =
            """{"error":{"message":"rate limited","code":"429","type":"rate_limit_error"}}"""
        val events = decoder.decode(payload)
        assertEquals(1, events.size)
        val ev = events.single()
        assertTrue(ev is ChatEvent.Failed)
        assertEquals("rate limited", ev.error.message)
    }
}
