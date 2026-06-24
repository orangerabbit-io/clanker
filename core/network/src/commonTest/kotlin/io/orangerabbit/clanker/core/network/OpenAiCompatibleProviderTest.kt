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
