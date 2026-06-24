# clanker — Architecture & Implementation Plan

A native Android agentic LLM harness. Kotlin + Compose, OpenAI-compatible provider layer (OpenRouter first), a two-layer agent definition (fixed system prompt + user `AGENTS.md`), and tool-calling spanning native tools, SSH targets, and (later) MCP.

## 1. Overview & Design Principles

clanker is an agentic chat client whose differentiation is two open seams: **providers** and **tools**. Everything else is conventional 2026 Android.

| Principle | Consequence |
|---|---|
| **The abstraction is the product.** | Two narrow interfaces — `LlmProvider` and `Tool` — own the value. No OpenAI-shaped DTO ever escapes the provider boundary. |
| **The harness owns the agent loop.** | Hand-rolled loop above the provider interface, not a framework (Koog is the documented fallback). Provider only emits/consumes `tool_calls`. |
| **The device is hostile; the model is hostile.** | Prompt injection is structural and unfixable (indirect via web results, SSH output fed back). No single model decision causes irreversible action. The *only* authoritative controls are server-side. On-device controls are defense-in-depth, never relied upon. |
| **No shell on the far side.** | The model never produces a command *string*. SSH tools are a fixed set of structured RPC verbs executed via argv vectors against a server-side wrapper that ignores free text. Client-side "parse the shell to decide safety" is intractable and is not a security boundary. |
| **Wire-faithful persistence, derived UI.** | Stored messages mirror the API wire shape exactly, including opaque structured reasoning blocks (signed/encrypted thinking must round-trip verbatim). UI state is a separate projection. |
| **Bounded everything.** | Every loop, every input, every context window, every spend has an explicit ceiling. Unbounded accumulation is a production failure, not an edge case. |
| **Android-only now, KMP-cheap later.** | `:core:*` modules stay free of Android UI deps so an iOS/desktop move stays inexpensive. |
| **Stable over shiny.** | Build on stable Material 3 / Navigation 3 1.1.x / Room 2.8.x; gate alpha (M3 Expressive, Room 3.0) off critical paths. |

## 2. Recommended Tech Stack

| Concern | Choice | Why |
|---|---|---|
| Language / compiler | Kotlin 2.3.x (K2), `org.jetbrains.kotlin.plugin.compose` pinned to Kotlin version (2.4.0 exists; 2.3.x is a deliberate conservative pin) | K2 stable since 2.0; Compose compiler now Kotlin-versioned, ending version-match drift |
| UI | Jetpack Compose (BOM 2026.06.xx) + Material 3 stable | Compose-first default; M3 Expressive deferred until stable |
| Architecture | Google layered (UI/domain/data), UDF, MVI-leaning single immutable `UiState` | Streaming + tool-call state machines need a reducer, not ad-hoc `mutableStateOf` |
| Navigation | **Navigation 3 1.1.x** (stable; 1.1.3, June 2026), Scenes API for list/detail | Compose-first, developer-owned back stack; adaptive panes. Verified GA, not RC. |
| DI | **Hilt** (KSP, 2.56+) | Official, first-class ViewModel/Nav integration; Android-only is fine for v1 |
| Async | Coroutines + Flow; injected `CoroutineDispatcher` | Maps to SSE streaming and blocking SSH; testable with test dispatcher |
| HTTP / streaming | **Ktor Client 3.5.x** (OkHttp engine) + built-in SSE plugin + kotlinx.serialization | Native coroutine SSE; Retrofit has no SSE. OkHttp engine for HTTP/2 streaming. **HTTPS enforced** (reject `http://` base URLs). |
| Persistence | **Room 2.8.x** (KSP) + **DataStore Proto** | Coroutine-first relational store. Room 3.0 (`androidx.room3`) shipped `3.0.0-alpha01` (Mar 2026) and is the KMP-first forward path, but is **alpha** — Room 2.x is the correct stable choice for v1; migrate when room3 stabilizes. |
| Secret storage | **Google Tink AEAD/`StreamingAead`** with the keyset wrapped by an Android Keystore master key; ciphertext in Proto DataStore. **NOT** security-crypto, **NOT** hand-rolled AES-GCM | security-crypto deprecated (final 1.1.0); Google's documented replacement is DataStore + Tink. Tink owns IV/nonce/AAD so we never hand-roll GCM. |
| Biometric gating | `androidx.biometric` BiometricPrompt + `CryptoObject` | Crypto-bound auth defeats UI-only bypasses |
| Markdown | `com.mikepenz:multiplatform-markdown-renderer` 0.39.x (`-m3`, `-code`) | Pure-Compose; highlights code blocks. Pin the exact Compose/Kotlin-compatible triple from release notes. |
| SSH | **sshj 0.40.0** | 0.40.0 includes the Terrapin (CVE-2023-48795) strict-kex mitigation; reject sshj ≤0.37.0. Clean API for coroutine wrapping. |
| MCP (later) | **`io.modelcontextprotocol:kotlin-sdk` 0.13.0**, Streamable HTTP / SSE only | Latest verified; stdio impossible on stock Android |
| Build | Gradle KDSL, `libs.versions.toml`, convention plugins in `build-logic`; AGP 9.1.x | Now-in-Android pattern; centralized versions |
| SDK levels | compileSdk/targetSdk 36, **minSdk 28** | Play floor is 35; 36 is current default. **minSdk 28 (Android 9) makes StrongBox and the strongest `BiometricPrompt`/`CryptoObject` overloads a guaranteed floor** — they require API 28, not 26. |

## 3. Module / Package Structure

```
clanker/
├─ build-logic/                  convention plugins (android-app, android-lib, compose, hilt, room)
├─ gradle/libs.versions.toml
├─ app/                          DI graph, NavDisplay, Application
├─ core/
│  ├─ model/                     pure Kotlin domain types (NO Android, NO OpenAI DTOs)
│  ├─ designsystem/              theme, M3 tokens, reusable composables
│  ├─ ui/                        shared Compose (markdown, message bubbles)
│  ├─ data/                      repositories
│  ├─ database/                  Room entities/DAOs
│  ├─ datastore/                 settings + Tink-encrypted secret store
│  ├─ security/                  Tink/Keystore wrapper, BiometricGate, approval engine
│  ├─ network/                   LlmProvider + OpenAiCompatibleProvider + wire DTOs (isolated here)
│  ├─ tools/                     Tool/ToolRegistry/ToolProvider; native, ssh, mcp adapters
│  ├─ context/                   token estimator + context-budget manager
│  └─ agent/                     agent loop orchestrator + SystemPrompt / composeSystemPrompt
└─ feature/
   ├─ chat/  servers/  settings/  tools/  inspector/
```

Wire DTOs live **only** in `:core:network`. `:core:agent`, `:core:tools`, UI depend on `:core:model`.

## 4. Core Domain Model

```kotlin
// :core:model — wire-faithful, provider-neutral
sealed interface ChatMessage {
  val lifecycle: MsgLifecycle           // Streaming | Complete | Failed | Interrupted | Aborted
  data class System(val content: String) : ChatMessage
  data class User(val content: String, val attachments: List<Attachment> = emptyList()) : ChatMessage
  data class Assistant(
    val content: String?,               // null when only tool_calls
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoning: List<ReasoningBlock> = emptyList(),   // NOT a String — see below
  ) : ChatMessage
  data class Tool(val toolCallId: String, val content: String, val isError: Boolean) : ChatMessage
}

// Reasoning MUST round-trip verbatim: Anthropic-style signed/encrypted thinking
// cannot survive flattening to a String. Store the opaque structured form.
data class ReasoningBlock(
  val type: String,                     // "thinking" | "redacted_thinking" | provider-specific
  val text: String?,                    // human-visible portion, if any
  val signature: String? = null,        // opaque, resend unchanged
  val opaqueData: String? = null,       // encrypted/redacted payload, resend unchanged
)

data class ToolCall(val id: String, val name: String, val argumentsJson: String) // arguments is a STRING

sealed interface Attachment {                                   // multimodal input is first-class
  data class Image(val mime: String, val bytesRef: BlobRef, val width: Int, val height: Int) : Attachment
  data class File(val mime: String, val name: String, val bytesRef: BlobRef) : Attachment
}
// Attachment bytes are stored as files referenced by BlobRef, NOT inlined Room blobs;
// the wire encoder emits the OpenAI content-array image_url/base64 data-URL form at request time.

data class Conversation(
  val id: Uuid, val title: String, val providerId: ProviderId,
  val modelId: String, val messages: List<ChatMessage>, val costAccumUsd: Double,
)

// No Character/Persona type — agent identity is the two-layer prompt (§7),
// composed at request time from SystemPrompt.TEXT + the global AGENTS.md.

data class ProviderConfig(
  val id: ProviderId, val baseUrl: String, val authRef: SecretRef,
  val defaultHeaders: Map<String,String>, val capabilities: Set<Capability>,
  val isCustomEndpoint: Boolean,        // true for any non-built-in host → confirmation gate (§10)
)

data class SshTarget(
  val id: Uuid, val label: String, val host: String, val port: Int, val username: String,
  val authRef: SecretRef, val pinnedHostKeySha256: String?,    // TOFU
  val mode: TargetMode,                                        // ReadOnly | ReadWrite
  val requiresForcedCommand: Boolean,                          // ReadWrite targets MUST set this
  val defaultTimeoutMs: Long, val maxBytes: Int,
)

// Persisted so process death mid-approval is recoverable; a CompletableDeferred is in-memory only.
data class PendingApproval(
  val id: Uuid, val conversationId: Uuid, val turnId: Uuid,
  val toolName: String, val resolvedArgs: JsonObject, val createdAt: Instant,
)
```

**Agent identity is a two-layer model, composed into the System message at request time** via `composeSystemPrompt(agentsMd)` (`:core:agent`), never baked into stored history — so edits apply retroactively:

- **Layer 0 — fixed system prompt.** `io.orangerabbit.clanker.core.agent.SystemPrompt.TEXT` is an app-versioned, non-user-editable base prompt that establishes the harness's identity and behaviour. It ships with the app and changes only with releases.
- **Layer 1 — user `AGENTS.md`.** A single user-editable global `AGENTS.md` string (stored in `SettingsStore`, edited in Settings) is appended after Layer 0. An empty/blank `AGENTS.md` yields just `SystemPrompt.TEXT`.

There are no per-character personas, macros, or per-conversation prompt overrides — see §7.

## 5. LLM Provider Abstraction

```kotlin
interface LlmProvider {
  val id: ProviderId
  val displayName: String
  suspend fun listModels(): List<ModelInfo>          // populates CapabilitySet
  fun streamChat(request: ChatRequest): Flow<ChatEvent>
  suspend fun chat(request: ChatRequest): ChatResponse
}

sealed interface ChatEvent {
  data class TextDelta(val text: String) : ChatEvent
  data class ReasoningDelta(val block: ReasoningBlock) : ChatEvent   // structured, not raw text
  data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argsFragment: String?) : ChatEvent
  data class Usage(val prompt: Int, val completion: Int, val total: Int, val costUsd: Double?) : ChatEvent
  data class Finished(val reason: FinishReason) : ChatEvent   // Stop|ToolCalls|Length|ContentFilter|Error
  data class Error(val apiError: ApiError) : ChatEvent
}
```

- **One `OpenAiCompatibleProvider(config)`** covers OpenRouter and every OpenAI-compatible host. `OpenRouterProvider` = it + attribution headers (`HTTP-Referer`, `X-Title`) + optional `models[]`/`provider{}` routing fields. Genuinely non-compatible providers (Anthropic/Gemini-native) get their own `LlmProvider` impl behind the same interface.
- **Streaming:** `client.sse{}` → parse each `data:` line as a `chat.completion.chunk`, stop on `[DONE]`, ignore `:` keepalives. **Tool-call deltas accumulate by `index`** — `id`/`name` may be null on later chunks; finalize/parse args only on `finish_reason == tool_calls`.
- **Usage/cost:** OpenRouter now returns full usage **and cost automatically in the final SSE chunk with no opt-in**. Both `stream_options.include_usage` and the newer `usage:{include:true}` are **deprecated and no-ops** — send neither; read usage/cost from the trailing chunk.
- **Capabilities are read, not guessed:** `GET /api/v1/models` → `architecture.input_modalities` (drives multimodal UI), `supported_parameters` (contains `"tools"`), `context_length` (feeds the context-budget manager, §6.1). Cached with TTL; static table fallback for thin providers. Flags are advisory (handle a model declining tool calls).
- **Retry:** exponential backoff + jitter honoring `Retry-After`; retry only 429/5xx/network, bounded 3–5; never 400/401/403; surface 402 (insufficient credits). **SSE reconnect = restart the turn**, not resume-from-offset (no major provider resumes a stream by offset). A restart MUST discard any partially-emitted assistant content and any partially-accumulated `tool_calls` from the dropped attempt so history never carries orphaned tool calls.

## 6. The Agent Loop

Provider-agnostic orchestrator in `:core:agent`, emitting a `Flow<AgentEvent>` the ViewModel collects.

1. Build request: run the **context-budget manager** (§6.1), compose the System message via `composeSystemPrompt(agentsMd)` (fixed `SystemPrompt.TEXT` + global `AGENTS.md`, §7), history, and the `tools` array from `ToolRegistry`.
2. `streamChat()`. Surface `TextDelta`/`ReasoningDelta` live. Accumulate tool calls by index. Mark the in-flight assistant message `Streaming`.
3. On `Finished(Stop)` → mark `Complete`, done. On `Finished(ToolCalls)` → continue.
4. **Append the assistant message verbatim** (content + tool_calls + structured reasoning) to history. *Every* `tool_call_id` MUST get exactly one `role:tool` reply or the next request 400s.
5. For each tool call: validate args against JSON Schema → **clamp/normalize** → **policy/approval gate** (§10) → execute.
   - **Concurrency policy keyed to RiskLevel:** read-only tools run concurrently in `supervisorScope`. **Mutating/destructive tools — and any two tools targeting the same SSH target — run strictly serially**, since one ref-counted `SSHClient` cannot safely interleave mutating sessions. `parallel_tool_calls` is therefore advertised true only when all pending calls are read-only.
   - Each call gets `withTimeout`; a failure/timeout/denial still produces a `role:tool` error message (never a missing one).
6. Append all tool results in order; loop.
7. **Termination guards:** max iterations (default ~15, configurable) + **degenerate-loop detection** (same tool + identical args repeated). Abort with a user-visible "tool loop limit reached."

**Approval** suspends the coroutine on a `CompletableDeferred` resolved by the UI — never blocks the main thread. The corresponding `PendingApproval` row is **persisted before suspending**; on relaunch the loop is re-derived from persisted history + the pending row (the in-memory `Deferred` cannot survive process death).

**Cancellation / Stop-generation** is a first-class path. A user abort: (a) cancels the in-flight SSE collection, (b) cancels running tool coroutines, (c) leaves history valid — any assistant turn with emitted `tool_calls` that did not all receive `role:tool` replies is either completed with synthetic "aborted by user" tool results or the whole assistant turn is rolled back, so no resend ever carries an unanswered `tool_call_id`. Aborted messages are marked `Aborted`.

### 6.1 Context-budget manager (`:core:context`)

`context_length` is discovered per model and **enforced**, not merely displayed.

- A token estimator (heuristic char/token ratio per model family; exact tokenizer where one is bundled) accounts the full prospective request against the model's context window minus a reserved completion margin.
- **Overflow strategy (configurable, default = rolling summarization with truncation fallback):** when the budget would be exceeded, oldest non-pinned turns are summarized into a compacted system note; if still over, oldest turns are dropped. The composed System message (`SystemPrompt.TEXT` + `AGENTS.md`) is pinned.
- **Tool-output capping** is layered with this: large SSH/web results are byte-capped (first+last N KB) before insertion, and the budget manager may further summarize them. A 15-iteration loop with large outputs must never silently 400 or truncate the wire body.

## 7. Agent definition

**clanker no longer imports SillyTavern character cards.** The earlier Character Card V2/V3 path — `:core:character`, the `Persona` model, the PNG chunk walker, the macro engine, lorebooks, and per-character `system_prompt`/`post_history_instructions` overrides — has been removed (see the §14 decision log).

Agent identity is the two-layer prompt described in §4: a fixed, app-versioned `SystemPrompt.TEXT` (Layer 0) plus a single user-editable global `AGENTS.md` (Layer 1), composed at request time via `composeSystemPrompt(agentsMd)` and never baked into stored history. There is exactly one global agent definition; there are no per-character profiles, no card parsing, and no prompt macros.

Multiple named agent profiles, per-conversation persistence, and richer prompt assembly (e.g. tool descriptions injected into the prompt) are sequenced as later work, not part of the current model.

## 8. Tool System

```kotlin
interface Tool {
  val name: String                  // namespaced: native__x, ssh__<server>__<verb>, mcp__<server>__<tool>
  val description: String
  val parameters: JsonObject        // JSON Schema — the lingua franca
  val source: ToolSource            // Native | Ssh(serverId) | Mcp(serverId)
  val execution: ToolExecution      // ClientExecuted | ProviderExecuted   ← see below
  val riskLevel: RiskLevel          // ReadOnly | Mutating | Destructive  → drives approval + concurrency
  val timeoutMs: Long
  suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult   // ClientExecuted only
}
interface ToolProvider { suspend fun tools(): List<Tool> }
```

`ToolRegistry` aggregates providers, emits the OpenAI `tools` array, dispatches `tool_calls`. **Args are validated and clamped against the schema before execution — model output is always treated as hostile**, especially for SSH.

**Two execution modes** (the loop handles them differently):

- **`ClientExecuted`** (Native client-side, SSH, MCP): the harness runs `execute()` and produces the `role:tool` reply itself. The "one reply per `tool_call_id`" invariant is the harness's responsibility.
- **`ProviderExecuted`** (OpenRouter server tools): advertised by adding `{"type": "openrouter:web_search"}` / `{"type": "openrouter:web_fetch"}` to the request `tools` array. These execute **server-side**; OpenRouter returns the call and its result inline in the stream. The harness does **not** call `execute()` and does **not** synthesize a `role:tool` reply for these — it records them in the transcript for display but excludes them from the client-side "every tool_call_id needs a reply" check, which applies only to `ClientExecuted` calls.

- **Native web search/fetch (two-tier):** default = OpenRouter `ProviderExecuted` `openrouter:web_search` / `openrouter:web_fetch` (server-side, any model, zero client plumbing). Portable fallback = client-side `ClientExecuted` `ExaSearchTool`/`WebFetchTool` for non-OpenRouter providers.
- **SSH tools — structured RPC verbs, no shell (see §9):** a small fixed set (`ssh__<server>__read_file`, `write_file`, `list_dir`, and a *whitelisted-verb* `run` that takes a fixed verb enum + structured args, **never** a free-form command string). The model never sees credentials and never emits a command line.
- **MCP future-proofing:** `McpToolProvider` wraps the Kotlin SDK 0.13.0; `listTools()` → `Tool.parameters` (MCP `inputSchema` *is* JSON Schema), `callTool()` → `execute()`. **Remote transports only** (Streamable HTTP/SSE). Remote MCP in 2026 uses OAuth 2.1 / bearer auth, so `ToolSource.Mcp` and the secret store **reserve per-MCP-server credential storage and an OAuth consent flow now**, even though wiring lands after native+SSH.

## 9. SSH Integration

- **Library: sshj 0.40.0** (Terrapin-mitigated; reject ≤0.37.0). Reject MINA (unsupported on Android), reject dead jcraft; `com.github.mwiede:jsch` is the only fallback. **BouncyCastle is optional** — sshj works without it on modern Android; bundle `bcprov-jdk18on` only if a specific algorithm gap appears on the minSdk matrix, since it adds attack surface and R8 complexity.
- **No free-form commands ever cross the wire.** This is the load-bearing security decision and it shapes the tool layer:
  - File operations (`read_file`/`write_file`/`list_dir`) use **sshj `SFTPClient`**, not `cat`/`echo`/`ls` via a shell — SFTP avoids quoting, binary, and large-file fragility entirely, and there is no shell to inject into.
  - Command execution uses `session.exec()` **only against a server-side forced-command wrapper** (below), passing a **structured protocol payload (JSON on stdin)**, never a user/model-authored command string. The harness never concatenates model output into a command line.
- **Coroutine model:** all blocking calls on `Dispatchers.IO`; `suspendCancellableCoroutine` + `invokeOnCancellation` → `client.disconnect()` so cancellation tears down the socket (sshj ignores thread interruption). Connect/auth/per-command `withTimeout`.
- **Host key — TOFU pinning, custom `HostKeyVerifier`:** capture SHA-256 fingerprint on first connect, show to user, persist per-target, verify on every reconnect. **Never** `PromiscuousVerifier`; **fail closed** on key change (require explicit re-approval). No silent auto-write.
- **Auth:** prefer ed25519 public-key; private key encrypted at rest (§10). Password/keyboard-interactive as fallback.
- **Lifecycle:** one long-lived ref-counted `SSHClient` per target (Mutex-guarded connect), keepalive, disconnect on idle/background. Because mutating commands must serialize per target, the loop holds a per-target serialization lock for any non-read-only call. Stream output as `Flow<String>`, **cap captured bytes** (first+last N KB to the model), drain stderr to avoid deadlock.
- R8/ProGuard keep rules for sshj reflective SPI (and BC only if bundled).

## 10. Security Model

The threat model is explicit: **prompt injection is structural and unfixable, the model is assumed hostile, and a rooted device is assumed compromised.** Controls are layered with a clear statement of which are authoritative and which are advisory.

**Secret storage:** use **Google Tink** as the crypto primitive — a Tink AEAD keyset (or `StreamingAead` for large secrets) whose master key lives in the Android Keystore, **StrongBox-preferred** (`hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` requires **API 28**, satisfied by minSdk 28 → `setIsStrongBoxBacked(true)`, catch `StrongBoxUnavailableException` → TEE fallback). Tink owns IV/nonce/AAD and supports key rotation, so no AES-GCM is hand-rolled. Ciphertext stored in Proto DataStore. **No security-crypto.**

**Custom provider endpoints (key-exfiltration defense):** BYOK + a user-editable base URL is an exfil vector. Therefore: **reject non-HTTPS base URLs**; any `isCustomEndpoint` provider triggers an explicit, unmistakable confirmation before a key is ever sent to it; provider configs are **not silently importable/shareable with a key attached** — imported configs land without secrets and require the user to re-enter the key against a confirmed endpoint.

**Auth gating (granular, combined to avoid double-prompt fatigue):**
| Secret / action | Gate |
|---|---|
| SSH private key + destructive/mutating actions | Per-use BiometricPrompt + `CryptoObject`, `AUTH_BIOMETRIC_STRONG`, `setInvalidatedByBiometricEnrollment(true)`. **The biometric `CryptoObject` unlock and the command-approval consent are presented as a single user action**, not two stacked prompts, to avoid fatigue-driven security disablement. |
| Provider API key | Short time-window or none (lower impact) |

(On any device lacking StrongBox or with a flaky biometric HAL, the gate degrades to TEE-backed keys + device-credential fallback; the degradation is surfaced in the threat-model UI, not silent.)

**The central threat — LLM-driven arbitrary effects.** Defense in depth, honest about what each layer buys:

1. **Server-side enforcement is the only authoritative control.** Each read-write target requires: a dedicated low-privilege account, a per-server ed25519 key, and `authorized_keys` `command="<wrapper>",restrict` (or `Match`+`ForceCommand`). **The wrapper MUST ignore `$SSH_ORIGINAL_COMMAND`'s free text entirely** and instead read a *fixed structured protocol* (JSON on stdin, or a strict verb+typed-arg vocabulary), dispatching only a closed set of verbs with argv-vector execution and no shell evaluation. A wrapper that dispatches on `SSH_ORIGINAL_COMMAND` free text is fully injectable and provides *negative* value (false confidence) — that design is explicitly prohibited. This wrapper is mandatory onboarding for ReadWrite targets and ships as a documented, auditable script.
2. **Human-in-the-loop gate** by `RiskLevel`: read-only auto-runs; mutating/destructive pauses the loop and shows the *fully resolved structured call* (verb + typed args + target) and, where possible, a dry-run. Deny → fed back as a `role:tool` rejection so the model adapts.
3. **Client-side argument validation/clamping** against JSON Schema. This is *defense-in-depth, not a boundary* — the doc does not claim client-side checks stop a determined injection; the server-side closed verb set does. **There is no client-side free-form-shell parser**, because proving an arbitrary bash string safe is intractable (substitution, globbing, aliases, runtime PATH/expansion the client cannot see).
4. **Per-target capability scoping** + **read-only mode** as the default for new targets.
5. **Audit log — on-device is advisory only.** An append-only, hash-chained log links each command to its originating model turn, redacting secrets. On a rooted device this log and the secrets are attacker-controlled, so **the server-side log (written by the wrapper) is the authoritative system of record**; the on-device log is for in-app review and convenience, never relied upon for forensics.
6. Never inject raw remote/web output into a system prompt unescaped — all of it is attacker-influenced.

## 11. Chat UX

- **Streaming without jank — the #1 lever:** do **not** bind raw tokens to Compose state. Accumulate in the ViewModel; emit a throttled/`sample()`d `StateFlow` at ~frame cadence (30–60ms). **Force-emit the final accumulated text on completion** (sample swallows the last chunk). Only the active streaming bubble is mutable; completed messages are `@Immutable`.
- **LazyColumn:** stable content keys (`key = { it.id }`, **never index**), `contentType` per role, `reverseLayout = true` for bottom-anchoring, gated `animateScrollToItem` + jump-to-latest FAB.
- **Markdown:** mikepenz renderer + `-m3` + `-code`. Handle incomplete mid-stream markdown (open ``` fence rendered as open block until closed) to avoid flicker.
- **Stop button:** always available during generation; wired to the §6 cancellation path.
- **Tool & approval cards:** model tool interactions as first-class transcript items (`ToolCall`/`ToolResult`/`ApprovalRequest`, plus server-executed search/fetch results). Approval card = Approve / Reject / Edit-args; for SSH show the full resolved structured call (verb + args + target host), copy-selectable.
- **Cost surface:** per-conversation running cost (from the §5 usage chunk) shown in-UI; a configurable per-conversation and global spend ceiling warns/halts before runaway auto-tool loops rack up cost.
- **Request/response inspector** (`feature/inspector`): a developer surface showing the exact JSON sent/received per turn (secrets redacted), adjacent to the audit log — near-essential for debugging tool-call and provider quirks, cheap now, expensive to retrofit.

## 12. Persistence & State

| Data | Store |
|---|---|
| Conversations, messages (wire-faithful, incl. structured reasoning), SSH targets, pending approvals, audit log | Room 2.8.x (room3 when stable) |
| Attachment bytes (images/files) | Files on disk, referenced by `BlobRef` (not inline Room blobs) |
| Non-secret settings (selected provider, model, theme, autonomy level, spend ceilings, global `AGENTS.md`) | DataStore Proto |
| API keys, SSH keys/passwords, MCP OAuth tokens | Tink-encrypted ciphertext in DataStore |
| Large histories | Paging 3 (`collectAsLazyPagingItems`) |

Canonical wire-model is the source of truth; UI projection (streaming buffers, approval state, tool cards) is derived. **Pending approvals and per-message lifecycle are persisted** so process-death/interrupt mid-turn is recoverable.

**Export / backup:** conversations, the global `AGENTS.md`, and server configs are exportable. Because secrets are Tink-encrypted and Keystore-bound (non-portable), export **excludes secrets by default**; an optional "include secrets" path re-encrypts under a user passphrase. This is a deliberate design item, not an afterthought — naive backup of Keystore-bound ciphertext is unrecoverable on a new device.

## 13. Phased Roadmap

**MVP — talk to OpenRouter**
- Gradle multi-module + convention plugins + version catalog
- `:core:network`: `OpenAiCompatibleProvider` + `OpenRouterProvider`, SSE streaming, capability discovery, **structured-reasoning persistence**, HTTPS-only + custom-endpoint confirmation
- Chat screen: throttled streaming, markdown, stable-keyed LazyColumn, stop button
- `:core:context` budget manager (estimator + overflow strategy)
- Room persistence; BYOK API key in Tink-encrypted store; per-message lifecycle + resume/discard on restart
- Nav3 1.1.x graph (conversation list ↔ chat ↔ settings); request/response inspector
- Cost surface + spend ceiling

**v1 — agentic**
- Agent loop (iteration cap, degenerate-loop guard, RiskLevel-keyed concurrency, **persisted approvals**, full cancellation path)
- `Tool`/`ToolRegistry` with `ClientExecuted`/`ProviderExecuted` modes; native web search/fetch (OpenRouter server tools + Exa/fetch fallback)
- Two-layer agent definition: fixed `SystemPrompt.TEXT` + user-editable global `AGENTS.md` (`composeSystemPrompt`), edited in Settings
- SSH: sshj, TOFU pinning, **SFTP file ops + structured-verb exec via forced-command wrapper**, approval UX, advisory audit log, biometric gating with combined consent
- Documented server-side wrapper + onboarding flow
- Adaptive list/detail panes; export/backup (secrets excluded)

**Later**
- MCP (remote transports, SDK 0.13.0, OAuth credential storage already reserved); additional providers (Anthropic/Gemini-native, local servers); Responses API behind capability flag
- Multiple named agent profiles + per-conversation agent persistence (Room); tool descriptions composed into the prompt; M3 Expressive as it stabilizes
- Room 3.0 migration when stable; Keystore-resident SSH signing; KMP extraction if iOS/desktop is pursued

## 14. Open Decisions for the User

| # | Decision | Options | Recommendation |
|---|---|---|---|
| 1 | **SSH execution model** (decide before building the tool layer — it shapes the `Tool` interface and the wrapper spec) | Free-form `run_command` string (insecure) / **structured RPC verbs with argv vectors + fixed server-side protocol** | **Structured verbs only.** Drop arbitrary command strings entirely for v1. SFTP for file I/O, a closed verb set for the rest. This is the single most important security decision. |
| 2 | **minSdk floor** | 26 (broader reach, StrongBox + strongest biometric become best-effort) / **28** (StrongBox + `CryptoObject` overloads are a guaranteed floor) | **28.** The security posture the app wants (StrongBox, strong BiometricPrompt) requires API 28; 26 makes them silently degrade. Accept the small install-base loss. |
| 3 | **Encryption primitive** | Hand-rolled AES-GCM over a Keystore key (footgun) / **Google Tink AEAD/StreamingAead over a Keystore-wrapped keyset** / community security-crypto fork | **Tink.** Google's documented replacement for the deprecated security-crypto; handles IV/nonce/AAD/rotation so we never hand-roll GCM. Decide before writing `:core:security`. |
| 4 | **Reasoning round-trip fidelity** | Flattened `String` (lossy, simpler) / **opaque structured `ReasoningBlock` list** (round-trips Anthropic signed/encrypted thinking) | **Structured blocks.** Expensive to change post-launch; required for any provider that signs/encrypts thinking. Decide before MVP persistence lands. |
| 5 | **Default tool autonomy** | Approve-everything / **default-deny + read-only auto-allowlist** / full auto | **Default-deny + read-only auto-allowlist.** Broader autonomy only for user-marked targets, always with the server-side forced-command backstop. |
| 6 | **Parallel tool calls for mutating/SSH tools** | Allow parallel for all / **read-only only; mutating + same-target serial** | **Read-only only.** Parallel mutating commands against one connection interleave destructively. Tie the policy to `RiskLevel`. |
| 7 | **Custom provider base-URL policy** | Allow any URL freely / **HTTPS-only + explicit confirmation for non-built-in endpoints + no secret-bearing config import** | **HTTPS-only + confirmation, imports drop secrets.** BYOK + arbitrary URL is a key-exfil vector. |
| 8 | **SSH server onboarding burden** | Require server-side forced-command/least-priv wrapper / allow plain accounts | **Require the wrapper** for any read-write target — it is the authoritative control, and the wrapper must parse a fixed protocol, not `SSH_ORIGINAL_COMMAND` free text. Confirm you accept imposing this setup. |
| 9 | **Audit authority** | On-device hash-chained log as system of record / **server-side wrapper log authoritative, on-device advisory** | **Server-side authoritative.** A rooted device compromises the on-device chain; don't rely on it for forensics. |
| 10 | **Web search/fetch billing** | OpenRouter server tools (per-req cost, OpenRouter-only) / client-side Exa+fetch (your key, portable) / both | **Both** behind one `Tool` interface (provider-executed + client-executed modes). Confirm you accept OpenRouter per-request cost and/or will provision an Exa key. |
| 11 | **API key handling** | **BYOK in Tink/Keystore** / shipped shared key / backend proxy | **BYOK** for v1 (no secret in binary). Backend proxy only for later managed billing. |
| 12 | **Context-overflow policy** | Hard-truncate oldest / **rolling summarization with truncation fallback** / user-prompted prune | **Rolling summarization, truncation fallback.** Affects the loop and message model; decide before the loop is built. |
| 13 | **Multimodal input scope for MVP** | Image input in v1 / **deferred but `Attachment` modeled now** | **Model now, ship when ready.** Capability discovery already reads `input_modalities`; designing `Attachment` later forces a schema break. |
| 14 | **Cost guardrails** | None / **per-conversation + global spend ceiling, surfaced and enforced** | **Surface and enforce.** Relevant the moment auto-run read-only tools exist; cheap to add with the usage chunk already parsed. |
| 15 | **Export/backup with encrypted secrets** | No export / **export excluding secrets (default) + optional passphrase-reencrypted secrets** | **Export excluding secrets by default.** Keystore-bound ciphertext is non-portable; design the boundary now or document the limitation. |
| 16 | **Room 2.x vs Room 3.0** | Room 2.8.x stable / room3 alpha now | **Room 2.8.x for v1** (room3 is `3.0.0-alpha01`, contradicts "stable over shiny"); migrate to room3 when it GAs, aligning with the KMP-cheap principle. |
| 17 | **DI: Hilt vs Metro** | Hilt (Android-only, mature) / Metro (KMP, newer) | **Hilt** — coupled to staying Android-only for v1. Choose Metro only if KMP is committed now. |
| 18 | **Multiplatform now?** | Android-only / KMP day 1 / **defer (keep core UI-free)** | **Defer.** Android-only v1; keep `:core:*` UI-free so a later KMP move is cheap. Revisit only if iOS/desktop is a real goal. |

### Decision log / reversals

> **2026-06-24 — Persona/character cards removed.** Clanker is an agent harness only. The SillyTavern character-card path (`:core:character`, `Persona`, macros, per-character overrides) is deleted in favour of a two-layer prompt: a fixed, app-versioned system prompt plus a single user-editable global `AGENTS.md`, composed at request time. Multiple agent profiles, per-conversation persistence (Room), and tool descriptions in the prompt are sequenced as later work. Spec: `docs/superpowers/specs/2026-06-24-agent-definition-layering-design.md`.

---

**Verified during synthesis (June 2026):** StrongBox/`FEATURE_STRONGBOX_KEYSTORE` requires **API 28**, not 26 — minSdk raised to 28. OpenRouter `openrouter:web_search` **and** `openrouter:web_fetch` are both real server-executed tools (agentic-web-tools announcement) — they run server-side and do not flow through client `execute()`. OpenRouter usage+cost arrive automatically in the final SSE chunk; both `stream_options.include_usage` and `usage:{include:true}` are deprecated no-ops. `androidx.security:security-crypto` is deprecated; Google's documented replacement is **DataStore + Tink**, so Tink AEAD is used over hand-rolled GCM. **Navigation 3 is GA (1.1.3, June 17 2026)** — used on the critical path. **Room 3.0 is `3.0.0-alpha01`** (Mar 2026) — Room 2.8.x remains the stable v1 choice. sshj **0.40.0** (Terrapin-mitigated; reject ≤0.37.0); BouncyCastle is optional on modern Android. MCP Kotlin SDK **0.13.0**.

Sources: [Android Keystore / StrongBox (API 28)](https://developer.android.com/privacy-and-security/keystore), [OpenRouter agentic web tools](https://openrouter.ai/announcements/agentic-web-tools), [OpenRouter server tools overview](https://openrouter.ai/docs/guides/features/server-tools/overview), [OpenRouter usage accounting](https://openrouter.ai/docs/cookbook/administration/usage-accounting), [security-crypto deprecation → DataStore+Tink](https://developer.android.com/jetpack/androidx/releases/security), [Navigation 3 release notes (1.1.3 stable)](https://developer.android.com/jetpack/androidx/releases/navigation3), [Room 3.0 (alpha)](https://developer.android.com/jetpack/androidx/releases/room3), [sshj versions](https://central.sonatype.com/artifact/com.hierynomus/sshj/versions), [Terrapin CVE-2023-48795](https://nvd.nist.gov/vuln/detail/cve-2023-48795), [MCP Kotlin SDK](https://central.sonatype.com/artifact/io.modelcontextprotocol/kotlin-sdk/versions)