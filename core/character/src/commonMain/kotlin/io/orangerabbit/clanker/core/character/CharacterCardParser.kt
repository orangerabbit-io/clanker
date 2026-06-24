package io.orangerabbit.clanker.core.character

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses Character Card V2/V3 from either raw JSON or a PNG with the card embedded in a `tEXt`
 * chunk (keyed `ccv3` preferred, else `chara`, base64-encoded JSON). Character cards are an
 * attacker-controlled input vector, so the PNG walker is resource-bounded and decoding is robust.
 */
object CharacterCardParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Accepts both the `{spec, data:{…}}` envelope (V2/V3) and bare flat JSON (V1). */
    fun fromJson(jsonText: String): CharacterCard {
        val root = json.parseToJsonElement(jsonText)
        val dataEl = root.jsonObject["data"] ?: root
        return json.decodeFromJsonElement<CardDto>(dataEl).toCard()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun fromPng(bytes: ByteArray): CharacterCard {
        require(bytes.size >= 8 && hasPngSignature(bytes)) { "Not a PNG file" }

        var offset = 8
        var chunks = 0
        var chara: String? = null
        var ccv3: String? = null

        while (offset + 8 <= bytes.size) {
            if (++chunks > MAX_CHUNKS) error("Too many PNG chunks (possible bomb)")
            val length = readUInt32(bytes, offset)
            require(length <= MAX_CHUNK_LENGTH) { "PNG chunk too large (possible bomb)" }
            val type = bytes.decodeToString(offset + 4, offset + 8)
            val dataStart = offset + 8
            val dataEnd = dataStart + length.toInt()
            if (dataEnd > bytes.size) break

            if (type == "tEXt") {
                val sep = bytes.indexOfZero(dataStart, dataEnd)
                if (sep != -1) {
                    val keyword = bytes.decodeToString(dataStart, sep).lowercase()
                    val text = bytes.decodeToString(sep + 1, dataEnd)
                    when (keyword) {
                        "ccv3" -> ccv3 = text
                        "chara" -> chara = text
                    }
                }
            }
            if (type == "IEND") break
            offset = dataEnd + 4 // skip the 4-byte CRC
        }

        val base64 = ccv3 ?: chara ?: error("No character card chunk (chara/ccv3) found in PNG")
        // base64 (ASCII) -> bytes -> UTF-8. Decoding the chunk before base64 would corrupt UTF-8.
        val decoded = Base64.decode(base64.trim()).decodeToString()
        return fromJson(decoded)
    }

    private fun hasPngSignature(b: ByteArray): Boolean =
        b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun readUInt32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun ByteArray.indexOfZero(from: Int, until: Int): Int {
        for (i in from until until) if (this[i] == 0.toByte()) return i
        return -1
    }

    private const val MAX_CHUNKS = 1024
    private const val MAX_CHUNK_LENGTH = 16L * 1024 * 1024 // 16 MiB per chunk
}

@Serializable
private data class CardDto(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
) {
    fun toCard() = CharacterCard(
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        firstMes = firstMes,
        mesExample = mesExample,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        alternateGreetings = alternateGreetings,
        tags = tags,
        creator = creator,
        characterVersion = characterVersion,
    )
}
