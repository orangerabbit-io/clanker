package io.orangerabbit.clanker.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageTest {

    @Test
    fun reasoningOpaqueRoundTrip() {
        val block = ReasoningBlock("thinking", "hmm", "{\"signature\":\"abc\"}")
        val msg = AssistantMessage(content = null, reasoning = listOf(block))
        val json = Json.encodeToString(msg)
        val back = Json.decodeFromString<AssistantMessage>(json)
        assertEquals(block, back.reasoning.first())
        assertEquals("{\"signature\":\"abc\"}", back.reasoning[0].opaqueJson)
    }

    @Test
    fun toolOnlyAssistantMessageRoundTrip() {
        val toolCall = ToolCall(id = "call_1", name = "search", argumentsJson = "{\"q\":\"hello\"}")
        val msg = AssistantMessage(content = null, toolCalls = listOf(toolCall))
        val json = Json.encodeToString(msg)
        val back = Json.decodeFromString<AssistantMessage>(json)
        assertEquals(msg, back)
        assertEquals(null, back.content)
        assertEquals(1, back.toolCalls.size)
        assertEquals("search", back.toolCalls[0].name)
    }

    @Test
    fun lifecycleDefaultIsStreaming() {
        val msg = AssistantMessage(content = "hello")
        assertEquals(MsgLifecycle.STREAMING, msg.lifecycle)
    }
}
