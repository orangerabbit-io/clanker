package io.orangerabbit.clanker.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.orangerabbit.clanker.agent.ChatSettings
import io.orangerabbit.clanker.agent.ServerTool
import io.orangerabbit.clanker.agent.ToolPolicy
import io.orangerabbit.clanker.model.AssistantMessage
import io.orangerabbit.clanker.model.ToolCall
import io.orangerabbit.clanker.model.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.writeString

class OpenRouterClientTest {

    private val apiKey = "sk-test-123"
    private val request =
        ChatRequest(model = "openai/gpt-4o-mini", messages = listOf(UserMessage("Hi")))

    // ── Step 1: happy path ────────────────────────────────────────────────────

    /**
     * Verifies streamChat() happy path:
     *  - POSTs to exactly `https://openrouter.ai/api/v1/chat/completions`
     *  - sends `Authorization: Bearer <key>` and app attribution headers
     *  - emits content deltas in order, exactly once each
     *  - returns Completed with usage from the final chunk
     *  - calls the API key provider exactly once
     */
    @Test
    fun happyPathStreamsContentAndCompletesWithUsage() = runTest {
        val fixture = listOf(
            """data: {"id":"chatcmpl-1","model":"openai/gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hel"}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            "",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":10,"cost":0.0012}}""",
            "",
            "data: [DONE]",
            "",
        ).joinToString("\n")

        var capturedUrl: String? = null
        var capturedAuth: String? = null
        var capturedReferer: String? = null
        var capturedTitle: String? = null
        var capturedBody: String? = null
        var keyProviderCalls = 0

        val engine = MockEngine { req ->
            capturedUrl = req.url.toString()
            capturedAuth = req.headers[HttpHeaders.Authorization]
            capturedReferer = req.headers["HTTP-Referer"]
            capturedTitle = req.headers["X-Title"]
            capturedBody = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                fixture,
                HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val client = OpenRouterClient(apiKeyProvider = { keyProviderCalls++; apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        val content = events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text }
        assertEquals("Hello", content, "content deltas must arrive in order")
        assertTrue(result is ChatResult.Completed, "must complete; got $result")
        assertEquals(0.0012, result.usage?.totalCost, "Completed.usage.totalCost must be 0.0012")
        assertEquals(1, events.filterIsInstance<StreamEvent.Done>().size, "exactly one Done event")
        assertEquals(1, keyProviderCalls, "apiKeyProvider must be called exactly once")
        assertEquals("Bearer $apiKey", capturedAuth, "Authorization header must be Bearer <key>")
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            capturedUrl,
            "must POST to the chat completions endpoint",
        )
        assertEquals(APP_REFERER, capturedReferer, "HTTP-Referer attribution header must be set")
        assertEquals(APP_TITLE, capturedTitle, "X-Title header must be set")
        val body = capturedBody ?: error("request body must be captured")
        assertEquals(
            """{"model":"openai/gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"Hi"}]}""",
            body,
            "request body must be OpenAI wire format with stream=true",
        )
    }

    /**
     * Verifies rawJson capture: ChatResult.Completed.rawJson carries the
     * verbatim `data:` payloads of every parsed chunk, joined with `\n` in
     * arrival order — byte-for-byte, no re-serialization.
     */
    @Test
    fun happyPathCapturesRawChunkPayloadsVerbatim() = runTest {
        val firstChunk = """{"id":"chatcmpl-1","model":"openai/gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hel"}}]}"""
        val secondChunk = """{"choices":[{"delta":{"content":"lo"}}]}"""
        val usageChunk = """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":10,"cost":0.0012}}"""
        val fixture = listOf(
            "data: $firstChunk",
            "",
            "data: $secondChunk",
            "",
            "data: $usageChunk",
            "",
            "data: [DONE]",
            "",
        ).joinToString("\n")

        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        assertTrue(result is ChatResult.Completed, "must complete; got $result")
        assertEquals(
            listOf(firstChunk, secondChunk, usageChunk).joinToString("\n"),
            result.rawJson,
            "rawJson must contain every chunk payload verbatim, joined with \\n in order",
        )
    }

    /**
     * Verifies rawJson on interruption: the payloads of chunks parsed before
     * the failure are preserved on ChatResult.Interrupted.rawJson.
     */
    @Test
    fun interruptionCarriesPartialRawJson() = runTest {
        val partialChunk = """{"choices":[{"delta":{"content":"partial"}}]}"""
        val fixture = "data: $partialChunk\n\ndata: NOT-JSON\n\n"

        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        assertTrue(result is ChatResult.Interrupted, "mid-stream failure must yield Interrupted; got $result")
        assertEquals(partialChunk, result.rawJson, "rawJson must carry the payload parsed before the failure")
    }

    // ── HTTP errors → Failed ──────────────────────────────────────────────

    /** Verifies non-2xx responses map to ChatResult.Failed with a descriptive message. */
    @Test
    fun httpErrorResponseYieldsFailed() = runTest {
        val engine = MockEngine { _ ->
            respond("""{"error":{"message":"rate limited"}}""", HttpStatusCode.TooManyRequests)
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        assertTrue(result is ChatResult.Failed, "HTTP error must yield Failed; got $result")
        assertTrue(
            (result as ChatResult.Failed).message.contains("429"),
            "Failed.message must include the status code; got '${result.message}'",
        )
    }

    // ── Step 2: partial failure (Review Focus #1) ─────────────────────────────

    /**
     * Verifies that when the stream fails mid-stream, the result is
     * Interrupted and content already emitted via onEvent was delivered
     * exactly once (no loss, no duplication).
     *
     * Fixture: one valid content delta, then a corrupted data line (mid-stream
     * data failure). Note: a literal engine-level close-with-error cannot be
     * used here because Ktor's response transfer drops already-buffered bytes
     * when the response channel is closed with a cause, so the parse failure
     * inside the execute block exercises the same
     * "transport exception -> Interrupted" mapping with prior content intact.
     */
    @Test
    fun interruptedStreamDeliversEmittedContentExactlyOnce() = runTest {
        val fixture =
            """data: {"choices":[{"delta":{"content":"partial"}}]}""" + "\n\n" + "data: NOT-JSON\n\n"

        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        assertTrue(result is ChatResult.Interrupted, "mid-stream failure must yield Interrupted; got $result")
        assertEquals(
            listOf("partial"),
            events.filterIsInstance<StreamEvent.Content>().map { it.text },
            "already-emitted content must be delivered exactly once",
        )
    }

    /** Verifies a transport error before any content still yields Interrupted with no events. */
    @Test
    fun transportErrorBeforeAnyContentYieldsInterruptedWithNoEvents() = runTest {
        val channel = partialThenError("")
        val engine = MockEngine { _ ->
            respond(channel, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        assertTrue(result is ChatResult.Interrupted, "transport failure must yield Interrupted; got $result")
        assertTrue(events.isEmpty(), "no events must be emitted before the failure; got $events")
    }

    // ── Step 3: usage absent (Review Focus #2) ────────────────────────────────

    /** Verifies `[DONE]` without any usage chunk completes with null usage, no exception. */
    @Test
    fun missingUsageCompletesWithNullUsage() = runTest {
        val fixture =
            """data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n\n" + "data: [DONE]\n\n"

        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        assertTrue(result is ChatResult.Completed, "missing usage must still complete; got $result")
        assertNull((result as ChatResult.Completed).usage, "usage must be null when absent")
    }

    // ── Model catalog (Task 7) ────────────────────────────────────────────────

    /**
     * Verifies models():
     *  - GETs exactly `https://openrouter.ai/api/v1/models`
     *  - sends no Authorization header (public endpoint)
     *  - parses id/name/context_length/pricing.{prompt,completion}
     *  - ignores unknown fixture fields (architecture, supported_parameters, ...)
     *  - tolerates a missing context_length and non-numeric pricing (null, no throw)
     *  - returns summaries sorted by name
     */
    @Test
    fun modelsParsesCatalogSortedByNameAndToleratesMissingFields() = runTest {
        val fixture = """
            {"data":[
              {"id":"openai/gpt-5.2","name":"OpenAI: GPT 5.2","context_length":400000,
               "pricing":{"prompt":"0.0000015","completion":"0.0000075"},
               "architecture":{"modality":"text"},"supported_parameters":["tools"]},
              {"id":"anthropic/claude-4.5","name":"Anthropic: Claude 4.5","context_length":200000,
               "pricing":{"prompt":"0.000003","completion":"0.000015"}},
              {"id":"weird/broken","name":"Zeta: Broken","pricing":{"prompt":"free"}}
            ]}
        """.trimIndent()

        var capturedMethod: String? = null
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        val engine = MockEngine { req ->
            capturedMethod = req.method.value
            capturedUrl = req.url.toString()
            capturedAuth = req.headers[HttpHeaders.Authorization]
            respond(
                fixture,
                HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val models = client.models()

        assertEquals("GET", capturedMethod, "models must use GET")
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            capturedUrl,
            "must GET the models endpoint",
        )
        assertNull(capturedAuth, "models is a public endpoint; Authorization header must be omitted")
        assertEquals(
            listOf(
                ModelSummary("anthropic/claude-4.5", "Anthropic: Claude 4.5", 200000, 0.000003, 0.000015),
                ModelSummary("openai/gpt-5.2", "OpenAI: GPT 5.2", 400000, 0.0000015, 0.0000075),
                ModelSummary("weird/broken", "Zeta: Broken", null, null, null),
            ),
            models,
            "summaries must be parsed and sorted by name",
        )
    }

    // ── Task 9: server tools ─────────────────────────────────────────────────

    private val bodyJson = Json { ignoreUnknownKeys = true }

    /** MockEngine capturing the request body and replying with [fixture]. */
    private fun bodyCapturingEngine(
        fixture: String,
        onBody: (String) -> Unit,
    ): MockEngine = MockEngine { req ->
        onBody((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        respond(
            fixture,
            HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    /**
     * Task 9 Step 2: `ChatRequest(tools = ToolPolicy.toToolSpecs(...))` must
     * produce a request body whose `tools` array matches the brief's exact
     * wire shapes (decoded from the MockEngine-recorded body).
     */
    @Test
    fun toolsArrayInRequestBodyMatchesPolicySpecs() = runTest {
        var capturedBody: String? = null
        val engine = bodyCapturingEngine(
            fixture = "data: [DONE]\n\n",
            onBody = { capturedBody = it },
        )
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val request = ChatRequest(
            model = "openai/gpt-4o-mini",
            messages = listOf(UserMessage("Hi")),
            tools = ToolPolicy.toToolSpecs(
                ChatSettings(tools = setOf(ServerTool.WEB_SEARCH, ServerTool.WEB_FETCH, ServerTool.DATETIME)),
            ),
        )
        client.streamChat(request, onEvent = {})

        val tools = bodyJson.parseToJsonElement(capturedBody ?: error("body must be captured"))
            .jsonObject["tools"]
        assertEquals(
            bodyJson.parseToJsonElement(
                """[{"type":"openrouter:web_search","parameters":{"max_results":5}},""" +
                    """{"type":"openrouter:web_fetch"},{"type":"openrouter:datetime"}]""",
            ),
            tools,
            "tools array must match the brief's exact wire shapes",
        )
    }

    /** `maxToolCalls` must land as the top-level integer `max_tool_calls` field. */
    @Test
    fun maxToolCallsLandsAsTopLevelRequestField() = runTest {
        var capturedBody: String? = null
        val engine = bodyCapturingEngine(fixture = "data: [DONE]\n\n", onBody = { capturedBody = it })
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        client.streamChat(
            ChatRequest(model = "openai/gpt-4o-mini", messages = listOf(UserMessage("Hi")), maxToolCalls = 3),
            onEvent = {},
        )

        val body = bodyJson.parseToJsonElement(capturedBody ?: error("body must be captured")).jsonObject
        assertEquals(JsonPrimitive(3), body["max_tool_calls"], "max_tool_calls must be a top-level integer")
        assertNull(body["spend_cap_usd"], "fields outside the request DTO must stay absent")
    }

    /**
     * Task 10 Step 2: `spendCapUsd` must serialize as the docs-verified
     * `stop_server_tools_when` conditions (max_cost + step_count_is), which
     * override `max_tool_calls` entirely.
     */
    @Test
    fun spendCapUsdProducesStopServerToolsWhenConditions() = runTest {
        var capturedBody: String? = null
        val engine = bodyCapturingEngine(fixture = "data: [DONE]\n\n", onBody = { capturedBody = it })
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        client.streamChat(
            ChatRequest(
                model = "openai/gpt-4o-mini",
                messages = listOf(UserMessage("Hi")),
                maxToolCalls = 10,
                spendCapUsd = 0.25,
            ),
            onEvent = {},
        )

        val body = bodyJson.parseToJsonElement(capturedBody ?: error("body must be captured")).jsonObject
        assertEquals(
            bodyJson.parseToJsonElement(
                """[{"type":"max_cost","max_cost_in_dollars":0.25},{"type":"step_count_is","step_count":10}]""",
            ),
            body["stop_server_tools_when"],
            "spend cap + step count must land as the docs-verified stop conditions",
        )
        assertNull(body["max_tool_calls"], "stop_server_tools_when overrides max_tool_calls")
    }

    /** Without a spend cap the legacy body stays: max_tool_calls, no stop conditions. */
    @Test
    fun withoutSpendCapNoStopConditionsAreSent() = runTest {
        var capturedBody: String? = null
        val engine = bodyCapturingEngine(fixture = "data: [DONE]\n\n", onBody = { capturedBody = it })
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        client.streamChat(
            ChatRequest(model = "openai/gpt-4o-mini", messages = listOf(UserMessage("Hi")), maxToolCalls = 3),
            onEvent = {},
        )

        val body = bodyJson.parseToJsonElement(capturedBody ?: error("body must be captured")).jsonObject
        assertEquals(JsonPrimitive(3), body["max_tool_calls"])
        assertNull(body["stop_server_tools_when"], "no stop conditions without a spend cap")
    }

    /** AssistantMessage.toolCalls must map to the OpenAI `tool_calls` wire array. */
    @Test
    fun assistantToolCallsAreWiredAsToolCallsArray() = runTest {
        var capturedBody: String? = null
        val engine = bodyCapturingEngine(fixture = "data: [DONE]\n\n", onBody = { capturedBody = it })
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        client.streamChat(
            ChatRequest(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    UserMessage("Hi"),
                    AssistantMessage(
                        content = null,
                        toolCalls = listOf(ToolCall(id = "call_1", name = "lookup", argumentsJson = """{"q":"x"}""")),
                    ),
                ),
            ),
            onEvent = {},
        )

        val messages = bodyJson.parseToJsonElement(capturedBody ?: error("body must be captured"))
            .jsonObject["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.content)
        assertEquals(
            bodyJson.parseToJsonElement(
                """[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}}]""",
            ),
            assistant["tool_calls"],
            "AssistantMessage.toolCalls must map to the OpenAI tool_calls wire array",
        )
    }

    /** `usage.server_tool_use` counts must surface on the public Usage. */
    @Test
    fun serverToolUseCountsSurfaceInUsage() = runTest {
        val fixture =
            """data: {"choices":[{"delta":{"content":"hi"}}],"usage":{"cost":0.01,""" +
                """"server_tool_use":{"web_search_requests":2,"web_fetch_requests":1}}}""" +
                "\n\ndata: [DONE]\n\n"
        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        val result = client.streamChat(request, onEvent = { events.add(it) })

        val expected = mapOf("web_search_requests" to 2, "web_fetch_requests" to 1)
        assertEquals(
            expected,
            events.filterIsInstance<StreamEvent.Done>().single().usage.serverToolUse,
            "Done event usage must carry server_tool_use counts",
        )
        assertEquals(expected, (result as ChatResult.Completed).usage?.serverToolUse)
    }

    /** `annotations` url_citations in a delta must surface as a Sources event. */
    @Test
    fun urlCitationAnnotationsEmitSourcesEvent() = runTest {
        val fixture =
            """data: {"choices":[{"delta":{"content":"answer",""" +
                """"annotations":[{"type":"url_citation","url_citation":{"url":"https://a.io","title":"A","content":"snippet"}}]}}]}""" +
                "\n\ndata: [DONE]\n\n"
        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine)

        val events = mutableListOf<StreamEvent>()
        client.streamChat(request, onEvent = { events.add(it) })

        assertEquals(
            listOf(Source(url = "https://a.io", title = "A")),
            events.filterIsInstance<StreamEvent.Sources>().single().sources,
        )
        assertEquals(listOf("answer"), events.filterIsInstance<StreamEvent.Content>().map { it.text })
    }

    // ── Base URL safety ───────────────────────────────────────────────────────

    /** Verifies the constructor throws on any non-HTTPS base URL override. */
    @Test
    fun nonHttpsBaseUrlIsRejected() {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        assertFailsWith<IllegalArgumentException> {
            OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine, baseUrl = "http://openrouter.ai/api/v1")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenRouterClient(apiKeyProvider = { apiKey }, engine = engine, baseUrl = "ftp://openrouter.ai/api/v1")
        }
    }

    // ── SseReader purity (lines in → events out) ──────────────────────────────

    /** Verifies SseReader ignores comments/blank lines, tolerates reasoning, and honors `[DONE]`. */
    @Test
    fun sseReaderParsesLinesToEventsAndHonorsDoneSentinel() = runTest {
        val lines = listOf(
            ": OPENROUTER PROCESSING",
            """data: {"choices":[{"delta":{"reasoning":"thinking"}}]}""",
            """data: {"choices":[{"delta":{"content":"ok"}}],"usage":{"cost":0.5}}""",
            "data: [DONE]",
            """data: {"ignored":"after done"}""",
        )
        var idx = 0
        val reader = SseReader(readLine = { lines.getOrNull(idx++) })

        val events = mutableListOf<StreamEvent>()
        val done = reader.events(onEvent = { events.add(it) })

        assertTrue(done, "[DONE] sentinel must terminate the stream with done=true")
        assertEquals(listOf("ok"), events.filterIsInstance<StreamEvent.Content>().map { it.text })
        assertEquals(listOf("thinking"), events.filterIsInstance<StreamEvent.Reasoning>().map { it.text })
        assertEquals(0.5, events.filterIsInstance<StreamEvent.Done>().single().usage.totalCost)
    }

    /** Verifies EOF without `[DONE]` reports a truncated (not completed) stream. */
    @Test
    fun sseReaderReturnsFalseOnEofWithoutDoneSentinel() = runTest {
        val lines = listOf("""data: {"choices":[{"delta":{"content":"half"}}]}""", "")
        var idx = 0
        val reader = SseReader(readLine = { lines.getOrNull(idx++) })

        val events = mutableListOf<StreamEvent>()
        val done = reader.events(onEvent = { events.add(it) })

        assertEquals(false, done, "EOF without [DONE] must report done=false")
        assertEquals(listOf("half"), events.filterIsInstance<StreamEvent.Content>().map { it.text })
    }
}

/** ByteReadChannel serving [data], then failing the connection mid-stream. */
internal fun partialThenError(data: String): ByteReadChannel {
    val pending = Buffer().apply { writeString(data) }
    var thrown = false
    val source = object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (pending.exhausted()) {
                if (!thrown) {
                    thrown = true
                    throw RuntimeException("connection reset by peer")
                }
                return -1L
            }
            return pending.readAtMostTo(sink, byteCount)
        }

        override fun close() {}
    }
    return ByteReadChannel(source.buffered())
}
