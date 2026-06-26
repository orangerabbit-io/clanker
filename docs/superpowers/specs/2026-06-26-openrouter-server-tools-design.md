# OpenRouter server tools (web search, fetch, datetime, image gen) — design

**Date:** 2026-06-26
**Status:** Approved (ready for implementation planning)
**Related:** `DESIGN.md` §8 (tool system — this realizes the `ProviderExecuted` tier named there). **Supersedes** `2026-06-24-web-search-client-tools-design.md` and its plan `2026-06-24-web-search-client-tools.md` (the client-executed Exa path is shelved; see §11).

## 1. Goal

Give clanker web search, web fetch, current date/time, and image generation by enabling **OpenRouter server tools** — model-callable tools that OpenRouter executes server-side. The model decides when to call them; OpenRouter runs the entire tool loop within a single streamed completion and returns the final answer plus `url_citation` annotations. The client executes nothing and synthesizes no `role:tool` replies.

This is path **A** from `DESIGN.md` §8 (`ProviderExecuted` server tools), chosen over the previously-specced client-executed Exa path because clanker is **OpenRouter-only**, which removes the portability rationale that justified the client loop. The client agent loop (`AgentLoop`) is deferred until SSH/MCP genuinely require it.

## 2. Scope

### In scope
Four server tools, all on the Chat Completions API:

| Tool | Wire `type` | Client-visible output |
|---|---|---|
| Web search | `openrouter:web_search` | `url_citation` annotations |
| Web fetch | `openrouter:web_fetch` | `url_citation` annotations |
| Datetime | `openrouter:datetime` | none (model-only) |
| Image generation | `openrouter:image_generation` | generated image (`imageUrl`) |

- `:core:network`: a typed `ServerTool` request section serialized into the wire `tools` array; decoder support for `url_citation` annotations, `server_tool_use` usage, and image-generation output.
- `:core:model`: a `Citation` domain type carried on the assistant message.
- `:app`: populate server tools "always-on for capable models"; render source chips and generated images.

### Out of scope (deliberately)
- **`apply_patch`** — Responses API only; clanker uses Chat Completions. Not enableable.
- **`fusion` / `advisor` / `subagent`** — quality/meta tools that multiply cost by running extra models with no new client output. Excluded for now; trivial to add later (one `ServerTool` variant each, no decoder change).
- **`AgentLoop`** — server tools loop server-side, so no client loop is built. (SSH/MCP will introduce it.)
- **Per-conversation / global toggles** — enablement is capability-gated and automatic (§7).
- **The client-executed Exa path** — shelved (§11).

## 3. Current state it builds on (verified 2026-06-26)

- `ChatRequest` (`LlmProvider.kt:26`) has `tools: List<ToolSpec>` (function-shaped, currently unused) — left intact for future SSH/MCP client tools; server tools get their own field.
- The wire builder `ChatRequest.toWire` (`OpenAiCompatibleProvider.kt:168`) already constructs the `tools` array from function `ToolSpec`s; server tools are appended to the same array.
- `OpenAiSseDecoder` already emits `ChatEvent.TextDelta`, `ImageDelta`, `ToolCallDelta`, `UsageReport`, `Finished`, `Failed`. No annotation or `server_tool_use` parsing yet.
- `ChatEvent.ImageDelta(dataUrl)` and the modalities-based image-output path already exist (`LlmProvider.kt:93`) — image-generation server-tool output reuses this channel.
- `Usage` (`LlmProvider.kt:65`) has no server-tool fields.
- `:core:agent` contains only `SystemPrompt` + `composeSystemPrompt` — no loop. Single-shot `streamChat` is the current and continuing execution model.
- `Capability.ToolCalling` is derived from `/models` `supported_parameters` containing `"tools"` (`OpenAiCompatibleProvider.kt:252`) — the gating signal in §7.

## 4. `:core:network` — request side

New public types (no OpenAI/OpenRouter DTO escapes the boundary):

```kotlin
sealed interface ServerTool {
    data class WebSearch(val maxResults: Int? = null, val engine: String = "auto") : ServerTool
    data object WebFetch : ServerTool
    data object Datetime : ServerTool
    data class ImageGeneration(
        val model: String? = null,
        val size: String? = null,
        val quality: String? = null,
    ) : ServerTool
}
```

`ChatRequest` gains `val serverTools: List<ServerTool> = emptyList()`.

Wire serialization (in `toWire`): each `ServerTool` becomes a `WireServerTool(type = "openrouter:…", parameters = {…})` and is **appended to the same `tools` array** as any function tools. `parameters` is omitted when empty (consistent with `encodeDefaults = false`). `WebSearch.engine` defaults to `"auto"` so OpenRouter falls back to Exa for models without native search — forcing `engine = "native"` is the only documented 400 path and is never sent by default.

Wire shape produced:
```json
"tools": [
  { "type": "openrouter:web_search", "parameters": { "engine": "auto", "max_results": 5 } },
  { "type": "openrouter:web_fetch" },
  { "type": "openrouter:datetime" },
  { "type": "openrouter:image_generation" }
]
```

## 5. `:core:network` — response side

`OpenAiSseDecoder` gains three additions; all are additive and must not disturb existing event emission:

1. **`url_citation` annotations.** Parse `choices[].delta.annotations[]` (and, defensively, `choices[].message.annotations[]` for the final non-delta form). Each `type:"url_citation"` annotation has `url`, `title`, `content`, `start_index`, `end_index`. Emit a new `ChatEvent.Citation(citation: Citation)` per annotation. Duplicate annotations across deltas are de-duplicated downstream by the consumer keying on `url` + `start_index` (the decoder stays stateless and emits what it sees).
2. **`server_tool_use` usage.** Extend `Usage` with `webSearchRequests: Int? = null`, parsed from `usage.server_tool_use.web_search_requests`.
3. **Image output.** Image-generation server-tool output (`imageUrl`, or an inline `data:`/image content part — pinned during TDD/live, §10) is mapped to the existing `ChatEvent.ImageDelta(dataUrl)`. No new event type.

New event:
```kotlin
data class Citation(val citation: io.orangerabbit.clanker.core.model.Citation) : ChatEvent
```

The exact streaming placement of annotations (incremental per-delta vs. only on the final chunk) is provider-dependent; the decoder parses both `delta.annotations` and `message.annotations` so either shape is handled (§10).

## 6. `:core:model`

```kotlin
data class Citation(
    val url: String,
    val title: String? = null,
    val content: String? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)
```

`ChatMessage.Assistant` gains `val citations: List<Citation> = emptyList()`. The consumer (ViewModel) accumulates `ChatEvent.Citation`s for a turn, de-duplicates, and attaches them to the finalized assistant message — mirroring how `toolCalls` are accumulated.

## 7. `:app` — enablement & rendering

**Enablement ("always-on for capable models").** When building a `ChatRequest`, set `serverTools = [WebSearch(), WebFetch, Datetime, ImageGeneration()]` **iff** the selected model has `Capability.ToolCalling`; otherwise `emptyList()`. No UI toggle.

Rationale for gating on `ToolCalling`: there is no per-model server-tool flag in `/models`. OpenRouter *can* run web search on some non-tool models via orchestration, but `ToolCalling` is the available, conservative signal that avoids 400s on models that reject a `tools` array outright. Revisable later (e.g. always-send + tolerate failure) without interface change.

**Rendering.**
- `url_citation`s render as tappable **source chips** beneath the assistant bubble (existing cyberpunk design-system components), de-duplicated by `url`.
- Generated images render via the existing image-output path (`ImageDelta`).
- A transient "searching…" affordance is acceptable but optional; the persistent record is the answer plus its source chips.

## 8. Security & secrets

- **No new secret.** Server tools bill through the existing OpenRouter key (BYOK in `SecretStore`); no Exa key is introduced (the Exa BYOK in the shelved plan is dropped).
- **HTTPS only** — unchanged; OpenRouter base URL is already HTTPS-enforced.
- **No client-side execution** means no SSRF surface on the handset: every tool runs on OpenRouter.
- **Cost visibility:** `server_tool_use.web_search_requests` and the existing `cost` field surface tool spend in the usage report.

## 9. Error handling

| Failure | Behaviour |
|---|---|
| Model lacks `ToolCalling` | server tools omitted from the request; normal completion |
| Model/provider rejects a server tool (e.g. 400) | surfaced via the existing `ChatEvent.Failed` path; no special-casing in this increment |
| `engine:"native"` unsupported | never sent — default is `"auto"`, which falls back to Exa |
| Annotation/usage fields absent | decoder emits nothing extra; existing events unaffected |
| Image output shape unrecognized | logged/ignored; text answer still renders (hardened during §10) |

There is no `tool_call_id` reply invariant to maintain — the server owns the loop, so no orphaned-call class of error exists for these tools.

## 10. Testing (TDD)

All logic test-first on the KMP `jvm()` target, matching existing conventions.
- **Wire (`OpenAiCompatibleProviderTest`):** each `ServerTool` serializes to the correct `{type, parameters}` and is appended to the `tools` array; `engine` defaults to `"auto"`; empty `parameters` omitted; server tools coexist with function `ToolSpec`s in one array.
- **Decoder (`OpenAiSseDecoderTest`):** `url_citation` annotations parsed from both `delta.annotations` and `message.annotations` into `ChatEvent.Citation`; `server_tool_use.web_search_requests` parsed into `Usage`; image-generation output mapped to `ImageDelta`; absence of all three leaves existing events unchanged.
- **Live (`LiveOpenRouterTest`):** add a `web_search` round-trip (self-skipping without `OPENROUTER_API_KEY`) that asserts at least one `url_citation` comes back; use this to pin the exact streaming annotation shape and the image-output shape (§5).

## 11. Migration: shelving the Exa client-tools spec

`2026-06-24-web-search-client-tools-design.md` and `2026-06-24-web-search-client-tools.md` are superseded by this spec. They are **not deleted** — each gets a `**Status: Superseded by 2026-06-26-openrouter-server-tools-design.md**` banner at the top, preserving the client-executed `AgentLoop`/`ExaClient` design for whenever SSH/MCP revive the client loop. `DESIGN.md` §8 already names both tiers; no §8 edit is required beyond a note that the `ProviderExecuted` tier is now the implemented default and the Exa fallback is deferred.

## 12. Data flow (summary)

```
user msg
  → build ChatRequest(messages + serverTools=[web_search, web_fetch, datetime, image_generation] if model.ToolCalling)
  → provider.streamChat
    → OpenRouter runs any tool calls server-side (search/fetch/datetime/image)
    → stream: TextDelta… + Citation(url_citation)… + ImageDelta(generated) + UsageReport(server_tool_use) + Finished(Stop)
  → ViewModel: accumulate citations, attach to Assistant message
  → UI: assistant bubble + source chips + generated image
  (single streamChat round-trip; no client loop; no role:tool replies)
```
