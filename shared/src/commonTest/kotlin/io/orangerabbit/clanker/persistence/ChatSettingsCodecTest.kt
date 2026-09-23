package io.orangerabbit.clanker.persistence

import io.orangerabbit.clanker.agent.ChatSettings
import io.orangerabbit.clanker.agent.ServerTool
import io.orangerabbit.clanker.agent.SpendLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * settingsJson decode/encode. Legacy shape (Task 8) is a bare model id string;
 * Task 9 stores the full per-chat settings as JSON with the model inside.
 */
class ChatSettingsCodecTest {

    @Test
    fun legacyBareModelStringDecodesToModelWithDefaultSettings() {
        val decoded = ChatSettingsCodec.decode("openai/gpt-4o-mini")
        assertEquals("openai/gpt-4o-mini", decoded.model)
        assertEquals(ChatSettings(), decoded.settings)
    }

    @Test
    fun encodeDecodeRoundTripsModelAndToolPreferences() {
        val settings = ChatSettings(
            tools = setOf(ServerTool.WEB_SEARCH, ServerTool.DATETIME),
            maxToolCalls = 7,
            searchMaxResults = 9,
        )
        val encoded = ChatSettingsCodec.encode(model = "anthropic/claude-4.5", settings = settings)
        val decoded = ChatSettingsCodec.decode(encoded)

        assertEquals("anthropic/claude-4.5", decoded.model)
        assertEquals(settings, decoded.settings)
    }

    @Test
    fun spendLimitsAndGuardrailsRoundTrip() {
        val settings = ChatSettings(
            spendLimits = SpendLimits(perRequestUsd = 0.5, perDayUsd = 5.0),
            guardrailsEnabled = false,
        )
        val encoded = ChatSettingsCodec.encode(model = "m", settings = settings)
        val decoded = ChatSettingsCodec.decode(encoded)

        assertEquals("m", decoded.model)
        assertEquals(settings, decoded.settings)
    }

    /** Task 9-era JSON without the Task 10 fields must decode to SpendLimits defaults. */
    @Test
    fun shapeWithoutSpendFieldsDecodesToDefaults() {
        val decoded = ChatSettingsCodec.decode(
            """{"model":"m","tools":[],"maxToolCalls":10,"searchMaxResults":5}""",
        )

        assertEquals(SpendLimits(), decoded.settings.spendLimits)
        assertTrue(decoded.settings.guardrailsEnabled, "guardrails posture is default-on")
    }

    @Test
    fun decodeToleratesUnknownJsonFields() {
        val decoded = ChatSettingsCodec.decode("""{"model":"m","unknown":true}""")
        assertEquals("m", decoded.model)
    }

    @Test
    fun decodeMalformedJsonFallsBackToDefaults() {
        val decoded = ChatSettingsCodec.decode("{not json")
        assertNull(decoded.model)
        assertEquals(ChatSettings(), decoded.settings)
        assertTrue(decoded.settings.tools.isEmpty())
    }
}
