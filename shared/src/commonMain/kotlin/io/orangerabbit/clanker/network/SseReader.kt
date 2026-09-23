package io.orangerabbit.clanker.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/**
 * Pure SSE parser: lines in, [StreamEvent]s out. Independently testable via
 * the suspend line-source constructor; the [ByteReadChannel] constructor
 * wraps Ktor's channel reader.
 */
class SseReader(private val readLine: suspend () -> String?) {
    constructor(channel: ByteReadChannel) : this({ channel.readUTF8Line() })

    /**
     * Drains the stream, invoking [onEvent] for each content, reasoning, and
     * usage event. Returns true when the `[DONE]` sentinel was seen; false on
     * EOF without it (truncated stream). Malformed JSON on a `data:` line
     * throws — callers map that to interruption.
     */
    suspend fun events(onEvent: (StreamEvent) -> Unit): Boolean {
        var usageEmitted = false
        while (true) {
            val line = readLine() ?: return false
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue // blank lines / SSE comments (keep-alives)
            if (!trimmed.startsWith(DATA_PREFIX)) continue
            val payload = trimmed.removePrefix(DATA_PREFIX).trim()
            if (payload == DONE_SENTINEL) return true
            val chunk = chatJson.decodeFromString<StreamChunk>(payload)
            val delta = chunk.choices.firstOrNull()?.delta
            delta?.content?.let { onEvent(StreamEvent.Content(it)) }
            delta?.reasoning?.let { onEvent(StreamEvent.Reasoning(it)) }
            delta?.annotations
                ?.mapNotNull { it.urlCitation }
                ?.filter { !it.url.isNullOrBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { citations ->
                    onEvent(StreamEvent.Sources(citations.map { Source(it.url!!, it.title) }))
                }
            if (chunk.usage != null && !usageEmitted) {
                usageEmitted = true
                onEvent(StreamEvent.Done(chunk.usage.toUsage()))
            }
        }
    }

    companion object {
        private const val DATA_PREFIX = "data:"
        private const val DONE_SENTINEL = "[DONE]"
    }
}
