package io.orangerabbit.clanker.core.character

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalEncodingApi::class)
class CharacterCardParserTest {

    @Test
    fun parsesV2Envelope() {
        val json = """
            {"spec":"chara_card_v2","spec_version":"2.0",
             "data":{"name":"Aria","description":"A wizard","first_mes":"Hi",
                     "alternate_greetings":["Hey"],"system_prompt":"You are {{char}}."}}
        """.trimIndent()
        val card = CharacterCardParser.fromJson(json)
        assertEquals("Aria", card.name)
        assertEquals("A wizard", card.description)
        assertEquals("Hi", card.firstMes)
        assertEquals(listOf("Hey"), card.alternateGreetings)
        assertEquals("You are {{char}}.", card.systemPrompt)
    }

    @Test
    fun parsesBareV1Json() {
        val card = CharacterCardParser.fromJson("""{"name":"Bob","personality":"gruff"}""")
        assertEquals("Bob", card.name)
        assertEquals("gruff", card.personality)
    }

    @Test
    fun parsesFromPngCharaChunk() {
        val json = """{"spec":"chara_card_v2","data":{"name":"Pixel","description":"From PNG"}}"""
        val png = buildPng("chara" to Base64.encode(json.encodeToByteArray()))
        val card = CharacterCardParser.fromPng(png)
        assertEquals("Pixel", card.name)
        assertEquals("From PNG", card.description)
    }

    @Test
    fun ccv3ChunkTakesPrecedenceOverChara() {
        val v2 = Base64.encode("""{"data":{"name":"OldName"}}""".encodeToByteArray())
        val v3 = Base64.encode("""{"data":{"name":"NewName"}}""".encodeToByteArray())
        val png = buildPng("chara" to v2, "ccv3" to v3)
        assertEquals("NewName", CharacterCardParser.fromPng(png).name)
    }

    @Test
    fun rejectsNonPng() {
        assertFailsWith<IllegalArgumentException> {
            CharacterCardParser.fromPng(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }
    }

    // --- test PNG builder: signature + tEXt chunk(s) + IEND, with zeroed CRCs (parser skips CRC) ---

    private fun buildPng(vararg textChunks: Pair<String, String>): ByteArray {
        val out = ArrayList<Byte>()
        out += intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }
        for ((keyword, text) in textChunks) {
            val data = keyword.encodeToByteArray() + byteArrayOf(0) + text.encodeToByteArray()
            out += chunk("tEXt", data)
        }
        out += chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): List<Byte> {
        val out = ArrayList<Byte>()
        val len = data.size
        out += byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()).toList()
        out += type.encodeToByteArray().toList()
        out += data.toList()
        out += listOf<Byte>(0, 0, 0, 0) // CRC placeholder (parser does not validate)
        return out
    }
}
