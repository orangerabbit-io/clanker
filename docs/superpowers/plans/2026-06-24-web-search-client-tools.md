# Web Search & Fetch via Client-Executed Tools — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give clanker its first tool use — the model can search the web and read pages via two client-executed Exa-backed tools, driven by a thin-but-real agent loop, answering with citations.

**Architecture:** A new UI-free `:core:tools` module owns the `Tool`/`ToolRegistry` abstraction plus an Exa client and the two read-only tools. A new `AgentLoop` in `:core:agent` streams from the provider, accumulates tool-call deltas, dispatches them serially through the registry, and re-requests until the model stops (bounded by an iteration cap, cleanly cancellable). `:core:network` is already tool-ready and needs no production change. `:app` drives the loop from `ChatViewModel`, stores the Exa key in the existing Tink `SecretStore`, and renders collapsed tool cards + source chips.

**Tech Stack:** Kotlin Multiplatform (jvm + android targets), Ktor 3.5 client + MockEngine for tests, kotlinx.serialization, kotlinx.coroutines (Flow/channelFlow), Metro DI, Jetpack Compose, the vendored cyberpunk design system.

**Spec:** `docs/superpowers/specs/2026-06-24-web-search-client-tools-design.md` (approved). Read it before starting.

**Conventions to follow (verified in-repo):**
- Modules are KMP: `kotlin.multiplatform` + `android.kmp.library` plugins, `jvm()` + `android { … }` targets, deps in `commonMain.dependencies`/`commonTest.dependencies` (see `core/network/build.gradle.kts`).
- Tests: `kotlin("test")` (`@Test`, `assertEquals`, `assertTrue`), `kotlinx.coroutines.test.runTest`, Ktor `MockEngine`. Run on the `jvm()` target.
- Value-class ids wrap `String`; **fresh-id generation does not live in `:core:model`/`commonMain`** (no `UUID` there) — the loop takes an injected `idGen: () -> String`; the app passes `{ UUID.randomUUID().toString() }`.
- Package roots: `io.orangerabbit.clanker.core.tools`, `…core.agent`. App packages: `…ui`, `…data`, `…di`.
- All Gradle runs go through `nix develop` (AGP needs the SDK at config time). The `aapt2FromMavenOverride` `-P` flag is only needed for `:app` assemble tasks, not for `:core:*:jvmTest`.

**Build/test commands:**
- Module tests: `nix develop --command ./gradlew :core:tools:jvmTest` (swap module/task as needed).
- App build: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`.
- Live Exa check: `EXA_API_KEY=… nix develop --command ./gradlew :core:tools:jvmTest --tests '*LiveExaTest' --rerun-tasks`.

**Reconciliation with the spec (documented refinements, no scope change):**
- `AgentEvent` is renamed to match the existing `ChatEvent` convention: errors surface via `Failed(ApiError)` and `Finished` carries only `Stop | IterationCapReached`; usage via `UsageReport`. Two events are added so the VM can manage multiple assistant bubbles across loop iterations: `AssistantTurnStarted(id)` and `AssistantCommitted(message)`. The spec's `ToolCallStarted(call)` is folded into `AssistantCommitted.message.toolCalls` (the committed assistant already lists its calls).
- The "no orphan `tool_call_id` on abort" invariant is enforced by a pure, unit-tested helper `reconcileAbortedToolCalls(messages)` in `:core:agent`, called by the ViewModel when it cancels — because synthetic replies must be appended to the *displayed* history after the cold flow is torn down, which is the VM's responsibility, not the flow's.

---

## File Structure

**`:core:tools` (new module)**
- `core/tools/build.gradle.kts` — module build; depends on `:core:model` + `:core:network` (for `ToolSpec`) + Ktor.
- `core/tools/src/commonMain/.../core/tools/Tool.kt` — `Tool`, `ToolProvider`, `ToolResult`, `ToolDisplay`, `ToolContext`, `ToolSource`, `ToolExecution`, `RiskLevel`.
- `core/tools/src/commonMain/.../core/tools/ToolRegistry.kt` — `ToolRegistry`, `DispatchOutcome`.
- `core/tools/src/commonMain/.../core/tools/exa/ExaClient.kt` — Ktor client + isolated Exa DTOs.
- `core/tools/src/commonMain/.../core/tools/exa/WebTools.kt` — `ExaSearchTool`, `WebFetchTool`, `NativeToolProvider`.
- `core/tools/src/commonTest/.../core/tools/ToolRegistryTest.kt`, `WebToolsTest.kt`, `LiveExaTest.kt`.

**`:core:agent` (extend)**
- `core/agent/build.gradle.kts` — add deps on `:core:model`, `:core:network`, `:core:tools`, coroutines.
- `core/agent/src/commonMain/.../core/agent/AgentLoop.kt` — `AgentLoop`, `AgentEvent`, `AgentFinish`.
- `core/agent/src/commonMain/.../core/agent/AbortReconciliation.kt` — `reconcileAbortedToolCalls`.
- `core/agent/src/commonTest/.../core/agent/AgentLoopTest.kt`, `AbortReconciliationTest.kt`.

**`:core:network` (test only)**
- `core/network/src/commonTest/.../core/network/ToolMessageWireTest.kt` — `role:tool` round-trip (only if absent).

**`:app` (extend)**
- `app/.../data/SecretStore.kt` — add `saveExaKey`/`loadExaKey`.
- `app/.../ui/ChatViewModel.kt` — drive `AgentLoop`; Exa key state; tool-display map; abort reconciliation.
- `app/.../ui/SettingsScreen.kt` — masked Exa-key field.
- `app/.../ui/ChatScreen.kt` — tool-call cards + source chips.
- `app/.../core/agent/SystemPrompt.kt` — capability-honest text now that tools exist.
- `settings.gradle.kts` — `include(":core:tools")`.

---

## Chunk 1: `:core:tools` foundation — Tool model + ToolRegistry

Builds the abstraction and the registry with its load-bearing one-reply-per-id invariant, with a fake tool. No Exa yet.

### Task 1: Create the `:core:tools` module skeleton

**Files:**
- Create: `core/tools/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Add the module include**

In `settings.gradle.kts`, add after `include(":core:network")`:

```kotlin
include(":core:tools")
```

- [ ] **Step 2: Write the build file**

Create `core/tools/build.gradle.kts` (mirrors `core/network/build.gradle.kts`):

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    android {
        namespace = "io.orangerabbit.clanker.core.tools"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:network")) // ToolSpec lives here
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.cio) // real engine for LiveExaTest
        }
    }
}
```

- [ ] **Step 3: Verify the module configures**

Run: `nix develop --command ./gradlew :core:tools:tasks`
Expected: configures without error and lists tasks including `jvmTest`.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts core/tools/build.gradle.kts
git commit -m "build(tools): scaffold :core:tools module"
```

### Task 2: Define the Tool model types

**Files:**
- Create: `core/tools/src/commonMain/kotlin/io/orangerabbit/clanker/core/tools/Tool.kt`

- [ ] **Step 1: Write the types** (no test — pure declarations exercised by later tasks)

```kotlin
package io.orangerabbit.clanker.core.tools

import io.orangerabbit.clanker.core.model.ToolCall
import kotlinx.serialization.json.JsonObject

/** Where a tool comes from. Only [Native] is wired in this increment. */
enum class ToolSource { Native, Ssh, Mcp }

/** Who runs the tool. Only [ClientExecuted] is wired in this increment. */
enum class ToolExecution { ClientExecuted, ProviderExecuted }

/** Drives approval + concurrency policy later. All tools here are [ReadOnly] (auto-run). */
enum class RiskLevel { ReadOnly, Mutating, Destructive }

/**
 * Structured, display-only data for a tool card — lets the UI render results/sources without
 * parsing the model-facing [ToolResult.content] string. Null when there is nothing to show.
 */
sealed interface ToolDisplay {
    data class Search(val query: String, val sources: List<Source>) : ToolDisplay {
        data class Source(val title: String, val url: String)
    }
    data class Fetch(val url: String) : ToolDisplay
}

/** Outcome of a single tool execution: model-facing text + optional UI display data. */
data class ToolResult(
    val content: String,
    val isError: Boolean,
    val display: ToolDisplay? = null,
)

/** Client-execution dependencies handed to tools at call time. */
interface ToolContext {
    val exaClient: io.orangerabbit.clanker.core.tools.exa.ExaClient
}

/**
 * One callable tool. [parameters] is the JSON Schema advertised to the model; the registry
 * serializes it into the network `ToolSpec`. Args reaching [execute] are already JSON-parsed but
 * NOT yet validated — each tool validates/clamps them itself (model output is hostile).
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    val source: ToolSource
    val execution: ToolExecution
    val riskLevel: RiskLevel
    val timeoutMs: Long
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

interface ToolProvider {
    suspend fun tools(): List<Tool>
}
```

> Note: `ToolContext` forward-references `exa.ExaClient`, created in Chunk 2. This file will not compile alone — it compiles once Task 6 lands. If you prefer per-task green builds, defer this file's `ToolContext` member to Task 6, or stub `ExaClient` as an empty interface now and flesh it out later. Recommended: create a minimal `ExaClient` interface stub in Task 2b below so Chunk 1 compiles independently.

- [ ] **Step 2: Stub `ExaClient` so Chunk 1 compiles**

Create `core/tools/src/commonMain/kotlin/io/orangerabbit/clanker/core/tools/exa/ExaClient.kt` with just the interface for now (implementation in Chunk 2):

```kotlin
package io.orangerabbit.clanker.core.tools.exa

/** Thin wrapper over Exa's /search and /contents endpoints. Implemented in Chunk 2. */
interface ExaClient {
    suspend fun search(query: String, numResults: Int): List<ExaResult>
    suspend fun contents(url: String): String
}

/** One Exa search hit (provider-neutral; Exa wire DTOs never escape this module). */
data class ExaResult(val title: String, val url: String, val highlight: String)
```

- [ ] **Step 3: Verify compile**

Run: `nix develop --command ./gradlew :core:tools:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/tools/src/commonMain
git commit -m "feat(tools): Tool/ToolProvider/ToolResult model + ExaClient interface"
```

### Task 3: ToolRegistry — toolSpecs() + dispatch() with the one-reply-per-id invariant

**Files:**
- Create: `core/tools/src/commonMain/kotlin/io/orangerabbit/clanker/core/tools/ToolRegistry.kt`
- Test: `core/tools/src/commonTest/kotlin/io/orangerabbit/clanker/core/tools/ToolRegistryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.orangerabbit.clanker.core.tools

import io.orangerabbit.clanker.core.model.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun call(name: String, args: String = "{}") =
    ToolCall(id = "call_1", name = name, argumentsJson = args)

private class FakeTool(
    override val name: String,
    override val timeoutMs: Long = 1_000,
    val body: suspend (JsonObject) -> ToolResult,
) : Tool {
    override val description = "fake"
    override val parameters = buildJsonObject { put("type", "object") }
    override val source = ToolSource.Native
    override val execution = ToolExecution.ClientExecuted
    override val riskLevel = RiskLevel.ReadOnly
    override suspend fun execute(args: JsonObject, ctx: ToolContext) = body(args)
}

private val noCtx = object : ToolContext {
    override val exaClient get() = error("unused")
}

class ToolRegistryTest {

    @Test
    fun `toolSpecs serializes parameters JsonObject to a schema string`() = runTest {
        val tool = FakeTool("native__x") { ToolResult("ok", isError = false) }
        val registry = ToolRegistry(providers = listOf(provider(tool)), context = noCtx)
        val specs = registry.toolSpecs()
        assertEquals(1, specs.size)
        assertEquals("native__x", specs[0].name)
        assertTrue(specs[0].parametersJsonSchema.contains("\"type\":\"object\""))
    }

    @Test
    fun `dispatch routes to the matching tool and keys the reply to the call id`() = runTest {
        val tool = FakeTool("native__x") { ToolResult("result-text", isError = false) }
        val registry = ToolRegistry(providers = listOf(provider(tool)), context = noCtx)
        val outcome = registry.dispatch(call("native__x"))
        assertEquals("call_1", outcome.reply.toolCallId)
        assertEquals("result-text", outcome.reply.content)
        assertTrue(!outcome.reply.isError)
    }

    @Test
    fun `unknown tool yields an error reply, never throws`() = runTest {
        val registry = ToolRegistry(providers = emptyList(), context = noCtx)
        val outcome = registry.dispatch(call("native__missing"))
        assertTrue(outcome.reply.isError)
        assertEquals("call_1", outcome.reply.toolCallId)
    }

    @Test
    fun `malformed arguments json yields an error reply`() = runTest {
        val tool = FakeTool("native__x") { ToolResult("ok", isError = false) }
        val registry = ToolRegistry(providers = listOf(provider(tool)), context = noCtx)
        val outcome = registry.dispatch(call("native__x", args = "{not json"))
        assertTrue(outcome.reply.isError)
    }

    @Test
    fun `a throwing tool yields an error reply`() = runTest {
        val tool = FakeTool("native__x") { error("boom") }
        val registry = ToolRegistry(providers = listOf(provider(tool)), context = noCtx)
        val outcome = registry.dispatch(call("native__x"))
        assertTrue(outcome.reply.isError)
        assertTrue(outcome.reply.content.contains("boom"))
    }

    @Test
    fun `a tool exceeding its timeout yields an error reply`() = runTest {
        val tool = FakeTool("native__slow", timeoutMs = 10) { delay(10_000); ToolResult("late", false) }
        val registry = ToolRegistry(providers = listOf(provider(tool)), context = noCtx)
        val outcome = registry.dispatch(call("native__slow"))
        assertTrue(outcome.reply.isError)
    }

    private fun provider(vararg tools: Tool) = object : ToolProvider {
        override suspend fun tools() = tools.toList()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*ToolRegistryTest'`
Expected: FAIL — `ToolRegistry` / `DispatchOutcome` unresolved.

- [ ] **Step 3: Implement ToolRegistry**

```kotlin
package io.orangerabbit.clanker.core.tools

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.ToolCall
import io.orangerabbit.clanker.core.network.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A dispatched call's result: the role:tool reply (always present) + optional UI display data. */
data class DispatchOutcome(val reply: ChatMessage.Tool, val display: ToolDisplay?)

/**
 * Aggregates [ToolProvider]s, advertises their tools to the model, and dispatches a [ToolCall] to
 * the right tool. Owns the load-bearing invariant: **every dispatch yields exactly one
 * [ChatMessage.Tool] keyed to the call id** — unknown tool, bad args, timeout, or a throwing tool
 * all map to an error reply, never a missing one and never an escaping exception. This is what
 * guarantees the agent loop never resends an unanswered `tool_call_id`.
 *
 * [idGen] supplies fresh message ids (the platform owns id generation; commonMain has no UUID).
 */
class ToolRegistry(
    private val providers: List<ToolProvider>,
    private val context: ToolContext,
    private val idGen: () -> String = { "tool-msg" },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    suspend fun toolSpecs(): List<ToolSpec> = allTools().map { tool ->
        ToolSpec(
            name = tool.name,
            description = tool.description,
            parametersJsonSchema = json.encodeToString(JsonObject.serializer(), tool.parameters),
        )
    }

    suspend fun dispatch(call: ToolCall): DispatchOutcome {
        val tool = allTools().firstOrNull { it.name == call.name }
            ?: return error(call, "unknown tool '${call.name}'")

        val args = try {
            json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (e: Exception) {
            return error(call, "invalid arguments json: ${e.message}")
        }

        return try {
            val result = withTimeout(tool.timeoutMs) { tool.execute(args, context) }
            DispatchOutcome(
                reply = ChatMessage.Tool(
                    id = MessageId(idGen()),
                    toolCallId = call.id,
                    content = result.content,
                    isError = result.isError,
                ),
                display = result.display,
            )
        } catch (e: CancellationException) {
            throw e // honor structured cancellation; the VM reconciles orphaned ids on abort
        } catch (e: Exception) {
            error(call, e.message ?: "tool failed")
        }
    }

    private suspend fun allTools(): List<Tool> = providers.flatMap { it.tools() }

    private fun error(call: ToolCall, message: String) = DispatchOutcome(
        reply = ChatMessage.Tool(
            id = MessageId(idGen()),
            toolCallId = call.id,
            content = message,
            isError = true,
        ),
        display = null,
    )
}
```

> Note on the timeout test: `withTimeout` inside `runTest`'s virtual-time scheduler will trip when the tool `delay`s past `timeoutMs`. `CancellationException` from `withTimeout`'s `TimeoutCancellationException` is a subclass — but it is caught by the generic `catch (e: Exception)` only if not rethrown. `TimeoutCancellationException` IS a `CancellationException`, so the explicit `catch (e: CancellationException) { throw e }` would rethrow it and fail the test. Fix: catch `TimeoutCancellationException` explicitly BEFORE the `CancellationException` clause and map it to an error reply. Add:
> ```kotlin
> } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
>     error(call, "timed out after ${tool.timeoutMs}ms")
> } catch (e: CancellationException) {
>     throw e
> } catch (e: Exception) { … }
> ```
> Order matters: the timeout clause must precede the `CancellationException` clause.

- [ ] **Step 4: Run tests to verify pass**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*ToolRegistryTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/tools/src
git commit -m "feat(tools): ToolRegistry with one-reply-per-id dispatch invariant"
```

---

## Chunk 2: Exa integration — client + the two web tools

### Task 4: ExaClient against MockEngine

**Files:**
- Modify: `core/tools/src/commonMain/kotlin/io/orangerabbit/clanker/core/tools/exa/ExaClient.kt`
- Test: `core/tools/src/commonTest/kotlin/io/orangerabbit/clanker/core/tools/exa/ExaClientTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.orangerabbit.clanker.core.tools.exa

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExaClientTest {

    @Test
    fun `search posts query and parses results with highlights`() = runTest {
        var sentApiKey: String? = null
        var sentBody: String? = null
        val engine = MockEngine { req ->
            sentApiKey = req.headers["x-api-key"]
            sentBody = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"results":[
                        {"title":"T1","url":"https://a.example","highlights":["snippet one"]},
                        {"title":"T2","url":"https://b.example","highlights":["snippet two"]}
                    ]}""".trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpExaClient(apiKey = "exa-key", engine = engine)
        val results = client.search("kotlin coroutines", numResults = 2)

        assertEquals("exa-key", sentApiKey)
        assertTrue(sentBody!!.contains("kotlin coroutines"))
        assertEquals(2, results.size)
        assertEquals("T1", results[0].title)
        assertEquals("https://a.example", results[0].url)
        assertEquals("snippet one", results[0].highlight)
    }

    @Test
    fun `contents posts the url and returns extracted text`() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(
                    """{"results":[{"url":"https://a.example","text":"full page text"}]}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpExaClient(apiKey = "exa-key", engine = engine)
        assertEquals("full page text", client.contents("https://a.example"))
    }

    @Test
    fun `a non-2xx response throws so the tool can map it to an error reply`() = runTest {
        val engine = MockEngine {
            respond(content = ByteReadChannel("nope"), status = HttpStatusCode.Unauthorized)
        }
        val client = HttpExaClient(apiKey = "bad", engine = engine)
        try {
            client.search("x", numResults = 1)
            kotlin.test.fail("expected an exception")
        } catch (e: Exception) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*ExaClientTest'`
Expected: FAIL — `HttpExaClient` unresolved.

- [ ] **Step 3: Implement `HttpExaClient` + isolated DTOs**

Replace `ExaClient.kt` (keep the `ExaClient` interface + `ExaResult` from Task 2, add the impl):

```kotlin
package io.orangerabbit.clanker.core.tools.exa

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ExaClient {
    suspend fun search(query: String, numResults: Int): List<ExaResult>
    suspend fun contents(url: String): String
}

data class ExaResult(val title: String, val url: String, val highlight: String)

/** Thrown on a non-2xx Exa response; the tools turn it into a `ToolResult(isError=true)`. */
class ExaException(message: String) : Exception(message)

/**
 * Ktor-backed Exa client. The [engine] is injected so tests use MockEngine and prod uses OkHttp.
 * Exa wire DTOs ([SearchRequest]/[SearchResponse]/…) are private and never leave this file.
 */
class HttpExaClient(
    private val apiKey: String,
    engine: HttpClientEngine,
    private val baseUrl: String = "https://api.exa.ai",
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) : ExaClient {

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun search(query: String, numResults: Int): List<ExaResult> {
        val resp = client.post("$baseUrl/search") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            setBody(SearchRequest(query = query, numResults = numResults, contents = ContentsOpt()))
        }
        if (!resp.status.isSuccess()) fail(resp.status, resp.bodyAsText())
        return resp.body<SearchResponse>().results.map {
            ExaResult(title = it.title ?: it.url, url = it.url, highlight = it.highlights.firstOrNull() ?: "")
        }
    }

    override suspend fun contents(url: String): String {
        val resp = client.post("$baseUrl/contents") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            setBody(ContentsRequest(urls = listOf(url), text = true))
        }
        if (!resp.status.isSuccess()) fail(resp.status, resp.bodyAsText())
        return resp.body<ContentsResponse>().results.firstOrNull()?.text.orEmpty()
    }

    private fun fail(status: HttpStatusCode, body: String): Nothing =
        throw ExaException("Exa ${status.value}: ${body.take(200)}")

    @Serializable private data class SearchRequest(
        val query: String,
        val numResults: Int,
        val contents: ContentsOpt,
    )
    @Serializable private data class ContentsOpt(
        val highlights: Boolean = true,
        val text: Boolean = true,
    )
    @Serializable private data class SearchResponse(val results: List<Hit> = emptyList())
    @Serializable private data class Hit(
        val title: String? = null,
        val url: String,
        val highlights: List<String> = emptyList(),
    )
    @Serializable private data class ContentsRequest(val urls: List<String>, val text: Boolean)
    @Serializable private data class ContentsResponse(val results: List<ContentHit> = emptyList())
    @Serializable private data class ContentHit(val url: String, val text: String? = null)
}
```

- [ ] **Step 4: Run to verify pass**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*ExaClientTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/tools/src
git commit -m "feat(tools): Ktor ExaClient (search + contents) with isolated DTOs"
```

### Task 5: The two tools + NativeToolProvider (arg validation/clamping)

**Files:**
- Create: `core/tools/src/commonMain/kotlin/io/orangerabbit/clanker/core/tools/exa/WebTools.kt`
- Test: `core/tools/src/commonTest/kotlin/io/orangerabbit/clanker/core/tools/exa/WebToolsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.orangerabbit.clanker.core.tools.exa

import io.orangerabbit.clanker.core.tools.ToolContext
import io.orangerabbit.clanker.core.tools.ToolDisplay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeExa(
    val onSearch: (String, Int) -> List<ExaResult> = { _, _ -> emptyList() },
    val onContents: (String) -> String = { "" },
) : ExaClient {
    var lastNumResults: Int? = null
    override suspend fun search(query: String, numResults: Int): List<ExaResult> {
        lastNumResults = numResults; return onSearch(query, numResults)
    }
    override suspend fun contents(url: String) = onContents(url)
}

private fun ctx(exa: ExaClient) = object : ToolContext { override val exaClient = exa }
private fun args(s: String) = Json.parseToJsonElement(s).jsonObject

class WebToolsTest {

    @Test
    fun `search returns model-readable content and a Search display`() = runTest {
        val exa = FakeExa(onSearch = { _, _ -> listOf(
            ExaResult("Kotlin", "https://kotlinlang.org", "Kotlin is a language"),
        ) })
        val result = ExaSearchTool.execute(args("""{"query":"kotlin"}"""), ctx(exa))
        assertTrue(!result.isError)
        assertTrue(result.content.contains("https://kotlinlang.org"))
        val display = result.display as ToolDisplay.Search
        assertEquals("kotlin", display.query)
        assertEquals("https://kotlinlang.org", display.sources.single().url)
    }

    @Test
    fun `search clamps numResults into 1 to 10`() = runTest {
        val exa = FakeExa()
        ExaSearchTool.execute(args("""{"query":"x","numResults":99}"""), ctx(exa))
        assertEquals(10, exa.lastNumResults)
        ExaSearchTool.execute(args("""{"query":"x","numResults":0}"""), ctx(exa))
        assertEquals(1, exa.lastNumResults)
    }

    @Test
    fun `search with a missing query is an error reply`() = runTest {
        val result = ExaSearchTool.execute(args("""{}"""), ctx(FakeExa()))
        assertTrue(result.isError)
    }

    @Test
    fun `fetch returns page text and a Fetch display`() = runTest {
        val exa = FakeExa(onContents = { "page body" })
        val result = WebFetchTool.execute(args("""{"url":"https://a.example"}"""), ctx(exa))
        assertTrue(!result.isError)
        assertEquals("page body", result.content)
        assertEquals("https://a.example", (result.display as ToolDisplay.Fetch).url)
    }

    @Test
    fun `fetch rejects a non-http url before calling exa`() = runTest {
        val result = WebFetchTool.execute(args("""{"url":"file:///etc/passwd"}"""), ctx(FakeExa()))
        assertTrue(result.isError)
    }

    @Test
    fun `an exa exception maps to an error reply, not a throw`() = runTest {
        val exa = FakeExa(onSearch = { _, _ -> throw ExaException("Exa 401") })
        val result = ExaSearchTool.execute(args("""{"query":"x"}"""), ctx(exa))
        assertTrue(result.isError)
        assertTrue(result.content.contains("401"))
    }

    @Test
    fun `provider advertises both tools`() = runTest {
        val names = NativeToolProvider().tools().map { it.name }
        assertTrue(names.contains("native__web_search"))
        assertTrue(names.contains("native__web_fetch"))
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*WebToolsTest'`
Expected: FAIL — `ExaSearchTool` / `WebFetchTool` / `NativeToolProvider` unresolved.

- [ ] **Step 3: Implement the tools**

```kotlin
package io.orangerabbit.clanker.core.tools.exa

import io.orangerabbit.clanker.core.tools.RiskLevel
import io.orangerabbit.clanker.core.tools.Tool
import io.orangerabbit.clanker.core.tools.ToolContext
import io.orangerabbit.clanker.core.tools.ToolDisplay
import io.orangerabbit.clanker.core.tools.ToolExecution
import io.orangerabbit.clanker.core.tools.ToolProvider
import io.orangerabbit.clanker.core.tools.ToolResult
import io.orangerabbit.clanker.core.tools.ToolSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `native__web_search` — Exa search; model decides when to call it. Read-only, auto-run. */
object ExaSearchTool : Tool {
    override val name = "native__web_search"
    override val description =
        "Search the web for current information. Returns titled results with source URLs and " +
            "extractive snippets. Cite the URLs you use."
    override val source = ToolSource.Native
    override val execution = ToolExecution.ClientExecuted
    override val riskLevel = RiskLevel.ReadOnly
    override val timeoutMs = 20_000L
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "The search query") }
            putJsonObject("numResults") {
                put("type", "integer"); put("description", "How many results (1-10, default 5)")
            }
        }
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = (args["query"]?.jsonPrimitive?.contentOrNullSafe())?.takeIf { it.isNotBlank() }
            ?: return ToolResult("error: 'query' is required", isError = true)
        val numResults = (args["numResults"]?.jsonPrimitive?.intOrNullSafe() ?: 5).coerceIn(1, 10)
        return try {
            val results = ctx.exaClient.search(query, numResults)
            val content = if (results.isEmpty()) "No results." else results.mapIndexed { i, r ->
                "[${i + 1}] ${r.title}\n${r.url}\n${r.highlight}"
            }.joinToString("\n\n")
            ToolResult(
                content = content,
                isError = false,
                display = ToolDisplay.Search(query, results.map { ToolDisplay.Search.Source(it.title, it.url) }),
            )
        } catch (e: ExaException) {
            ToolResult("search failed: ${e.message}", isError = true)
        }
    }
}

/** `native__web_fetch` — Exa /contents for a model-supplied URL. Exa fetches, not the device. */
object WebFetchTool : Tool {
    override val name = "native__web_fetch"
    override val description = "Fetch and extract the readable text of a web page by URL."
    override val source = ToolSource.Native
    override val execution = ToolExecution.ClientExecuted
    override val riskLevel = RiskLevel.ReadOnly
    override val timeoutMs = 20_000L
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "Absolute http(s) URL") }
        }
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
    }

    private val httpUrl = Regex("^https?://", RegexOption.IGNORE_CASE)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return ToolResult("error: 'url' is required", isError = true)
        if (!httpUrl.containsMatchIn(url)) return ToolResult("error: url must be http(s)", isError = true)
        return try {
            ToolResult(ctx.exaClient.contents(url), isError = false, display = ToolDisplay.Fetch(url))
        } catch (e: ExaException) {
            ToolResult("fetch failed: ${e.message}", isError = true)
        }
    }
}

class NativeToolProvider : ToolProvider {
    override suspend fun tools(): List<Tool> = listOf(ExaSearchTool, WebFetchTool)
}

// Safe accessors: a model can send a JSON number where we expect a string, etc.
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else content.takeIf { it.isNotBlank() }
private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? = content.toIntOrNull()
```

> Note: the `contentOrNullSafe`/`intOrNullSafe` helpers tolerate the model emitting `numResults` as either `5` or `"5"`, and a `query` that is a JSON string. Keep them defensive — model output is hostile.

- [ ] **Step 4: Run to verify pass**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*WebToolsTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add core/tools/src
git commit -m "feat(tools): web_search + web_fetch tools with arg clamping"
```

### Task 6: LiveExaTest (self-skipping integration)

**Files:**
- Test: `core/tools/src/commonTest/kotlin/io/orangerabbit/clanker/core/tools/exa/LiveExaTest.kt`

- [ ] **Step 1: Write the test** (mirrors the existing `LiveOpenRouterTest` self-skip pattern)

```kotlin
package io.orangerabbit.clanker.core.tools.exa

import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Hits the real Exa API. Self-skips unless EXA_API_KEY is set, so CI without the key stays green. */
class LiveExaTest {
    private val apiKey: String? = System.getenv("EXA_API_KEY")

    @Test
    fun `live search returns results`() = runTest {
        val key = apiKey ?: run { println("LiveExaTest skipped: no EXA_API_KEY"); return@runTest }
        val client = HttpExaClient(apiKey = key, engine = CIO.create())
        val results = client.search("Kotlin Multiplatform", numResults = 3)
        assertTrue(results.isNotEmpty(), "expected at least one live result")
        assertTrue(results.first().url.startsWith("http"))
    }
}
```

- [ ] **Step 2: Verify it skips cleanly without the key**

Run: `nix develop --command ./gradlew :core:tools:jvmTest --tests '*LiveExaTest'`
Expected: PASS (prints "skipped").

- [ ] **Step 3: (Optional, manual) verify against the live API**

Run: `EXA_API_KEY=<key> nix develop --command ./gradlew :core:tools:jvmTest --tests '*LiveExaTest' --rerun-tasks`
Expected: PASS with real results.

- [ ] **Step 4: Run the full module suite**

Run: `nix develop --command ./gradlew :core:tools:jvmTest`
Expected: PASS (all tasks 3–6 green).

- [ ] **Step 5: Commit**

```bash
git add core/tools/src
git commit -m "test(tools): self-skipping LiveExaTest"
```

---

## Chunk 3: `:core:agent` — the AgentLoop

### Task 7: Wire `:core:agent` dependencies

**Files:**
- Modify: `core/agent/build.gradle.kts`

- [ ] **Step 1: Add deps**

Replace the `sourceSets` block of `core/agent/build.gradle.kts` with:

```kotlin
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:network"))
            implementation(project(":core:tools"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
```

- [ ] **Step 2: Verify configure + existing tests still pass**

Run: `nix develop --command ./gradlew :core:agent:jvmTest`
Expected: PASS (existing `SystemPromptTest`).

- [ ] **Step 3: Commit**

```bash
git add core/agent/build.gradle.kts
git commit -m "build(agent): depend on :core:network and :core:tools"
```

### Task 8: `reconcileAbortedToolCalls` (the abort invariant, pure + testable)

**Files:**
- Create: `core/agent/src/commonMain/kotlin/io/orangerabbit/clanker/core/agent/AbortReconciliation.kt`
- Test: `core/agent/src/commonTest/kotlin/io/orangerabbit/clanker/core/agent/AbortReconciliationTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.orangerabbit.clanker.core.agent

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assistant(id: String, vararg callIds: String) = ChatMessage.Assistant(
    id = MessageId(id),
    content = null,
    toolCalls = callIds.map { ToolCall(it, "native__web_search", "{}") },
)
private fun toolReply(callId: String) = ChatMessage.Tool(MessageId("t-$callId"), callId, "ok")

class AbortReconciliationTest {

    @Test
    fun `unanswered tool calls get synthetic aborted replies`() {
        val msgs = listOf(assistant("a1", "c1", "c2"), toolReply("c1"))
        val out = reconcileAbortedToolCalls(msgs) { "gen" }
        val toolMsgs = out.filterIsInstance<ChatMessage.Tool>()
        assertEquals(2, toolMsgs.size)
        val synthetic = toolMsgs.single { it.toolCallId == "c2" }
        assertTrue(synthetic.isError)
        assertTrue(synthetic.content.contains("aborted"))
    }

    @Test
    fun `fully answered history is unchanged`() {
        val msgs = listOf(assistant("a1", "c1"), toolReply("c1"))
        assertEquals(msgs, reconcileAbortedToolCalls(msgs) { "gen" })
    }

    @Test
    fun `assistant with no tool calls is untouched`() {
        val msgs = listOf(ChatMessage.Assistant(MessageId("a1"), content = "hi"))
        assertEquals(msgs, reconcileAbortedToolCalls(msgs) { "gen" })
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `nix develop --command ./gradlew :core:agent:jvmTest --tests '*AbortReconciliationTest'`
Expected: FAIL — `reconcileAbortedToolCalls` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package io.orangerabbit.clanker.core.agent

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.MsgLifecycle

/**
 * Ensures every assistant `tool_call` in [messages] has a matching `role:tool` reply, appending a
 * synthetic "aborted by user" error reply for any that don't. Called by the ViewModel when it
 * cancels a turn, so a resend after abort never carries an orphaned `tool_call_id` (which 400s).
 * Pure and order-preserving: synthetic replies are inserted directly after their assistant turn.
 */
fun reconcileAbortedToolCalls(
    messages: List<ChatMessage>,
    idGen: () -> String,
): List<ChatMessage> {
    val answered = messages.filterIsInstance<ChatMessage.Tool>().map { it.toolCallId }.toMutableSet()
    val out = mutableListOf<ChatMessage>()
    for (msg in messages) {
        out += msg
        if (msg is ChatMessage.Assistant) {
            for (call in msg.toolCalls) {
                if (call.id !in answered) {
                    answered += call.id
                    out += ChatMessage.Tool(
                        id = MessageId(idGen()),
                        toolCallId = call.id,
                        content = "aborted by user",
                        isError = true,
                        lifecycle = MsgLifecycle.Aborted,
                    )
                }
            }
        }
    }
    return out
}
```

- [ ] **Step 4: Run to verify pass**

Run: `nix develop --command ./gradlew :core:agent:jvmTest --tests '*AbortReconciliationTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/agent/src
git commit -m "feat(agent): reconcileAbortedToolCalls guards the no-orphan-id invariant"
```

### Task 9: AgentLoop — multi-iteration tool loop with a fake provider

**Files:**
- Create: `core/agent/src/commonMain/kotlin/io/orangerabbit/clanker/core/agent/AgentLoop.kt`
- Test: `core/agent/src/commonTest/kotlin/io/orangerabbit/clanker/core/agent/AgentLoopTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.orangerabbit.clanker.core.agent

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.ProviderId
import io.orangerabbit.clanker.core.network.ChatEvent
import io.orangerabbit.clanker.core.network.ChatRequest
import io.orangerabbit.clanker.core.network.ChatResponse
import io.orangerabbit.clanker.core.network.FinishReason
import io.orangerabbit.clanker.core.network.LlmProvider
import io.orangerabbit.clanker.core.network.ModelInfo
import io.orangerabbit.clanker.core.tools.NativeToolProviderStub
import io.orangerabbit.clanker.core.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider scripted with a queue of event-lists, one per streamChat call, so we can drive the
 * loop through N iterations deterministically.
 */
private class ScriptedProvider(private val rounds: ArrayDeque<List<ChatEvent>>) : LlmProvider {
    override val id = ProviderId("scripted")
    override val displayName = "scripted"
    val requests = mutableListOf<ChatRequest>()
    override suspend fun listModels(): List<ModelInfo> = emptyList()
    override suspend fun chat(request: ChatRequest): ChatResponse = error("unused")
    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        requests += request
        (rounds.removeFirstOrNull() ?: listOf(ChatEvent.Finished(FinishReason.Stop)))
            .forEach { emit(it) }
    }
}

class AgentLoopTest {

    private var counter = 0
    private val idGen = { "id-${counter++}" }

    private fun loop(provider: LlmProvider, registry: ToolRegistry, maxIterations: Int = 15) =
        AgentLoop(provider = provider, registry = registry, agentsMd = "",
            model = "test/model", idGen = idGen, maxIterations = maxIterations)

    @Test
    fun `a plain text answer streams and finishes with Stop`() = runTest {
        val provider = ScriptedProvider(ArrayDeque(listOf(
            listOf(ChatEvent.TextDelta("Hello "), ChatEvent.TextDelta("world"),
                ChatEvent.Finished(FinishReason.Stop)),
        )))
        val registry = ToolRegistry(providers = emptyList(), context = stubCtx(), idGen = idGen)
        val events = loop(provider, registry).run(userHistory("hi")).toList()

        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "Hello " })
        assertEquals(AgentFinish.Stop, (events.last() as AgentEvent.Finished).reason)
        // System prompt is prepended on the request, never in the returned events.
        assertTrue(provider.requests.first().messages.first() is ChatMessage.System)
    }

    @Test
    fun `a tool call round dispatches then re-requests and finishes`() = runTest {
        val provider = ScriptedProvider(ArrayDeque(listOf(
            // round 1: model asks for a search
            listOf(
                ChatEvent.ToolCallDelta(0, "call_a", "native__web_search", """{"query":"kotlin"}"""),
                ChatEvent.Finished(FinishReason.ToolCalls),
            ),
            // round 2: model answers
            listOf(ChatEvent.TextDelta("Per the results…"), ChatEvent.Finished(FinishReason.Stop)),
        )))
        val registry = ToolRegistry(providers = listOf(NativeToolProviderStub()), context = stubCtx(), idGen = idGen)
        val events = loop(provider, registry).run(userHistory("search kotlin")).toList()

        // committed assistant with the tool call, then a tool reply, then a final answer
        assertTrue(events.any { it is AgentEvent.AssistantCommitted && it.message.toolCalls.isNotEmpty() })
        val toolFinished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("call_a", toolFinished.reply.toolCallId)
        assertEquals(AgentFinish.Stop, (events.last() as AgentEvent.Finished).reason)
        // second request carries the assistant tool_call + the tool reply
        val secondReq = provider.requests[1].messages
        assertTrue(secondReq.any { it is ChatMessage.Tool && it.toolCallId == "call_a" })
    }

    @Test
    fun `the iteration cap stops a runaway tool loop`() = runTest {
        // every round asks for another tool call → would loop forever without the cap
        val rounds = ArrayDeque((0 until 50).map {
            listOf(
                ChatEvent.ToolCallDelta(0, "call_$it", "native__web_search", """{"query":"x"}"""),
                ChatEvent.Finished(FinishReason.ToolCalls),
            )
        })
        val provider = ScriptedProvider(rounds)
        val registry = ToolRegistry(providers = listOf(NativeToolProviderStub()), context = stubCtx(), idGen = idGen)
        val events = loop(provider, registry, maxIterations = 3).run(userHistory("go")).toList()

        assertEquals(AgentFinish.IterationCapReached, (events.last() as AgentEvent.Finished).reason)
        assertEquals(3, provider.requests.size) // capped
    }

    @Test
    fun `a provider error surfaces as Failed`() = runTest {
        val provider = ScriptedProvider(ArrayDeque(listOf(
            listOf(ChatEvent.Failed(io.orangerabbit.clanker.core.network.ApiError(500, null, "boom", true))),
        )))
        val registry = ToolRegistry(providers = emptyList(), context = stubCtx(), idGen = idGen)
        val events = loop(provider, registry).run(userHistory("hi")).toList()
        assertTrue(events.last() is AgentEvent.Failed)
    }

    private fun userHistory(text: String) =
        listOf(ChatMessage.User(MessageId("u0"), text))
}
```

> This test references two tiny test doubles that must exist on the `:core:tools` test/main classpath: `NativeToolProviderStub` (a provider whose `native__web_search` tool returns a canned non-error `ToolResult` without touching Exa) and `stubCtx()`/a stub `ToolContext`. Add them as a small `commonMain` test-support file in `:core:tools` OR inline them in this test file. **Recommended:** inline them in `AgentLoopTest.kt` to avoid shipping stubs in `:core:tools` main. Replace the imports with local definitions:
> ```kotlin
> private fun stubCtx() = object : io.orangerabbit.clanker.core.tools.ToolContext {
>     override val exaClient get() = error("unused in this test")
> }
> private class NativeToolProviderStub : io.orangerabbit.clanker.core.tools.ToolProvider {
>     override suspend fun tools() = listOf(object : io.orangerabbit.clanker.core.tools.Tool {
>         override val name = "native__web_search"
>         override val description = "stub"; override val parameters = kotlinx.serialization.json.buildJsonObject {}
>         override val source = io.orangerabbit.clanker.core.tools.ToolSource.Native
>         override val execution = io.orangerabbit.clanker.core.tools.ToolExecution.ClientExecuted
>         override val riskLevel = io.orangerabbit.clanker.core.tools.RiskLevel.ReadOnly
>         override val timeoutMs = 1_000L
>         override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: io.orangerabbit.clanker.core.tools.ToolContext) =
>             io.orangerabbit.clanker.core.tools.ToolResult("stub results", isError = false)
>     })
> }
> ```
> and drop the two `import …tools.NativeToolProviderStub`/`ToolRegistry`-adjacent stub imports.

- [ ] **Step 2: Run to verify fail**

Run: `nix develop --command ./gradlew :core:agent:jvmTest --tests '*AgentLoopTest'`
Expected: FAIL — `AgentLoop` / `AgentEvent` / `AgentFinish` unresolved.

- [ ] **Step 3: Implement AgentLoop**

```kotlin
package io.orangerabbit.clanker.core.agent

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.MsgLifecycle
import io.orangerabbit.clanker.core.model.ToolCall
import io.orangerabbit.clanker.core.network.ApiError
import io.orangerabbit.clanker.core.network.ChatEvent
import io.orangerabbit.clanker.core.network.ChatRequest
import io.orangerabbit.clanker.core.network.FinishReason
import io.orangerabbit.clanker.core.network.LlmProvider
import io.orangerabbit.clanker.core.network.Usage
import io.orangerabbit.clanker.core.tools.DispatchOutcome
import io.orangerabbit.clanker.core.tools.ToolDisplay
import io.orangerabbit.clanker.core.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Why the loop ended. Errors surface via [AgentEvent.Failed], not here. */
enum class AgentFinish { Stop, IterationCapReached }

/** UI-facing events the ViewModel folds into its transcript. See plan's reconciliation note. */
sealed interface AgentEvent {
    data class AssistantTurnStarted(val id: MessageId) : AgentEvent
    data class TextDelta(val id: MessageId, val text: String) : AgentEvent
    data class AssistantCommitted(val message: ChatMessage.Assistant) : AgentEvent
    data class ToolFinished(val reply: ChatMessage.Tool, val display: ToolDisplay?) : AgentEvent
    data class UsageReport(val usage: Usage) : AgentEvent
    data class Finished(val reason: AgentFinish) : AgentEvent
    data class Failed(val error: ApiError) : AgentEvent
}

/**
 * The thin-but-real §6 agent loop. Streams from [provider], accumulates tool-call deltas by index,
 * dispatches them **serially** through [registry], appends the assistant turn verbatim and one
 * `role:tool` reply per call, and re-requests until the model stops — bounded by [maxIterations].
 * Read-only tools only in this increment (no approval gate, no concurrency, no degenerate-loop
 * guard beyond the cap). The returned flow is cold; collecting it starts the turn and cancelling
 * the collector cancels the turn (the VM reconciles any orphaned tool ids via
 * [reconcileAbortedToolCalls]).
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val registry: ToolRegistry,
    private val agentsMd: String,
    private val model: String,
    private val idGen: () -> String,
    private val maxIterations: Int = 15,
) {
    fun run(history: List<ChatMessage>): Flow<AgentEvent> = flow {
        val system = ChatMessage.System(MessageId(idGen()), composeSystemPrompt(agentsMd))
        val working = mutableListOf<ChatMessage>().apply { addAll(history) }
        val toolSpecs = registry.toolSpecs()

        var iteration = 0
        while (true) {
            iteration++
            val assistantId = MessageId(idGen())
            emit(AgentEvent.AssistantTurnStarted(assistantId))

            val request = ChatRequest(
                model = model,
                messages = listOf(system) + working,
                tools = toolSpecs,
            )

            val buffer = StringBuilder()
            val byIndex = sortedMapOf<Int, ToolCallAccum>()
            var finish: FinishReason? = null
            var failed: ApiError? = null

            provider.streamChat(request).collect { event ->
                when (event) {
                    is ChatEvent.TextDelta -> {
                        buffer.append(event.text)
                        emit(AgentEvent.TextDelta(assistantId, event.text))
                    }
                    is ChatEvent.ToolCallDelta -> {
                        val acc = byIndex.getOrPut(event.index) { ToolCallAccum() }
                        event.id?.let { acc.id = it }
                        event.name?.let { acc.name = it }
                        event.argsFragment?.let { acc.args.append(it) }
                    }
                    is ChatEvent.UsageReport -> emit(AgentEvent.UsageReport(event.usage))
                    is ChatEvent.Finished -> finish = event.reason
                    is ChatEvent.Failed -> failed = event.error
                    else -> Unit // ReasoningDelta / ImageDelta: not handled in this increment
                }
            }

            if (failed != null) { emit(AgentEvent.Failed(failed!!)); return@flow }

            val toolCalls = byIndex.values
                .filter { it.id != null && it.name != null }
                .map { ToolCall(it.id!!, it.name!!, it.args.toString().ifBlank { "{}" }) }

            val assistant = ChatMessage.Assistant(
                id = assistantId,
                content = buffer.toString().ifBlank { null },
                toolCalls = toolCalls,
                lifecycle = MsgLifecycle.Complete,
            )
            working += assistant
            emit(AgentEvent.AssistantCommitted(assistant))

            if (finish == FinishReason.ToolCalls && toolCalls.isNotEmpty()) {
                for (call in toolCalls) { // SERIAL — read-only concurrency is deferred
                    val outcome: DispatchOutcome = registry.dispatch(call)
                    working += outcome.reply
                    emit(AgentEvent.ToolFinished(outcome.reply, outcome.display))
                }
                if (iteration >= maxIterations) {
                    emit(AgentEvent.Finished(AgentFinish.IterationCapReached)); return@flow
                }
                // loop again
            } else {
                emit(AgentEvent.Finished(AgentFinish.Stop)); return@flow
            }
        }
    }

    private class ToolCallAccum {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `nix develop --command ./gradlew :core:agent:jvmTest --tests '*AgentLoopTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full agent suite**

Run: `nix develop --command ./gradlew :core:agent:jvmTest`
Expected: PASS (SystemPrompt + AbortReconciliation + AgentLoop).

- [ ] **Step 6: Commit**

```bash
git add core/agent/src
git commit -m "feat(agent): AgentLoop — serial tool dispatch, iteration cap, cancellable"
```

### Task 10: Confirm `role:tool` wire round-trip (test only)

**Files:**
- Test: `core/network/src/commonTest/kotlin/io/orangerabbit/clanker/core/network/ToolMessageWireTest.kt` (create only if no equivalent exists)

- [ ] **Step 1: Check for existing coverage**

Run: `nix develop --command bash -c "grep -rl 'role.*tool\|ChatMessage.Tool' core/network/src/commonTest || echo none"`
If a test already asserts the `role:tool` encoding, skip this task. Otherwise continue.

- [ ] **Step 2: Write the test**

```kotlin
package io.orangerabbit.clanker.core.network

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.ToolCall
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolMessageWireTest {
    @Test
    fun `assistant tool_calls and a role tool reply both encode on the wire`() = runTest {
        var sentBody: String? = null
        val engine = MockEngine { req ->
            sentBody = (req.body as TextContent).text
            respond("{}", HttpStatusCode.OK)
        }
        val provider = OpenAiCompatibleProvider(
            id = io.orangerabbit.clanker.core.model.ProviderId("t"),
            displayName = "t", baseUrl = "https://x.example", apiKey = "k", engine = engine,
        )
        val history = listOf(
            ChatMessage.Assistant(MessageId("a"), content = null,
                toolCalls = listOf(ToolCall("call_1", "native__web_search", """{"query":"x"}"""))),
            ChatMessage.Tool(MessageId("t1"), toolCallId = "call_1", content = "results"),
        )
        runCatching { provider.chat(ChatRequest(model = "m", messages = history)) }
        assertTrue(sentBody!!.contains("\"role\":\"tool\""))
        assertTrue(sentBody!!.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(sentBody!!.contains("\"tool_calls\""))
    }
}
```

> If `chat()` requires a fuller response body to parse, the `runCatching` swallows the parse failure — we only assert on the *outbound* body, which is captured before the response is read.

- [ ] **Step 3: Run**

Run: `nix develop --command ./gradlew :core:network:jvmTest --tests '*ToolMessageWireTest'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add core/network/src
git commit -m "test(network): assert role:tool + assistant tool_calls wire encoding"
```

---

## Chunk 4: `:app` wiring — Exa key, drive the loop, tool cards

### Task 11: Exa key in SecretStore

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/data/SecretStore.kt`

- [ ] **Step 1: Add Exa key storage** (mirror `saveApiKey`/`loadApiKey`)

Add a key and two methods to `SecretStore`:

```kotlin
    suspend fun saveExaKey(key: String) {
        val ciphertext = Base64.encodeToString(aead.encrypt(key.encodeToByteArray(), EXA_AAD), Base64.NO_WRAP)
        context.secretDataStore.edit { it[EXA_KEY] = ciphertext }
    }

    suspend fun loadExaKey(): String? {
        val ciphertext = context.secretDataStore.data.first()[EXA_KEY] ?: return null
        return runCatching {
            aead.decrypt(Base64.decode(ciphertext, Base64.NO_WRAP), EXA_AAD).decodeToString()
        }.getOrNull()
    }
```

And in the companion object:

```kotlin
        val EXA_KEY = stringPreferencesKey("exa_api_key")
        val EXA_AAD = "clanker.exaKey".encodeToByteArray()
```

- [ ] **Step 2: Build to verify**

Run: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:compileDebugKotlin'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/data/SecretStore.kt
git commit -m "feat(app): encrypted Exa API key storage in SecretStore"
```

### Task 12: Drive AgentLoop from ChatViewModel

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt`

The app module has no unit tests (device-verified per repo convention), so this task is implement-then-build-then-device-verify.

- [ ] **Step 1: Add Exa key state + loaders**

In `UiState`, add `val exaKey: String = ""` and `val toolEnabled: Boolean = false` (true once a key exists). In `init`, load it:

```kotlin
        viewModelScope.launch {
            secrets.loadExaKey()?.let { k -> _state.update { it.copy(exaKey = k, toolEnabled = k.isNotBlank()) } }
        }
```

Add a setter mirroring `setApiKey`:

```kotlin
    fun setExaKey(value: String) {
        val key = value.trim()
        _state.update { it.copy(exaKey = key, toolEnabled = key.isNotBlank()) }
        viewModelScope.launch { secrets.saveExaKey(key) }
    }
```

- [ ] **Step 2: Add a tool-display map to UiState for source chips**

Add `val toolDisplays: Map<String, io.orangerabbit.clanker.core.tools.ToolDisplay> = emptyMap()` keyed by `toolCallId`.

- [ ] **Step 3: Replace `send()`'s streaming body with the AgentLoop**

Replace the `streamJob = viewModelScope.launch { … }` block. Key changes:
- Build a `ToolRegistry` + `AgentLoop` when `toolEnabled`, else an empty registry (no tools advertised).
- Collect `Flow<AgentEvent>` instead of `Flow<ChatEvent>`.
- Maintain the transcript by folding events; keep the ~50ms throttle for `TextDelta`.

```kotlin
        streamJob = viewModelScope.launch {
            val provider = openRouterProvider(apiKey = current.apiKey, engine = engine)
            val idGen = { newId() }
            val registry = if (current.toolEnabled) {
                val exa = io.orangerabbit.clanker.core.tools.exa.HttpExaClient(
                    apiKey = current.exaKey, engine = engine)
                val ctx = object : io.orangerabbit.clanker.core.tools.ToolContext {
                    override val exaClient = exa
                }
                io.orangerabbit.clanker.core.tools.ToolRegistry(
                    providers = listOf(io.orangerabbit.clanker.core.tools.exa.NativeToolProvider()),
                    context = ctx, idGen = idGen)
            } else {
                io.orangerabbit.clanker.core.tools.ToolRegistry(
                    providers = emptyList(),
                    context = object : io.orangerabbit.clanker.core.tools.ToolContext {
                        override val exaClient get() = error("no tools")
                    }, idGen = idGen)
            }
            val loop = io.orangerabbit.clanker.core.agent.AgentLoop(
                provider = provider, registry = registry, agentsMd = current.agentsMd,
                model = if (current.imageMode) current.defaultImageModel else current.defaultChatModel,
                idGen = idGen)

            // Remove the optimistic empty assistant bubble we added above; the loop emits its own.
            _state.update { it.copy(messages = it.messages.dropLast(1)) }

            val buffer = StringBuilder()
            var streamingId: MessageId? = null
            var lastUiUpdate = 0L
            try {
                loop.run(history).collect { ev ->
                    when (ev) {
                        is io.orangerabbit.clanker.core.agent.AgentEvent.AssistantTurnStarted -> {
                            buffer.clear(); streamingId = ev.id
                            _state.update { it.copy(messages = it.messages +
                                ChatMessage.Assistant(ev.id, content = "", lifecycle = MsgLifecycle.Streaming)) }
                        }
                        is io.orangerabbit.clanker.core.agent.AgentEvent.TextDelta -> {
                            buffer.append(ev.text)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= UI_THROTTLE_MS) {
                                lastUiUpdate = now
                                updateAssistant(ev.id, buffer.toString(), emptyList(), MsgLifecycle.Streaming)
                            }
                        }
                        is io.orangerabbit.clanker.core.agent.AgentEvent.AssistantCommitted -> {
                            // Flush final text + attach any tool calls to the committed bubble.
                            _state.update { st -> st.copy(messages = st.messages.map {
                                if (it is ChatMessage.Assistant && it.id == ev.message.id) ev.message else it }) }
                        }
                        is io.orangerabbit.clanker.core.agent.AgentEvent.ToolFinished -> {
                            _state.update { st -> st.copy(
                                messages = st.messages + ev.reply,
                                toolDisplays = ev.display?.let { st.toolDisplays + (ev.reply.toolCallId to it) }
                                    ?: st.toolDisplays) }
                        }
                        is io.orangerabbit.clanker.core.agent.AgentEvent.UsageReport ->
                            ev.usage.costUsd?.let { c ->
                                _state.update { it.copy(costUsd = it.costUsd + c) }
                                settingsStore.addLifetimeCost(c)
                            }
                        is io.orangerabbit.clanker.core.agent.AgentEvent.Finished -> Unit
                        is io.orangerabbit.clanker.core.agent.AgentEvent.Failed ->
                            _state.update { it.copy(error = ev.error.message) }
                    }
                }
            } catch (e: CancellationException) {
                // Stop pressed: close any orphaned tool_call_ids so a resend stays valid.
                _state.update { st -> st.copy(messages =
                    io.orangerabbit.clanker.core.agent.reconcileAbortedToolCalls(st.messages, idGen)) }
                streamingId?.let { updateAssistant(it, buffer.toString(), emptyList(), MsgLifecycle.Aborted) }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                _state.update { it.copy(streaming = false) }
            }
        }
```

> Note: the existing `send()` adds an optimistic streaming `Assistant` bubble before launching; the loop now owns bubble creation via `AssistantTurnStarted`, so the snippet drops that last optimistic message first. Keep the `history` value (user message appended) as-is. Keep image mode working: `modalities` is not yet threaded through `AgentLoop` — for this increment, image mode and tools are mutually exclusive in practice (image models rarely tool-call); leave the image path on the existing direct-stream code path OR thread `modalities` into `AgentLoop.run`. **Recommended for minimal risk:** add a `modalities: List<String>` param to `AgentLoop` and pass it into `ChatRequest`; if `imageMode`, also pass `emptyList()` toolSpecs by using the empty registry. Document whichever you choose.

- [ ] **Step 4: Build**

Run: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt
git commit -m "feat(app): drive the agent loop with web tools from ChatViewModel"
```

### Task 13: Settings — masked Exa key field

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/SettingsScreen.kt`

- [ ] **Step 1: Add a masked Exa-key field**

Following the existing OpenRouter-key field (masked `OutlinedTextField` with `PasswordVisualTransformation`), add a parallel field bound to `state.exaKey` / `onSetExaKey`. Add an explanatory caption: "Enables web search & fetch tools. Get a key at exa.ai." Wire the `onSetExaKey` callback through to `viewModel.setExaKey`.

- [ ] **Step 2: Build**

Run: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/ui/SettingsScreen.kt
git commit -m "feat(app): Settings field for the Exa API key"
```

### Task 14: Chat transcript — collapsed tool cards + source chips

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt`

- [ ] **Step 1: Render tool calls and results as cards**

In the transcript `LazyColumn`, handle the message roles that previously weren't shown:
- `ChatMessage.Assistant` with non-empty `toolCalls` → a `ThemedCard` per call showing `🔍 native__web_search · <query from argumentsJson>` / `🌐 native__web_fetch · <url>`, collapsed by default, expandable to show args.
- `ChatMessage.Tool` → a result card under its call, collapsed, showing the returned content (and `isError` styling when set).
- Under the final assistant answer, render `state.toolDisplays` sources for that turn as tappable chips (open URL via an `Intent.ACTION_VIEW`).

Keep `key = { it.id.value }` stable content keys and a `contentType` per role (existing pattern). Use existing design-system components (`ThemedCard`, `ThemedSectionHeader`). Parse the query/url for the card label from `ToolCall.argumentsJson` with a tolerant `Json` parse (fall back to the raw name on failure).

- [ ] **Step 2: Build**

Run: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt
git commit -m "feat(app): collapsed tool-call cards + source chips in the transcript"
```

### Task 15: Make SystemPrompt capability-honest

**Files:**
- Modify: `core/agent/src/commonMain/kotlin/io/orangerabbit/clanker/core/agent/SystemPrompt.kt`
- Modify: `core/agent/src/commonTest/.../SystemPromptTest.kt` (adjust any assertion that pins the "no tools" wording)

- [ ] **Step 1: Update the prompt text**

Replace the "You currently have no tools…" paragraph with capability-honest text that does NOT hard-claim tools are always present (they depend on the Exa key), e.g.:

```
When web tools are available you can search the web (native__web_search) and fetch pages
(native__web_fetch). Use them for current or unfamiliar facts, and cite the source URLs you
relied on. If a tool returns an error, tell the user plainly rather than inventing an answer.
You take no other actions on the user's systems.
```

- [ ] **Step 2: Fix the test**

If `SystemPromptTest` asserts the old "no tools" sentence, update it to assert the new tool-mention wording (and that `composeSystemPrompt("")` still returns `SystemPrompt.TEXT` unchanged).

- [ ] **Step 3: Run**

Run: `nix develop --command ./gradlew :core:agent:jvmTest --tests '*SystemPromptTest'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add core/agent/src
git commit -m "feat(agent): system prompt describes the web tools honestly"
```

### Task 16: Full build + device verification

- [ ] **Step 1: Full test sweep**

Run: `nix develop --command ./gradlew :core:tools:jvmTest :core:agent:jvmTest :core:network:jvmTest`
Expected: PASS across all three modules.

- [ ] **Step 2: Assemble the app**

Run: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Device smoke test** (per repo convention; needs a connected device + a valid OpenRouter key and Exa key re-entered)

Install and drive via adb (the flake ships platform-tools):
```bash
nix develop --command adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then: open Settings, enter the Exa key; in chat ask something that needs current info (e.g. "search the web for the latest stable Kotlin release and cite it"). Verify: a `🔍 web_search` card appears, a tool-result card follows, the assistant answers, and source chips render and open in a browser. Remember the ~2s sleep-after-tap quirk when scripting `adb shell input`.

- [ ] **Step 4: Update the progress memory**

Append to `/home/alindsay/.claude/projects/-home-alindsay-projects-orangerabbit-io-android-clanker/memory/clanker-mvp-progress.md`: web search/fetch tools + thin agent loop landed (`:core:tools`, `AgentLoop`), what's tested, and the remaining v1 items (concurrency, approval gate, SSH, inspector). Update the MEMORY.md one-liner.

- [ ] **Step 5: Final commit (if any uncommitted verification tweaks)**

```bash
git add -A && git commit -m "chore: verify web-tools agent loop end-to-end"
```

---

## Done criteria

- `:core:tools` and `:core:agent` test suites green (registry invariant, Exa client, tool clamping, multi-iteration loop, iteration cap, abort reconciliation).
- App assembles; on device, a web-search question produces tool cards, a cited answer, and working source chips.
- No orphaned `tool_call_id` is ever resent (covered by `reconcileAbortedToolCalls` tests + the loop's one-reply-per-call dispatch).
- Deferred items remain deferred, with interfaces (`RiskLevel`, `ToolExecution`, `ToolSource`) in place for SSH/MCP/approval to extend without rework.
