package io.orangerabbit.clanker.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.ProviderId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {

    private fun provider(engine: MockEngine, apiKey: String = "test-key") =
        OpenAiCompatibleProvider(
            id = ProviderId("test"),
            displayName = "Test",
            baseUrl = "https://api.example.com/v1",
            apiKey = apiKey,
            engine = engine,
        )

    private val sampleRequest = ChatRequest(
        model = "openai/gpt-4o",
        messages = listOf(ChatMessage.User(MessageId("1"), "hi")),
    )

    private fun sseResponse(body: String) = MockEngine {
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    @Test
    fun streamingDeltasAndFinishAreEmittedInOrder() = runTest {
        val body = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val events = provider(sseResponse(body)).streamChat(sampleRequest).toList()
        assertEquals(
            listOf(
                ChatEvent.TextDelta("Hel"),
                ChatEvent.TextDelta("lo"),
                ChatEvent.Finished(FinishReason.Stop),
            ),
            events,
        )
    }

    @Test
    fun requestCarriesBearerAuthAndStreamTrueAndModel() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        provider(engine).streamChat(sampleRequest).toList()

        val req = requireNotNull(captured)
        assertTrue(req.url.toString().endsWith("/chat/completions"), "url was ${req.url}")
        assertEquals("Bearer test-key", req.headers[HttpHeaders.Authorization])
        val bodyText = (req.body as TextContent).text
        assertTrue(bodyText.contains("\"stream\":true"), "body missing stream flag: $bodyText")
        assertTrue(bodyText.contains("\"model\":\"openai/gpt-4o\""), "body missing model: $bodyText")
        assertTrue(bodyText.contains("\"usage\":{\"include\":true}"), "body missing usage accounting opt-in: $bodyText")
    }

    @Test
    fun modalitiesAreSentWhenRequested() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val request = sampleRequest.copy(modalities = listOf("image", "text"))
        provider(engine).streamChat(request).toList()

        val bodyText = (requireNotNull(captured).body as TextContent).text
        assertTrue(bodyText.contains("\"modalities\":[\"image\",\"text\"]"), "body missing modalities: $bodyText")
    }

    @Test
    fun userImagesBecomeContentPartArray() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val dataUrl = "data:image/png;base64,AAAA"
        val request = ChatRequest(
            model = "openai/gpt-4o",
            messages = listOf(ChatMessage.User(MessageId("1"), "describe", imageUrls = listOf(dataUrl))),
        )
        provider(engine).streamChat(request).toList()

        val bodyText = (requireNotNull(captured).body as TextContent).text
        assertTrue(bodyText.contains("\"type\":\"text\""), "missing text part: $bodyText")
        assertTrue(bodyText.contains("\"type\":\"image_url\""), "missing image part: $bodyText")
        assertTrue(bodyText.contains(dataUrl), "missing data url: $bodyText")
    }

    @Test
    fun imageOutputModelGainsImageOutputCapability() = runTest {
        val body = """
            {"data":[
              {"id":"google/gemini-2.5-flash-image","name":"Gemini Image",
               "architecture":{"input_modalities":["text","image"],"output_modalities":["text","image"]},
               "supported_parameters":[]}
            ]}
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val model = provider(engine).listModels().single()
        assertTrue(Capability.ImageOutput in model.capabilities)
        assertTrue(Capability.Vision in model.capabilities)
    }

    @Test
    fun httpErrorStatusBecomesFailedEvent() = runTest {
        val engine = MockEngine {
            respondError(
                status = HttpStatusCode.Unauthorized,
                content = "{\"error\":{\"message\":\"bad key\",\"code\":\"invalid_api_key\"}}",
            )
        }
        val events = provider(engine).streamChat(sampleRequest).toList()
        assertEquals(1, events.size)
        val ev = events.single()
        assertTrue(ev is ChatEvent.Failed)
        assertEquals(401, ev.error.httpStatus)
        assertEquals(false, ev.error.retryable)
    }

    @Test
    fun listModelsParsesIdsNamesAndCapabilities() = runTest {
        val body = """
            {"data":[
              {"id":"openai/gpt-4o","name":"GPT-4o","context_length":128000,
               "architecture":{"input_modalities":["text","image"]},
               "supported_parameters":["tools","temperature"]},
              {"id":"meta/llama-3","context_length":8192,"supported_parameters":[]}
            ]}
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val models = provider(engine).listModels()

        assertEquals(2, models.size)
        val gpt = models.first()
        assertEquals("openai/gpt-4o", gpt.id)
        assertEquals("GPT-4o", gpt.displayName)
        assertEquals(128000, gpt.contextLength)
        assertTrue(Capability.Streaming in gpt.capabilities)
        assertTrue(Capability.ToolCalling in gpt.capabilities)
        assertTrue(Capability.Vision in gpt.capabilities)

        val llama = models[1]
        assertEquals("meta/llama-3", llama.id)
        assertEquals("meta/llama-3", llama.displayName) // no name → falls back to id
        assertTrue(Capability.ToolCalling !in llama.capabilities)
        assertTrue(Capability.Vision !in llama.capabilities)
    }

    @Test
    fun defaultServerToolsEnabledOnlyForToolCapableModels() {
        val capable = defaultServerTools(setOf(Capability.Streaming, Capability.ToolCalling))
        assertEquals(3, capable.size)
        assertTrue(capable.any { it is ServerTool.WebSearch })
        assertTrue(capable.contains(ServerTool.WebFetch))
        assertTrue(capable.contains(ServerTool.Datetime))
        // ImageGeneration is intentionally excluded from the always-on set (fails inline on chat models).
        assertTrue(capable.none { it is ServerTool.ImageGeneration })

        assertEquals(emptyList(), defaultServerTools(setOf(Capability.Streaming)))

        // Unknown capability (catalogue not loaded yet) → enable optimistically, not suppress.
        assertEquals(3, defaultServerTools(null).size)
    }

    @Test
    fun serverToolsAreSerializedIntoToolsArray() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val request = sampleRequest.copy(
            serverTools = listOf(
                ServerTool.WebSearch(maxResults = 5),
                ServerTool.WebFetch,
                ServerTool.Datetime,
                ServerTool.ImageGeneration(),
            ),
        )
        provider(engine).streamChat(request).toList()

        val bodyText = (requireNotNull(captured).body as TextContent).text
        assertTrue(bodyText.contains("\"type\":\"openrouter:web_search\""), "missing web_search: $bodyText")
        assertTrue(bodyText.contains("\"engine\":\"auto\""), "web_search missing engine=auto: $bodyText")
        assertTrue(bodyText.contains("\"max_results\":5"), "web_search missing max_results: $bodyText")
        assertTrue(bodyText.contains("\"type\":\"openrouter:web_fetch\""), "missing web_fetch: $bodyText")
        assertTrue(bodyText.contains("\"type\":\"openrouter:datetime\""), "missing datetime: $bodyText")
        assertTrue(bodyText.contains("\"type\":\"openrouter:image_generation\""), "missing image_generation: $bodyText")
    }

    @Test
    fun noToolsMeansNoToolsKeyInBody() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        provider(engine).streamChat(sampleRequest).toList()
        val bodyText = (requireNotNull(captured).body as TextContent).text
        assertTrue(!bodyText.contains("\"tools\""), "tools key should be absent when empty: $bodyText")
    }

    @Test
    fun serverErrorIsRetryable() = runTest {
        val engine = MockEngine {
            respondError(status = HttpStatusCode.ServiceUnavailable, content = "overloaded")
        }
        val events = provider(engine).streamChat(sampleRequest).toList()
        val ev = events.single()
        assertTrue(ev is ChatEvent.Failed)
        assertEquals(503, ev.error.httpStatus)
        assertEquals(true, ev.error.retryable)
    }
}
