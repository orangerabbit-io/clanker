# Web search & fetch via client-executed tools — design

**Date:** 2026-06-24
**Status:** Approved (ready for implementation planning)
**Related:** `DESIGN.md` §6 (agent loop), §8 (tool system), §11 (chat UX). This spec builds the first concrete slice of both §6 and §8.

## 1. Goal

Give clanker its first tool use: the model can **search the web** and **read web pages**, then answer from the results with citations. Two client-executed tools (Exa-backed) are driven by a thin-but-real agent loop. All tools in this increment are **read-only and auto-run** — no approval gate.

This is path **B** from the scope discussion (client-side agent loop + client-executed tools), deliberately chosen over OpenRouter's server-executed `openrouter:web_search`/`web_fetch` because the client loop is the reusable foundation for SSH and MCP later, and is portable across providers. We build the *thin-but-real* slice: the real §6 interfaces, but only the parts read-only tools exercise.

## 2. Scope

### In scope
- New `:core:tools` KMP module: `Tool` / `ToolProvider` / `ToolRegistry` / `ToolResult`, plus an `ExaClient` and the two tools (`ExaSearchTool`, `WebFetchTool`).
- New `AgentLoop` in `:core:agent`: stream → accumulate tool_calls → dispatch → re-request until `Finished(Stop)`, with an iteration cap and clean cancellation.
- `:app`: drive the loop from `ChatViewModel`; Exa key in the existing Tink `SecretStore` + a Settings field; collapsed tool-call transcript cards + source chips.

`:core:network` needs **no production change** — `ChatMessage.Tool` already wire-encodes (see §3 / §6); this increment only adds a round-trip test if one is absent.

### Out of scope (deferred; interfaces accommodate them without rework)
- RiskLevel-keyed concurrency (this slice dispatches **serially**).
- Persisted approval gate / `PendingApproval` (read-only tools auto-run).
- Degenerate-loop detection (only the iteration cap guards termination now).
- ProviderExecuted (`openrouter:*`) tools.
- Request/response inspector (§11).
- Room persistence of the tool transcript — Room is not built yet, so tool messages live in the same in-memory transcript as the rest of chat and vanish on restart, consistent with current behaviour.

## 3. Current state it builds on (verified 2026-06-24)

The network layer is already tool-ready, so this increment touches it only lightly:
- `ChatRequest.tools: List<ToolSpec>` exists and encodes to the wire (`WireTool`).
- `OpenAiSseDecoder` emits `ChatEvent.ToolCallDelta` keyed by `index` and `ChatEvent.Finished(FinishReason.ToolCalls)`.
- Capability discovery sets `Capability.ToolCalling` from `supported_parameters`.
- `:core:model` already has `ChatMessage.Assistant.toolCalls`, `ChatMessage.Tool(toolCallId, content, isError)`, and `ToolCall(id, name, argumentsJson)`.
- `:core:agent` currently contains only `SystemPrompt.TEXT` + `composeSystemPrompt(agentsMd)`.

## 4. Module: `:core:tools` (new, KMP, UI-free)

Implements the §8 abstraction, scoped to read-only client execution.

### 4.1 Interfaces and types

```kotlin
interface Tool {
  val name: String                 // "native__web_search", "native__web_fetch"
  val description: String
  val parameters: JsonObject       // JSON Schema for the args object
  val source: ToolSource           // Native here
  val execution: ToolExecution     // ClientExecuted here
  val riskLevel: RiskLevel         // ReadOnly here
  val timeoutMs: Long
  suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

interface ToolProvider { suspend fun tools(): List<Tool> }

// content: model-readable text fed back as the role:tool reply.
// display: optional structured data for the UI card (e.g. search results), so the
//          UI never parses content strings. Null for tools with nothing to show.
data class ToolResult(val content: String, val isError: Boolean, val display: ToolDisplay? = null)

// ctx carries the client-execution dependencies tools need at call time.
// This increment's only dependency is the Exa client.
interface ToolContext { val exaClient: ExaClient }
```

`ToolSource`, `ToolExecution`, `RiskLevel` are defined in full per §8 (`Native | Ssh | Mcp`, `ClientExecuted | ProviderExecuted`, `ReadOnly | Mutating | Destructive`) so later increments add variants without redefining the enums; only the `Native` / `ClientExecuted` / `ReadOnly` paths are wired now.

### 4.2 `ToolRegistry`

Aggregates `ToolProvider`s and owns the boundary between the loop and individual tools.
- `suspend fun toolSpecs(): List<ToolSpec>` — produces the `ChatRequest.tools` array. Each tool's `parameters: JsonObject` is serialized into the existing `ToolSpec.parametersJsonSchema: String` here (`LlmProvider.kt:42`) — this serialization is the registry's responsibility and the one type-conversion at the registry→network boundary.
- `suspend fun dispatch(call: ToolCall): DispatchOutcome` — resolves the tool by `call.name`, parses/validates/clamps `call.argumentsJson` against the tool's schema, runs `execute()` under `withTimeout(tool.timeoutMs)`, and returns a `DispatchOutcome(reply: ChatMessage.Tool, display: ToolDisplay?)` keyed to `call.id`. The registry builds the outcome from the tool's `ToolResult` (`reply` from `content`/`isError`, `display` passed through). The loop appends `reply` to history and forwards `display` to the UI via `AgentEvent.ToolFinished` (§5).
- **Invariant (load-bearing):** every dispatched call yields exactly one `ChatMessage.Tool`, never null and never an exception that escapes. Unknown tool, schema-invalid args, timeout, or `execute()` failure all map to `ChatMessage.Tool(isError = true)` with a descriptive message (and `display = null`). This guarantees the §6 "every `tool_call_id` gets exactly one `role:tool` reply" rule.

### 4.3 `ExaClient` (Ktor)

Wraps two Exa endpoints; **Exa DTOs are isolated in this module and never escape** (same rule as OpenAI DTOs in `:core:network`).
- `POST https://api.exa.ai/search` — `{ query, numResults, contents: { highlights, text } }` → results with URL, title, and extractive highlights.
- `POST https://api.exa.ai/contents` — `{ urls: [url], text: true }` → extracted page text.
- Auth: `x-api-key` header from the Exa key (BYOK, §7). HTTPS only.
- Errors map to a typed failure the tools convert into `ToolResult(isError=true)`.

### 4.4 The two tools

| Tool | `name` | Args (JSON Schema) | Backend |
|---|---|---|---|
| `ExaSearchTool` | `native__web_search` | `{ query: string (required), numResults?: int }` — `numResults` clamped to 1–10 | `ExaClient.search` |
| `WebFetchTool` | `native__web_fetch` | `{ url: string (required) }` — must match `https?://` | `ExaClient.contents` |

Both are `Native` / `ClientExecuted` / `ReadOnly`. Model output is treated as hostile: args are validated and clamped **before** the Exa call. Because Exa fetches the page server-side, the handset never issues SSRF-style requests to model-chosen hosts.

`NativeToolProvider` returns these two tools; the registry aggregates it.

The search tool's `ToolResult.content` is a compact, model-readable rendering of results (index, title, URL, highlight) so the model can cite sources; `ToolResult.display` carries the structured `{title, url}` list, which the registry forwards into `AgentEvent.ToolFinished` (§5) for the source chips, so the UI never parses the content string.

## 5. `:core:agent` — `AgentLoop`

New unit alongside `SystemPrompt`. Provider-agnostic orchestrator returning a `Flow<AgentEvent>` the ViewModel collects.

`fun run(history: List<ChatMessage>): Flow<AgentEvent>`:

1. Build request: `composeSystemPrompt(agentsMd)` as the System message + `history` + `registry.toolSpecs()` as `tools`.
2. `provider.streamChat(request)`. Surface `TextDelta`. Accumulate `ToolCallDelta` by `index`; finalize/parse args only on `Finished(ToolCalls)`.
3. `Finished(Stop)` → emit `Finished`, terminate.
4. `Finished(ToolCalls)` → append the assistant message **verbatim** (content + accumulated `toolCalls`) to the working history; for each tool call, **serially**, call `registry.dispatch(call)`, append the returned `DispatchOutcome.reply` (`ChatMessage.Tool`) in order, and emit `ToolFinished(reply, display)`; then loop to step 1 with the extended history.
5. **Termination guards:** an **iteration cap** (default **15**, configurable) bounds total request rounds. On exceeding it, emit a user-visible `Finished` carrying a "tool loop limit reached" reason and stop. (Degenerate-loop detection is deferred.)

**Cancellation:** the flow is collected in a cancellable coroutine. On abort, collection stops; for any assistant turn whose emitted `tool_call_id`s were not all answered, the loop **closes each unanswered call with a synthetic `ChatMessage.Tool(isError = true, "aborted by user")`** (chosen over silent rollback so the transcript preserves what was attempted, matching the tool cards already shown). This guarantees no subsequent resend carries an orphaned `tool_call_id`; history remains valid. (DESIGN.md §6 sanctions either synthetic results or rollback; this increment picks synthetic results.)

`AgentEvent` (UI projection, distinct from the wire-level `ChatEvent`):

```kotlin
sealed interface AgentEvent {
  data class TextDelta(val text: String) : AgentEvent
  data class ToolCallStarted(val call: ToolCall) : AgentEvent
  data class ToolFinished(val reply: ChatMessage.Tool, val display: ToolDisplay) : AgentEvent
  data class Usage(val report: UsageReport) : AgentEvent
  data class Finished(val reason: AgentFinish) : AgentEvent   // Stop | IterationCapReached | Error
  data class Error(val error: ApiError) : AgentEvent
}
```

`ToolDisplay` carries the structured data the UI needs (e.g. the query and the list of `{title, url}` for search, the URL for fetch) without the UI parsing tool content strings.

## 6. `:core:network` — no production change

Verified 2026-06-24: `ChatMessage.Tool` **already** wire-encodes (`OpenAiCompatibleProvider.kt:190` → `WireMessage(role="tool", content=…, toolCallId=…)`), and the assistant-with-tool_calls direction encodes too (`:186`). Tool-call deltas and finish reasons already decode (§3). So this layer needs no production code in this increment — add only a `role:tool` round-trip encoder test if one is not already present.

## 7. Security & secrets

- **Exa key**: BYOK, stored as Tink-AEAD ciphertext in the existing `SecretStore` (Keystore-wrapped keyset, ciphertext in DataStore). A masked field in Settings, mirroring the OpenRouter key. In-memory at use; no plaintext persisted.
- **Argument hostility**: all tool args validated and clamped against the JSON Schema before execution (`numResults` 1–10; `url` must be `https?://`). This is defense-in-depth per §10, not a trust boundary.
- **HTTPS only** for Exa endpoints.
- Read-only risk level means auto-run; no biometric/approval gate is introduced here.

## 8. Chat UX (§11)

- Each tool call renders as a **collapsed card** in the transcript: `🔍 search · "<query>"` / `🌐 fetch · <host>`, expandable to show args and returned results/citations.
- The model's final synthesized answer renders as a normal assistant bubble; **source URLs** from search results render as tappable chips beneath it.
- A transient status indicator is acceptable while a tool runs, but the persistent record is the card.
- Built with the existing cyberpunk design-system components.

## 9. Error handling

| Failure | Behaviour |
|---|---|
| Unknown tool name | `ChatMessage.Tool(isError=true, "unknown tool")` fed back; model adapts |
| Schema-invalid / unclampable args | `ChatMessage.Tool(isError=true, <validation message>)` |
| Tool timeout (`withTimeout`) | `ChatMessage.Tool(isError=true, "timed out")` |
| Exa HTTP / network error | mapped, surfaced as `isError=true` result |
| Iteration cap reached | `AgentEvent.Finished(IterationCapReached)`, user-visible message |
| User abort mid-turn | collection cancelled; unanswered calls closed with synthetic "aborted by user" `role:tool` results (§5); history left valid (no orphan `tool_call_id`) |

In every tool-failure case the loop still produces exactly one `role:tool` reply per call, so the next request never 400s on an unanswered `tool_call_id`.

## 10. Testing (TDD)

All logic is test-first, on the KMP `jvm()` target, matching existing conventions.
- `ToolRegistry`: dispatch routing, the one-reply-per-id invariant across unknown-tool / bad-args / timeout / throwing-tool (fake `Tool`).
- `ExaSearchTool` / `WebFetchTool`: Ktor `MockEngine` for `/search` and `/contents`; assert request shape, arg clamping (`numResults` bounds, URL scheme rejection), and result rendering.
- `AgentLoop`: a fake `LlmProvider` that emits one or more tool-call rounds then `Stop`; assert multi-iteration progression, verbatim assistant append, ordered `role:tool` replies, iteration-cap stop, and the cancellation invariant (no orphan `tool_call_id`).
- `LiveExaTest`: jvmTest hitting the real Exa API, self-skipping without `EXA_API_KEY` (mirrors `LiveOpenRouterTest`).

## 11. Data flow (summary)

```
user msg
  → AgentLoop.run(history)
    → ChatRequest(system + history + tools=registry.toolSpecs())
    → provider.streamChat → TextDelta / ToolCallDelta(index) ...
    → Finished(ToolCalls)
      → append Assistant(verbatim) ; for each call: registry.dispatch → DispatchOutcome(reply, display)
      → re-request
    → Finished(Stop) → assistant answer + source chips
  (bounded by iteration cap; cancellable; all in-memory)
```
