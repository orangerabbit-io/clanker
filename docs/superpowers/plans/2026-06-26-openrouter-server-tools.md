# OpenRouter Server Tools Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable web search, web fetch, datetime, and image generation in clanker via OpenRouter server tools — declared in the request `tools` array, executed entirely server-side, returning the final answer plus `url_citation` annotations.

**Architecture:** Three units. (1) `:core:network` request side: a typed `ServerTool` section serialized into the wire `tools` array. (2) `:core:network` response side: decode `url_citation` annotations and `server_tool_use` usage; a `Citation` domain type in `:core:model`. (3) `:app`: enable server tools for tool-capable models (always-on, no toggle), accumulate citations onto the assistant message, render source chips. No client agent loop — OpenRouter runs the tool loop server-side, so the existing single-shot `streamChat` stands.

**Tech Stack:** Kotlin Multiplatform, Ktor client, kotlinx.serialization, Jetpack Compose (Material3). Tests on the KMP `jvm()` target.

**Spec:** `docs/superpowers/specs/2026-06-26-openrouter-server-tools-design.md`

**Naming note:** the spec's `ChatEvent.Citation` is implemented as `ChatEvent.CitationDelta` to match the existing `*Delta` event convention (reviewer recommendation).

---

## Conventions used throughout this plan

**Test command (unit, JVM target):**
```bash
nix develop --command bash -c './gradlew :core:network:jvmTest'
```

**Live test command (needs key):**
```bash
nix develop --command bash -c 'OPENROUTER_API_KEY=$OPENROUTER_API_KEY ./gradlew :core:network:jvmTest --tests "*LiveOpenRouterTest" --rerun-tasks'
```

**App build (verifies `:app` wiring compiles):**
```bash
nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'
```

**Commit signing:** follow the repo's normal signed-commit flow (do not pass `--no-gpg-sign`). All commits land on `main` (solo workflow). Conventional-commit messages, one logical change each. End each commit message with:
```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## Chunk 1: Network request side — `ServerTool` → wire `tools` array

Adds the typed `ServerTool` request section and serializes it into the wire `tools` array alongside any function tools. The existing `ChatRequest.tools` (function tools, currently unused) stays intact.

**Files:**
- Modify: `core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/LlmProvider.kt` (add `ServerTool`, `ChatRequest.serverTools`, `defaultServerTools`)
- Modify: `core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProvider.kt` (merge server tools into the wire `tools` array)
- Test: `core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProviderTest.kt`

### Task 1.1: Add the `ServerTool` type and `ChatRequest.serverTools`

- [ ] **Step 1: Add `ServerTool` and the request field in `LlmProvider.kt`**

In `LlmProvider.kt`, add the `serverTools` field to `ChatRequest` (after `tools`):

```kotlin
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    /**
     * OpenRouter server tools (e.g. web search) advertised in the request. OpenRouter executes
     * these server-side within the same completion; the client never runs them and never returns a
     * `role:tool` reply. Serialized into the same wire `tools` array as [tools].
     */
    val serverTools: List<ServerTool> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val parallelToolCalls: Boolean? = null,
    val modalities: List<String> = emptyList(),
)
```

Add the `ServerTool` sealed interface near `ToolSpec`:

```kotlin
/**
 * An OpenRouter server tool: a model-callable tool OpenRouter operates server-side. Declared in the
 * request `tools` array as `{"type":"openrouter:…","parameters":{…}}`. Results stream back inline
 * (web tools surface as `url_citation` annotations); the client executes nothing.
 */
sealed interface ServerTool {
    /** Web search. [engine] defaults to "auto" (falls back to Exa); never send "native" by default. */
    data class WebSearch(val maxResults: Int? = null, val engine: String = "auto") : ServerTool
    data object WebFetch : ServerTool
    data object Datetime : ServerTool
    data class ImageGeneration(
        val model: String? = null,
        val size: String? = null,
        val quality: String? = null,
    ) : ServerTool
}

/**
 * The server tools clanker enables "always-on for capable models": all four iff the model supports
 * tool calling, else none. Pure so it is unit-testable without the Android/UI layer.
 */
fun defaultServerTools(capabilities: Set<Capability>): List<ServerTool> =
    if (Capability.ToolCalling in capabilities) {
        listOf(
            ServerTool.WebSearch(),
            ServerTool.WebFetch,
            ServerTool.Datetime,
            ServerTool.ImageGeneration(),
        )
    } else {
        emptyList()
    }
```

- [ ] **Step 2: Write the failing test for `defaultServerTools`**

Add to `OpenAiCompatibleProviderTest.kt`:

```kotlin
    @Test
    fun defaultServerToolsEnabledOnlyForToolCapableModels() {
        val capable = defaultServerTools(setOf(Capability.Streaming, Capability.ToolCalling))
        assertEquals(4, capable.size)
        assertTrue(capable.any { it is ServerTool.WebSearch })
        assertTrue(capable.contains(ServerTool.WebFetch))
        assertTrue(capable.contains(ServerTool.Datetime))
        assertTrue(capable.any { it is ServerTool.ImageGeneration })

        assertEquals(emptyList(), defaultServerTools(setOf(Capability.Streaming)))
    }
```

- [ ] **Step 3: Run the test to verify it passes (implementation already added in Step 1)**

Run the unit test command. Expected: PASS. (The type and function were added in Step 1; if the test fails to compile, fix imports/signatures before proceeding.)

- [ ] **Step 4: Commit**

```bash
git add core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/LlmProvider.kt \
        core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProviderTest.kt
git commit -m "feat(network): add ServerTool type and capability-gated defaults"
```

### Task 1.2: Serialize server tools into the wire `tools` array

The wire `tools` array must hold *both* function tools (`{"type":"function","function":{…}}`) and server tools (`{"type":"openrouter:…","parameters":{…}}`). These have different shapes, so build the array as a `JsonArray`.

**Files:**
- Modify: `core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProvider.kt`
- Test: `core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProviderTest.kt`

- [ ] **Step 1: Write the failing wire-serialization test**

Add to `OpenAiCompatibleProviderTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

Run the unit test command. Expected: FAIL — `serverTools` not yet serialized (the body won't contain `openrouter:web_search`), and `ChatCompletionRequest.tools` is still `List<WireTool>?`.

- [ ] **Step 3: Implement the merged tools-array serialization**

In `OpenAiCompatibleProvider.kt`:

(a) Add imports (if not already present):
```kotlin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
```

(b) Change `ChatCompletionRequest.tools` type from `List<WireTool>?` to `JsonArray?`:
```kotlin
    val tools: JsonArray? = null,
```

(c) Replace the `tools = …` line in `toWire` with a call to a new builder:
```kotlin
    private fun ChatRequest.toWire(stream: Boolean) = ChatCompletionRequest(
        model = model,
        messages = messages.map { it.toWire() },
        stream = stream,
        temperature = temperature,
        maxTokens = maxTokens,
        tools = toWireTools(),
        parallelToolCalls = parallelToolCalls,
        modalities = modalities.takeIf { it.isNotEmpty() },
        usage = UsageInclude(include = true),
    )

    /** Merge function tools and server tools into the single wire `tools` array (null when empty). */
    private fun ChatRequest.toWireTools(): JsonArray? {
        if (tools.isEmpty() && serverTools.isEmpty()) return null
        return buildJsonArray {
            tools.forEach { add(json.encodeToJsonElement(WireTool.serializer(), it.toWire())) }
            serverTools.forEach { add(json.encodeToJsonElement(WireServerTool.serializer(), it.toWireServerTool())) }
        }
    }

    private fun ServerTool.toWireServerTool(): WireServerTool = when (this) {
        is ServerTool.WebSearch -> WireServerTool(
            type = "openrouter:web_search",
            parameters = buildJsonObject {
                put("engine", engine)
                maxResults?.let { put("max_results", it) }
            },
        )
        ServerTool.WebFetch -> WireServerTool(type = "openrouter:web_fetch")
        ServerTool.Datetime -> WireServerTool(type = "openrouter:datetime")
        is ServerTool.ImageGeneration -> WireServerTool(
            type = "openrouter:image_generation",
            parameters = buildJsonObject {
                model?.let { put("model", it) }
                size?.let { put("size", it) }
                quality?.let { put("quality", it) }
            }.takeIf { it.isNotEmpty() },
        )
    }
```

(d) Add the `WireServerTool` DTO near `WireTool`:
```kotlin
@Serializable
private data class WireServerTool(
    val type: String,
    val parameters: JsonObject? = null,
)
```

Note: `put(String, Int)` and `put(String, String)` come from `kotlinx.serialization.json.put`, already imported.

- [ ] **Step 4: Run to verify it passes**

Run the unit test command. Expected: PASS (the two new tests and all existing tests).

- [ ] **Step 5: Commit**

```bash
git add core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProvider.kt \
        core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiCompatibleProviderTest.kt
git commit -m "feat(network): serialize server tools into the wire tools array"
```

---

## Chunk 2: Network response side — `url_citation` annotations + `server_tool_use` usage

Decodes `url_citation` annotations into a new `ChatEvent.CitationDelta` carrying a `:core:model` `Citation`, and parses `server_tool_use.web_search_requests` into `Usage`. Image-generation output already flows through `ChatEvent.ImageDelta` (the existing `delta.images` path) — confirmed by the live test in Chunk 3; no new decoder work is needed for images unless the live test reveals a different shape.

**Files:**
- Create: `core/model/src/commonMain/kotlin/io/orangerabbit/clanker/core/model/Citation.kt`
- Modify: `core/model/src/commonMain/kotlin/io/orangerabbit/clanker/core/model/ChatMessage.kt` (add `citations` to `Assistant`)
- Modify: `core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/LlmProvider.kt` (add `Usage.webSearchRequests`, `ChatEvent.CitationDelta`)
- Modify: `core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/OpenAiSseDecoder.kt` (parse annotations + server_tool_use)
- Test: `core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiSseDecoderTest.kt`

### Task 2.1: `Citation` domain type and `Assistant.citations`

- [ ] **Step 1: Create `Citation.kt`**

```kotlin
package io.orangerabbit.clanker.core.model

/**
 * A web source cited by a server-executed web tool (OpenRouter `url_citation` annotation). Display
 * metadata only; the model has already incorporated the content into its answer.
 */
data class Citation(
    val url: String,
    val title: String? = null,
    val content: String? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)
```

- [ ] **Step 2: Add `citations` to `ChatMessage.Assistant`**

In `ChatMessage.kt`, add the field to `Assistant` (after `imageUrls`):

```kotlin
        val imageUrls: List<String> = emptyList(),
        /** Web sources cited by server-executed web tools, for source-chip display. */
        val citations: List<Citation> = emptyList(),
        override val lifecycle: MsgLifecycle = MsgLifecycle.Complete,
```

- [ ] **Step 3: Verify it compiles**

Run the unit test command. Expected: PASS (no behavior change yet; pure additive data classes with defaults). If `:core:model` is not pulled in by `:core:network:jvmTest`, instead run:
```bash
nix develop --command bash -c './gradlew :core:model:compileKotlinJvm :core:network:jvmTest'
```

- [ ] **Step 4: Commit**

```bash
git add core/model/src/commonMain/kotlin/io/orangerabbit/clanker/core/model/Citation.kt \
        core/model/src/commonMain/kotlin/io/orangerabbit/clanker/core/model/ChatMessage.kt
git commit -m "feat(model): add Citation type and Assistant.citations"
```

### Task 2.2: `Usage.webSearchRequests` and `ChatEvent.CitationDelta`

- [ ] **Step 1: Extend `Usage` and add `CitationDelta` in `LlmProvider.kt`**

Add the field to `Usage`:
```kotlin
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double? = null,
    /** Server-tool web search calls OpenRouter executed this turn (from `server_tool_use`). */
    val webSearchRequests: Int? = null,
)
```

Add the event to the `ChatEvent` sealed interface:
```kotlin
    /** A web source cited by a server-executed web tool. May arrive multiple times per turn. */
    data class CitationDelta(val citation: io.orangerabbit.clanker.core.model.Citation) : ChatEvent
```

- [ ] **Step 2: Verify it compiles**

Run the unit test command. Expected: PASS (additive; existing `toUsage()` still compiles because the new field has a default).

- [ ] **Step 3: Commit**

```bash
git add core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/LlmProvider.kt
git commit -m "feat(network): add CitationDelta event and Usage.webSearchRequests"
```

### Task 2.3: Decode `url_citation` annotations

- [ ] **Step 1: Write the failing decoder tests**

Add to `OpenAiSseDecoderTest.kt`:

```kotlin
    @Test
    fun urlCitationAnnotationInDeltaBecomesCitationDelta() {
        val payload = """
            {"choices":[{"index":0,"delta":{"annotations":[
            {"type":"url_citation","url_citation":{"url":"https://example.com/a","title":"A",
            "content":"snippet","start_index":0,"end_index":5}}]},"finish_reason":null}]}
        """.trimIndent().replace("\n", "")
        val events = decoder.decode(payload)
        assertEquals(1, events.size)
        val ev = events.single()
        assertTrue(ev is ChatEvent.CitationDelta)
        assertEquals("https://example.com/a", ev.citation.url)
        assertEquals("A", ev.citation.title)
        assertEquals("snippet", ev.citation.content)
        assertEquals(0, ev.citation.startIndex)
        assertEquals(5, ev.citation.endIndex)
    }

    @Test
    fun contentAndCitationInSameDeltaEmitTextThenCitation() {
        val payload = """
            {"choices":[{"index":0,"delta":{"content":"see","annotations":[
            {"type":"url_citation","url_citation":{"url":"https://example.com/b"}}]},"finish_reason":null}]}
        """.trimIndent().replace("\n", "")
        val events = decoder.decode(payload)
        assertEquals(
            listOf(
                ChatEvent.TextDelta("see"),
                ChatEvent.CitationDelta(io.orangerabbit.clanker.core.model.Citation(url = "https://example.com/b")),
            ),
            events,
        )
    }

    @Test
    fun nonCitationAnnotationIsIgnored() {
        val payload = """
            {"choices":[{"index":0,"delta":{"annotations":[{"type":"file_citation"}]},"finish_reason":null}]}
        """.trimIndent().replace("\n", "")
        assertEquals(emptyList(), decoder.decode(payload))
    }

    @Test
    fun urlCitationOnFinalMessageObjectBecomesCitationDelta() {
        // Defensive: some responses carry annotations on a non-delta `message` object (spec §5/§10).
        val payload = """
            {"choices":[{"index":0,"delta":{},"message":{"annotations":[
            {"type":"url_citation","url_citation":{"url":"https://example.com/c"}}]},"finish_reason":"stop"}]}
        """.trimIndent().replace("\n", "")
        val events = decoder.decode(payload)
        assertEquals(
            listOf(
                ChatEvent.CitationDelta(io.orangerabbit.clanker.core.model.Citation(url = "https://example.com/c")),
                ChatEvent.Finished(FinishReason.Stop),
            ),
            events,
        )
    }
```

- [ ] **Step 2: Run to verify it fails**

Run the unit test command. Expected: FAIL — `annotations` not parsed; `Delta`/`Choice` have no such fields.

- [ ] **Step 3: Implement annotation decoding (both `delta.annotations` and `message.annotations`)**

The spec (§5, §10) requires parsing annotations from **both** the streaming `delta` and a final non-delta `message` object. In `OpenAiSseDecoder.kt`:

(a) Add `annotations` to `Delta` and a `message` field to `Choice`:
```kotlin
@Serializable
private data class Delta(
    val content: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
    val images: List<ImageDto> = emptyList(),
    val annotations: List<AnnotationDto> = emptyList(),
)
```
```kotlin
@Serializable
private data class Choice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    /** Defensive: annotations sometimes ride a final non-delta `message` object (spec §5/§10). */
    val message: MessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class MessageDto(
    val annotations: List<AnnotationDto> = emptyList(),
)
```

(b) Add the annotation DTOs (near `ImageDto`):
```kotlin
@Serializable
private data class AnnotationDto(
    val type: String? = null,
    @SerialName("url_citation") val urlCitation: UrlCitationDto? = null,
)

@Serializable
private data class UrlCitationDto(
    val url: String = "",
    val title: String? = null,
    val content: String? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("end_index") val endIndex: Int? = null,
)
```

(c) Add a top-level helper that maps an annotation list to citation events (near `toFinishReason`):
```kotlin
private fun List<AnnotationDto>.toCitationEvents(): List<ChatEvent> =
    mapNotNull { ann ->
        if (ann.type != "url_citation") return@mapNotNull null
        ann.urlCitation?.takeIf { it.url.isNotEmpty() }?.let { c ->
            ChatEvent.CitationDelta(
                io.orangerabbit.clanker.core.model.Citation(
                    url = c.url,
                    title = c.title,
                    content = c.content,
                    startIndex = c.startIndex,
                    endIndex = c.endIndex,
                ),
            )
        }
    }
```

(d) In `decode`, inside the `if (choice != null)` block, after the `toolCalls` loop, emit citations from both sources (delta first, then the final-message form):
```kotlin
            events += choice.delta.annotations.toCitationEvents()
            choice.message?.annotations?.let { events += it.toCitationEvents() }
```

- [ ] **Step 4: Run to verify it passes**

Run the unit test command. Expected: PASS (new citation tests + all existing).

- [ ] **Step 5: Commit**

```bash
git add core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/OpenAiSseDecoder.kt \
        core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiSseDecoderTest.kt
git commit -m "feat(network): decode url_citation annotations into CitationDelta"
```

### Task 2.4: Decode `server_tool_use` usage

- [ ] **Step 1: Write the failing test**

Add to `OpenAiSseDecoderTest.kt`:

```kotlin
    @Test
    fun serverToolUseIsParsedIntoUsage() {
        val payload = """
            {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
            "cost":0.001,"server_tool_use":{"web_search_requests":2}}}
        """.trimIndent().replace("\n", "")
        val events = decoder.decode(payload)
        val report = events.single()
        assertTrue(report is ChatEvent.UsageReport)
        assertEquals(2, report.usage.webSearchRequests)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run the unit test command. Expected: FAIL — `webSearchRequests` is null (field not parsed).

- [ ] **Step 3: Implement**

In `OpenAiSseDecoder.kt`:

(a) Add `serverToolUse` to `UsageDto`:
```kotlin
@Serializable
private data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    val cost: Double? = null,
    @SerialName("server_tool_use") val serverToolUse: ServerToolUseDto? = null,
)

@Serializable
private data class ServerToolUseDto(
    @SerialName("web_search_requests") val webSearchRequests: Int? = null,
)
```

(b) Map it in `toUsage()`:
```kotlin
private fun UsageDto.toUsage() = Usage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
    costUsd = cost,
    webSearchRequests = serverToolUse?.webSearchRequests,
)
```

- [ ] **Step 4: Run to verify it passes**

Run the unit test command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/network/src/commonMain/kotlin/io/orangerabbit/clanker/core/network/OpenAiSseDecoder.kt \
        core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/OpenAiSseDecoderTest.kt
git commit -m "feat(network): parse server_tool_use web_search_requests into Usage"
```

---

## Chunk 3: App wiring, UI source chips, and live verification

Enables server tools for tool-capable models in `ChatViewModel`, accumulates citations onto the streaming assistant message, renders source chips in the chat bubble, and adds a live web_search round-trip test.

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt`
- Modify: `core/network/src/jvmTest/kotlin/io/orangerabbit/clanker/core/network/LiveOpenRouterTest.kt`

### Task 3.1: Live web_search round-trip (pins the real wire shape first)

Run this BEFORE finalizing the app rendering, so the actual streaming shape of annotations and image output is confirmed against the live API. The decoder already handles both `delta.annotations` and `message.annotations` (Task 2.3); this test confirms which path the live API actually uses and that image-generation output flows through `ImageDelta`. If citations still come through neither path, capture the raw SSE and extend the decoder before continuing.

- [ ] **Step 1: Add the live test**

Add to `LiveOpenRouterTest.kt`:

```kotlin
    @Test
    fun liveWebSearchReturnsCitations() {
        val key = System.getenv("OPENROUTER_API_KEY")
        if (key.isNullOrBlank()) {
            println("OPENROUTER_API_KEY not set — skipping live web search test")
            return
        }

        val provider = openRouterProvider(apiKey = key, engine = CIO.create())
        val request = ChatRequest(
            model = "openai/gpt-4o-mini",
            messages = listOf(
                ChatMessage.User(MessageId("1"), "Search the web: what is the latest stable Kotlin version? Cite a source."),
            ),
            serverTools = listOf(ServerTool.WebSearch()),
        )

        val events = runBlocking { provider.streamChat(request).toList() }

        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        val citations = events.filterIsInstance<ChatEvent.CitationDelta>().map { it.citation }
        val usage = events.filterIsInstance<ChatEvent.UsageReport>().lastOrNull()?.usage
        val failures = events.filterIsInstance<ChatEvent.Failed>()
        println("LIVE web_search: text='${text.take(120)}' citations=${citations.size} usage=$usage failures=${failures.map { it.error }}")

        assertTrue(failures.isEmpty(), "provider returned errors: ${failures.map { it.error }}")
        assertTrue(citations.isNotEmpty(), "expected at least one url_citation; got none")
    }
```

- [ ] **Step 2: Run the live test**

Run the live test command. Expected: PASS, with `citations=N` (N≥1) printed. If it fails with zero citations, inspect the printed events / capture raw SSE and adjust the decoder (Task 2.3) — the annotations may sit on a `message` object rather than `delta`. Do not proceed until citations come through.

- [ ] **Step 3: Commit**

```bash
git add core/network/src/jvmTest/kotlin/io/orangerabbit/clanker/core/network/LiveOpenRouterTest.kt
git commit -m "test(network): live web_search round-trip asserting citations"
```

### Task 3.2: Enable server tools in `ChatViewModel`

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt`

- [ ] **Step 1: Compute and pass `serverTools` in `send()`**

In `ChatViewModel.kt` `send()`, where the request is built (around line 211), resolve the selected model's capabilities and pass `serverTools`:

```kotlin
            val modelId = if (current.imageMode) current.defaultImageModel else current.defaultChatModel
            val modelCaps = current.availableModels.find { it.id == modelId }?.capabilities ?: emptySet()
            val request = ChatRequest(
                model = modelId,
                messages = systemMessages + history,
                serverTools = defaultServerTools(modelCaps),
                modalities = if (current.imageMode) listOf("image", "text") else emptyList(),
            )
```

Add the import:
```kotlin
import io.orangerabbit.clanker.core.network.defaultServerTools
```
(`Capability` is referenced only inside `defaultServerTools`, so no extra import is needed in the ViewModel.)

- [ ] **Step 2: Accumulate citations during the stream**

In the `streamJob` collect block, add a citations accumulator next to `images` (around line 217):
```kotlin
            val buffer = StringBuilder()
            val images = mutableListOf<String>()
            val citations = mutableListOf<Citation>()
            var lastUiUpdate = 0L
```

Add a `CitationDelta` branch in the `when (event)` (before the `else`), de-duplicating by url + startIndex:
```kotlin
                        is ChatEvent.CitationDelta -> {
                            val c = event.citation
                            if (citations.none { it.url == c.url && it.startIndex == c.startIndex }) {
                                citations += c
                                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Streaming)
                            }
                        }
```

Add the import:
```kotlin
import io.orangerabbit.clanker.core.model.Citation
```

- [ ] **Step 3: Thread citations through `updateAssistant`**

Change the `updateAssistant` signature and call sites to carry citations:
```kotlin
    private fun updateAssistant(
        id: MessageId,
        content: String,
        images: List<String>,
        citations: List<Citation>,
        lifecycle: MsgLifecycle,
    ) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg is ChatMessage.Assistant && msg.id == id) {
                        msg.copy(
                            content = content,
                            imageUrls = images.toList(),
                            citations = citations.toList(),
                            lifecycle = lifecycle,
                        )
                    } else {
                        msg
                    }
                },
            )
        }
    }
```

Update every `updateAssistant(...)` call in `send()` (TextDelta throttle, ImageDelta, Finished, Failed, and both catch blocks) to pass `citations`. The `CancellationException` and generic `catch` blocks pass `citations` too:
```kotlin
                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Aborted)
```
and
```kotlin
                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Failed)
```

- [ ] **Step 4: Verify the app compiles**

Run the app build command. Expected: BUILD SUCCESSFUL. Fix any missed `updateAssistant` call site (the compiler will flag arity mismatches).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt
git commit -m "feat(app): enable server tools for capable models and accumulate citations"
```

### Task 3.3: Render source chips in the chat bubble

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt`

- [ ] **Step 1: Extract citations in `MessageBubble`**

In `ChatScreen.kt` `MessageBubble`, after the `images` derivation (around line 276), add:
```kotlin
    val citations = (message as? ChatMessage.Assistant)?.citations ?: emptyList()
```

Also annotate the `MessageBubble` composable with the experimental-layout opt-in (required by `FlowRow`):
```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, onImageTap: (ImageBitmap) -> Unit) {
```

- [ ] **Step 2: Render a source-chip row**

Inside the `ThemedCard { Column { … } }`, after the `images.forEach { … }` block, add the chip row. Use Material3 `AssistChip` and `LocalUriHandler`:

```kotlin
            if (citations.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    citations.distinctBy { it.url }.forEachIndexed { i, c ->
                        AssistChip(
                            onClick = { runCatching { uriHandler.openUri(c.url) } },
                            label = {
                                Text(
                                    text = c.title?.takeIf { it.isNotBlank() } ?: hostOf(c.url),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = {
                                Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }
```

Add a small `hostOf` helper at file scope (bottom of the file):
```kotlin
/** Best-effort host extraction for a citation chip label when no title is available. */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore("/").removePrefix("www.").ifBlank { url }
```

Add ONLY the imports not already present in `ChatScreen.kt`. `Arrangement`, `Modifier`, `padding`, and `dp` are already imported — do NOT re-add them (a duplicate import is a compile error). The genuinely new imports are:
```kotlin
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.ui.platform.LocalUriHandler
```
`FlowRow` is `@ExperimentalLayoutApi` in the current Compose version, which is why `MessageBubble` is annotated `@OptIn(ExperimentalLayoutApi::class)` in Step 1.

- [ ] **Step 3: Verify the app compiles**

Run the app build command. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt
git commit -m "feat(app): render web source chips beneath assistant answers"
```

### Task 3.4: Final verification

- [ ] **Step 1: Full unit test run**

Run the unit test command. Expected: all `:core:network:jvmTest` tests PASS.

- [ ] **Step 2: Live end-to-end (optional, needs key)**

Run the live test command. Expected: both `liveStreamingCompletion` and `liveWebSearchReturnsCitations` PASS.

- [ ] **Step 3: App build**

Run the app build command. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke (optional)**

Install/run the app, ask a question that needs current info (e.g. "what's the latest stable Kotlin release?") with a tool-capable model selected, and confirm the answer renders with tappable source chips.

---

## Out of scope (per spec §2)

- `apply_patch` (Responses API only), `fusion` / `advisor` / `subagent` (cost multipliers).
- Client `AgentLoop` (server runs the loop).
- Per-conversation / global enablement toggles (always-on for capable models).
- Reviving the shelved Exa client-executed path.
