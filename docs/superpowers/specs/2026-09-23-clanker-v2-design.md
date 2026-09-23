# clanker — Product & Architecture Design (v2)

**Date:** 2026-09-23
**Status:** Draft for review
**Replaces:** The native-Android clanker design (`android/clanker/DESIGN.md`) and the unexecuted PWA plan. This is a fresh repo; the old repo remains as reference.

---

## 1. Overview

clanker is an open-source, cross-platform (Android + iOS) agentic AI client built on **OpenRouter as the sole backend** — its models, its server tools (web search, fetch, image generation, shell/bash sandboxing), and its BYOK OAuth flow. The goal is to be **the most capable mobile AI client there is**: the default answer to "run powerful, agentic AI from my phone."

Everything expensive executes server-side at OpenRouter. The client's job is: streaming chat UI, tool policy and approvals, wire-faithful history, secure key storage, and spend visibility. That keeps the client thin, the feature surface as wide as OpenRouter's, and the roadmap driven by OpenRouter's API surface rather than by device-specific engineering.

### Success criteria

- A user can connect their OpenRouter account in one OAuth tap and have a streaming chat with web-grounded answers on both Android and iOS.
- A power user can run agentic requests (server tools + client tools) with visible, enforced spend caps and an approval policy they control.
- The app ships from a public open-source repo with release builds for both stores when monetization lands.

### Design principles (carried from clanker v1)

| Principle | Consequence |
|---|---|
| **The abstraction is the product.** | `LlmTransport` and `Tool` interfaces own the value. No OpenAI-shaped DTO escapes the network layer. |
| **Server tools first; client loop for device tools.** | OpenRouter executes server tools mid-request. The client-side loop exists only for user-defined/device tools and interactive approvals. |
| **Wire-faithful persistence, derived UI.** | Stored messages mirror the API wire shape, including opaque reasoning blocks. UI state is a projection. |
| **Bounded everything.** | Every loop, context window, and spend has an explicit ceiling. Spend caps are a first-class UI, not a hidden constant. |
| **Execution backend is a property of a tool.** | `Tool.backend ∈ {openrouter, device, hosted}`. `hosted` is reserved for future clanker-operated services (revenue expansion) — designed in from day one, not implemented until later. |
| **The device and the model are hostile.** | Prompt injection is structural. Server-side limits (spend caps, tool budgets, guardrails) are primary; client controls are defense-in-depth. |

## 2. Business model

- **BYOK always.** Inference is billed to the user's own OpenRouter account via the OAuth PKCE flow. clanker never holds, proxies, or marks up inference spend. (OpenRouter's ToS prohibits reselling API access; bundled-credits arrangements would be an explicit partner deal, out of scope.)
- **Monetization = app features.** 7-day free trial, then **$1/mo or $10/yr** (RevenueCat; app-store billing). The Pro entitlement gates app capability, never model access.
- **Open source.** Public repo. Release signing keys, store credentials, and RevenueCat public config only in private.
- **Sequencing.** Monetization and store publishing land around the image-generation phase (Phase 2) — not before.

## 3. Tech stack

| Concern | Choice | Rationale |
|---|---|---|
| Codebase | Kotlin Multiplatform; shared logic + Compose Multiplatform UI | Reuses clanker v1's validated pure-Kotlin core design; one codebase for Android + iOS (desktop nearly free); no corner-painting (Ktor server reuse later for hosted tools/sync) |
| UI | Compose Multiplatform + Material 3 | Single UI tree; platform-specific glue in thin `expect/actual` layers |
| HTTP / SSE | Ktor Client + kotlinx.serialization | Coroutine-native SSE streaming; HTTPS enforced |
| Persistence | SQLDelight | KMP-first relational store (Room is Android-only); wire-faithful message tables |
| Secrets | Platform secure storage via `expect/actual`: Android Keystore / iOS Keychain, biometric-gated | The BYOK key is the crown jewel; never in plaintext storage |
| Payments | RevenueCat (KMP SDK) | Subscription entitlement "Pro"; trial configured in store consoles |
| DI | kotlin-inject or Koin (decide at plan time) | Multiplatform DI without codegen lock-in |
| Build | Gradle KDSL + convention plugins; Nix flake devShell | Matches existing tooling habits |
| Versions | Pinned and verified at plan time per phase (Kotlin/CMP/Ktor/SQLDelight) | Do not hardcode versions in this spec; verify against release notes when planning each phase |

**Rejected alternatives:** Expo/RN (throws away validated Kotlin core; web target adds debt), PWA+Capacitor (iOS ceiling on the exact differentiators: background runs, secure storage, IAP).

## 4. Architecture

```
clanker/
├─ shared/                  pure Kotlin, no platform deps
│  ├─ model/                wire-faithful domain types
│  ├─ network/              OpenRouter client: SSE, Chat Completions (+ Responses/Messages
│  │                        adapters added in later phases), OAuth PKCE, catalog, videos, containers
│  ├─ agent/                client tool loop + server-tool policy engine (approvals, budgets)
│  ├─ tools/                Tool/ToolRegistry; backend enum {openrouter, device, hosted}
│  ├─ persistence/          SQLDelight; conversations, messages, tool results, settings
│  ├─ security/             encrypted key store, biometric gate (expect/actual)
│  └─ context/              context-budget manager, token estimation
├─ composeApp/              shared Compose UI
│  └─ feature/ chat, tools, inspector, settings
├─ iosApp/                  Xcode shell: notifications, IAP glue, URL-scheme handling
└─ androidApp/              Android shell
```

### Domain model

Carried over verbatim from clanker v1 DESIGN.md §4 (wire-faithful `ChatMessage` sealed hierarchy, `ToolCall` with string arguments, `ReasoningBlock` with verbatim round-trip of signed/encrypted thinking, `Attachment` blobs by reference). Additions:

- `Tool.backend: {OPENROUTER, DEVICE, HOSTED}` — execution venue, decided at registration.
- `ToolPolicy`: per-tool approval mode (`ALWAYS_ASK | ALLOW_PER_CHAT | ALWAYS_ALLOW`), per-request `max_tool_calls`, per-request and per-day spend caps.
- `GenerationJob`: async job record (video, later image batches) — id, polling URL, status, spend cap.

### Transport evolution by phase

| Phase | Primary API | Why |
|---|---|---|
| 1 | OpenAI Chat Completions + SSE | Broadest model compat; web_search/web_fetch/datetime/image_generation server tools all work here |
| 3 | + Responses API, Anthropic Messages API | Required for `shell` (Responses+Messages) and `bash` (Messages only); the wire-faithful model already supports both shapes |
| 4 | + videos API, containers API, MCP-over-HTTP | Async jobs and tool discovery |

The message model never flattens provider-specific blocks — adding an API surface never requires a migration.

### OpenRouter capabilities (verified 2026-09-23)

- Chat Completions, Responses, Messages APIs; SSE streaming; automatic per-response cost accounting.
- Server tools (beta): `web_search`, `web_fetch`, `datetime`, `image_generation`, `apply_patch` (Responses), `shell` (Responses+Messages, always server-side), `bash` (Messages only, dual-mode: client `auto`/`native` default, server `engine:"openrouter"`), `fusion`, `advisor`, `subagent`, `search_models`, `tool_search`. Loop budgets: `max_tool_calls` ≤30/request, `stop_server_tools_when` (step/spend).
- Containers: session-scoped sandbox FS, 5-min idle sleep, 30-day file retention, default-no-egress network policy (allowlist ≤50 domains, ports 80/443, fixed at start), container files API + `promote` to workspace. Limits: 2-min default/5-min max per command, 64k output cap, ≤100 commands/call.
- Dedicated async APIs: image generation, video generation (`POST /videos` → poll → download; per-second pricing, webhook optional), files, embeddings, STT/TTS, batch.
- Guardrails: prompt-injection detection, secret redaction, spend budgets.
- OAuth PKCE for user-controlled keys; app attribution + public apps marketplace.
- Management API: keys, credits, activity, BYOK, guardrails (server-side, future use).

## 5. Phased roadmap

### Phase 1 — Chat (MVP)

Open-source repo scaffold; no store, no payments.

1. App scaffold: KMP project, Compose shell, navigation, theming; Nix flake devShell; CI (build + tests).
2. OAuth PKCE connect: open `openrouter.ai/auth` with S256 challenge, exchange code for user-controlled key, store encrypted. Spike first (see §6): callback handling on mobile (custom scheme vs universal links vs localhost).
3. Streaming chat: conversation list, model picker (catalog API), markdown rendering, streaming bubbles, reasoning display, per-response cost display.
4. Server tools: `web_search`, `web_fetch`, `datetime` — per-chat toggles, sources UI for search results, capped `max_results`.
5. Persistence: SQLDelight conversations/messages, wire-faithful; export/import JSON.
6. Session/keep-awake: disable screen idle during an active run (backgrounded runs lose the stream until the Phase 4+ push relay).
7. Guardrails: enable prompt-injection detection and secret redaction by default; per-request spend cap with sensible default.

**Exit criteria:** both platforms build and run from the same tree; a chat with web-grounded answers works end-to-end on a real device; key survives app restart encrypted; no plaintext secrets on disk.

### Phase 2 — Image generation + monetization

1. Image gen: server tool in chat + standalone "Studio" surface using the dedicated image API; image gallery persisted as blob refs; save-to-photos.
2. Spend caps UI: per-request and per-day caps, per-tool budget visibility (usage accounting is already in every response).
3. RevenueCat: "Pro" entitlement, 7-day trial, $1/mo or $10/yr; paywall only on app features.
4. Store publishing: Play Store + App Store builds, screenshots, app attribution registered with OpenRouter (marketplace listing), release pipeline (private signing, CI uploads).

### Phase 3 — Shell, bash, containers

1. Server-side shell/bash: enabled per chat; visible spend counters (shell scales badly); container/session indicator in the transcript.
2. Network policy UX: locked to no-egress by default; allowlist editing behind an explicit warning; policy is fixed per container (409 on change) so the UI reflects that.
3. Container file browser: list/download/promote via container files API.
4. On-device bash (Android differentiator): client-side execution mode (`engine: auto`) with the ToolPolicy approval UI — model-proposed commands run in an app-internal shell, always-approve-first, output streamed back. iOS: server-sandbox only (no local binaries); identical UX, different backend.
5. Apply-patch / workspace files where supported.

### Phase 4 — Powerful features

1. MCP client tools: user-registered remote MCP servers (HTTP/SSE transport only — no stdio on mobile); `tool_search` for on-demand discovery to keep the context budget sane.
2. Multi-model: `subagent`, `advisor`, `fusion` with their inner budget controls.
3. Video generation: async job queue with polling, per-job spend cap, gallery.
4. Context inspector: full wire view per message, tool call/result inspection, token and cost breakdown per request.

### Later (sketched, not specced)

- **Hosted tools (`backend = HOSTED`):** clanker-operated sandbox fleet, hosted MCP gateway, sync — the Pro-feature revenue expansion surface.
- Push relay + background agent runs (requires backend).
- Cross-device sync; desktop target via Compose Desktop.

### Non-goals (v1)

Reselling/markup of inference, sync backend, push notifications, web client, video gen, MCP, multi-model orchestration — all explicitly deferred by phase above.

## 6. Security

- BYOK key: Keystore/Keychain-wrapped encryption, biometric-gated access, never logged, never exported.
- OAuth PKCE with S256; code verifier never persisted beyond the exchange.
- Client-side bash (Phase 3) is always-approve-first; no auto-allow mode; command transcript retained in the inspector.
- Server-tool spend caps enforced by OpenRouter (`stop_server_tools_when`) — the client sets them, the server enforces them; client-side caps are display-only.
- Prompt-injection guardrail on by default; the UI surfaces detected-injection warnings.
- No analytics beyond OpenRouter app attribution; privacy mode respected.

## 7. Error handling

- Streaming failures: partial content preserved and marked `Interrupted`; retry re-sends the wire-faithful history.
- Tool failures: rendered as error tool results, never swallowed; agent loop continues or halts per policy.
- Budget exhaustion: explicit terminal state ("spend cap reached"), not a silent truncation.
- Beta-status server tools: feature flags per tool so OpenRouter beta changes can't break the whole app.

## 8. Testing

- `shared/` unit tests: wire-faithful model round-trips, policy engine, spend accounting, PKCE exchange.
- Transport tests against recorded SSE fixtures per API surface.
- Compose UI tests for chat streaming and approval flows; platform-specific instrumented tests for Keystore/Keychain and biometric.
- Phase exit criteria (§4) are the acceptance tests.

## 9. Open questions → spikes before Phase 1 planning

| # | Question | Probe |
|---|---|---|
| S1 | Does OpenRouter OAuth accept custom URL schemes / universal links as `callback_url` on mobile? | Test the PKCE flow from a bare app with `clanker://callback` and an associated domain |
| S2 | Does the Responses API on OpenRouter support background mode + status polling? | One request with `background: true`; determines whether Phase 3+ long runs survive backgrounding without our own relay |
| S3 | Compose Multiplatform iOS: current stable guidance for IAP + notifications glue | Verify at Phase 2 planning against RevenueCat KMP SDK release notes |
| S4 | On-device bash sandbox shape on Android | Survey app-internal shell options (Termux-style, proot, busybox) for a per-app sandbox; approval-first UX unchanged |

## 10. Repository

- New repo: `~/projects/orangerabbit-io/clanker/` (this one).
- Trunk-based, PR-gated per global workflow; semantic-release from Phase 1 CI.
- clanker v1 repo remains untouched as reference.