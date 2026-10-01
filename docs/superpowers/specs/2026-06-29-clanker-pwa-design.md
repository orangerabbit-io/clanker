# clanker PWA — design

**Date:** 2026-06-29
**Status:** Draft
**Related:** `DESIGN.md` (original native Android architecture — reference only). This is a **greenfield PWA** in a new repository; the native app is unchanged and unsuperseded.

## 1. Goal

Rebuild clanker — a chat client for OpenRouter — as a progressive web app (PWA) that works on both Android and iOS. Zero backend server: all logic runs in the browser, all data is local, and the only external calls are directly to `api.openrouter.ai`. The original native Android app (Kotlin/Compose) is left intact as a reference; the PWA is a separate project in its own repository.

## 2. Scope

### In scope (v1)

- OpenRouter chat completions via SSE streaming (text, tool calls, web search citations)
- PWA install on Android (Chrome) and iOS (Safari) via `manifest.json` + service worker
- Conversation management: create, list, select, delete conversations
- API key management: user enters key once, stored in `localStorage`, loaded for each request
- Agent instructions: user-editable global `AGENTS.md` composed into the system prompt
- Model selection from OpenRouter's model catalogue
- Web search server tools (OpenRouter `openrouter:web_search` / `web_fetch` / `datetime`)
- Markdown rendering in assistant messages, rendered progressively during streaming
- Cyberpunk visual theme (dark backgrounds, neon cyan/magenta accents, scanline overlay, CRT effect, glitch text, cut-corner cards)
- Theme system designed for multiple themes (cyberpunk v1; floral pink/purple deferred)

### Out of scope (v1, explicitly)

- **Image generation** — deferred; the native app's image-output path is not ported yet
- **SSH tools** — deferred until the agent loop and tool registry are built
- **Server-side proxy or backend** — zero backend by design
- **Push notifications** — not PWA-standard on iOS, and not needed for an API-key-gated chat app
- **Encryption at rest** — the API key is stored in `localStorage` unencrypted; this is an accepted constraint of a browser-only app (the native app's Keystore/Tink path is not replicable without a backend).

### Future (post-v1)

- Floral pink/purple theme
- Image generation
- Conversation export/backup
- Progressive rendering improvements (syntax highlighting, LaTeX)

## 3. Architecture

```
                   Static hosting (Vite build output)
                   ┌─────────────────────────────────────────┐
                   │ /index.html     (SPA shell)             │
                   │ /sw.js           (Service Worker)       │
                   │ /assets/*        (JS, CSS, SurrealDB    │
                   │                   WASM binary)          │
                   │ /conversations/  (htmx fragments)       │
                   │ /settings/       (htmx fragments)       │
                   └──────┬──────────────────────────────────┘
                          │
            ┌─────────────┼─────────────┐
            ▼             ▼             ▼
       htmx (CRUD)   JS module    SurrealDB WASM
       navigation,    (SSE stream,  (conversations,
       forms, UI      markdown      messages, memory)
       interactivity)  rendering)
                          │
                          ▼
                   api.openrouter.ai
                   (Chat Completions API, SSE)
```

### Key architectural decisions

| Decision | Rationale |
|---|---|
| **No backend server** | Static hosting only. Every dynamic behavior lives in the browser or service worker. The API key never leaves the browser. |
| **htmx for CRUD + UI** | Navigation, forms, toggles, panels — everything that maps to a request/response cycle — is pure hypermedia. No JS framework needed. |
| **Small JS module for streaming** | The one thing htmx can't do cleanly without a server: consume an SSE stream from a remote API, parse raw tokens, render markdown progressively into a DOM element. ~100 lines. |
| **SurrealDB WASM (embedded)** | Multi-model database (documents, graph, relational) runs entirely in the browser via WASM. Handles conversations, messages, and future memory features. No network hop. |
| **localStorage for secrets** | API key and simple settings (selected model, FX intensity) in `localStorage`. Acceptably secure for a BYOK chat client with no multi-tenant data. |
| **Service worker for PWA, not API routing** | Caches app shell, handles install/offline, serves SurrealDB WASM binary. Does NOT intercept API calls or proxy SSE streams — keeping it simple avoids the lifetime and debugging problems of a SW-based request router. |

## 4. Routes & Views

```
/                  → Conversation list
/chat/{id}         → Active conversation
/settings          → Settings
```

All routes are static HTML served from the Vite build. htmx `hx-boost` on `<a>` tags and form submissions intercepts navigation and swaps the `<body>`, avoiding full page reloads.

### Conversation list (`/`)

- List of saved conversations, newest first (queried from SurrealDB)
- Each item shows conversation title, model name, last message preview, timestamp
- "New chat" button → creates a new conversation in SurrealDB → htmx navigates to `/chat/{id}`
- Deletion via htmx `hx-delete` with in-place removal
- Settings gear icon in header

### Active conversation (`/chat/{id}`)

```
┌─────────────────────────────────┐
│  ← Back    clanker    ⚙️        │
├─────────────────────────────────┤
│                                 │
│  ◉ System prompt loaded         │
│  You: rewrite this proposal     │
│  clanker: Here's a revised…     │
│    [🔗 openrouter.io]           │
│  You: now make it more...       │
│  clanker: Absolutely, here's…   │
│  ┌─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐   │
│  │ The key change is the...  │   │  ← streaming bubble
│  └─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘   │  (blinking cursor)
│                                 │
├─────────────────────────────────┤
│  [_________Type a message...__] │
│                    [Send] [■]  │  ← stop button during gen
└─────────────────────────────────┘
```

- Message list: scrollable container, bottom-anchored, each bubble is a static HTML element
- Assistant bubbles contain rendered markdown (from the streaming JS module)
- Web search citations render as tappable chips below the assistant bubble
- Input bar: text input + send button; during generation, send becomes a stop button (■)
- Stop button: cancels the active `fetch()` stream via `AbortController`

### Settings (`/settings`)

- API key: text input (password-masked), stored to `localStorage` on save
- Model selector: dropdown populated from OpenRouter `/api/v1/models` (fetched on settings load), stored in `localStorage`
- Agent instructions: multi-line textarea for `AGENTS.md`, stored in `localStorage`
- Theme: current theme indicator (v1: cyberpunk only; future: theme picker)
- FX intensity: slider for scanline/CRT effect strength, stored in `localStorage`

## 5. Data model

### SurrealDB schema

SurrealDB is used in document mode (no strict schema). Tables:

**`conversation`**:
```json
{
  "id": "conv_01j2abc...",
  "title": "Rewrite proposal",
  "model": "openrouter/deepseek/deepseek-v4-flash",
  "created": "2026-06-29T10:00:00Z",
  "updated": "2026-06-29T10:15:00Z"
}
```

**`message`**:
```json
{
  "id": "msg_01j2def...",
  "conversation": "conv_01j2abc...",
  "role": "user" | "assistant" | "system",
  "content": "Hello, world!",
  "tool_calls": [],
  "citations": [],
  "lifecycle": "complete" | "streaming" | "interrupted",
  "created": "2026-06-29T10:00:00Z"
}
```

Messages are ordered by `created`. The `lifecycle` field tracks the streaming state: `streaming` during an active turn, `complete` on finalization, `interrupted` if the user navigated away mid-stream.

### localStorage keys

- `api_key` — OpenRouter API key
- `selected_model` — model ID string
- `agent_md` — user's AGENTS.md content
- `fx_intensity` — 0.0 to 1.0, for scanline/CRT effect opacity

## 6. Data flow — sending a chat message

```
1. User types message, presses Enter/Send
   │
   ├─► htmx POST /conversations/{id}/messages
   │    → SurrealDB: INSERT INTO message (role:user, content, lifecycle:complete)
   │    → htmx renders user bubble into the message list
   │    → hx-trigger fires the JS streaming module
   │
   ├─► JS streaming module:
   │    2. Read API key + model from localStorage
   │    3. Read last N messages from SurrealDB (context window)
   │    4. Compose system prompt: SystemPrompt.TEXT + AGENTS.md
   │    5. fetch(api.openrouter.ai/v1/chat/completions) with ReadableStream
   │    6. Create assistant bubble element, mark lifecycle:streaming in SurrealDB
   │    7. For each SSE chunk:
   │       a. Parse delta (text, tool_call fragments)
   │       b. Append to accumulated text buffer
   │       c. Debounced (~100ms): marked() render → innerHTML
   │       d. Debounced persist to SurrealDB (don't write every delta)
   │    8. On stream end:
   │       a. Final markdown render
   │       b. Update SurrealDB: lifecycle:complete, final content, citations
   │       c. If tool_calls → htmx renders any web search citation chips
   │       d. Dispatch custom DOM event for htmx to re-enable input
   │
   └─► On abort (stop button):
        → AbortController.abort()
        → Update SurrealDB: lifecycle:interrupted
        → Remove the partial bubble
```

### Streaming markdown rendering

The accumulating message text is progressively rendered to HTML using `marked` (or a library of equivalent size). A debounced timer (~100ms) triggers re-render:

```text
Input:  "Here are the **key"
Output: "Here are the <strong>key</strong>"  ← partial bold, no close tag
                                        rendered gracefully by marked

Input:  "Here are the **key changes**"
Output: "Here are the <strong>key changes</strong>"  ← finalized
```

The renderer handles incomplete markdown gracefully — open `**`, `` ` `` , `[` produce incomplete but valid HTML until closed.

### OpenRouter SSE parsing

The JS module parses the standard SSE stream from `POST /v1/chat/completions`:

- Each `data: {"choices": [{"delta": {"content": "text"}}]}` → text delta
- Each `data: {"choices": [{"delta": {"tool_calls": [...]}}]}` → tool call delta
- Each `data: {"usage": {...}}` → final usage chunk
- `data: [DONE]` → stream end
- Tool call results (web search, fetch) arrive as `url_citation` annotations in the final usage chunk or alongside deltas

## 7. PWA

### Service worker (Serwist)

- **Install:** Pre-cache app shell (`index.html`, JS, CSS, SurrealDB WASM binary). The WASM binary is large (~5-10MB); cache it separately with its own cache key and a cache-first strategy.
- **Runtime:** No API call interception. The service worker only serves cached assets.
- **Offline:** The app shell (chat list, settings UI) works offline. Chatting requires connectivity — shown as a banner rather than a broken page.
- **Update:** Standard `skipWaiting` + `clients.claim` flow, with a "New version available" toast.

### Manifest (`manifest.json`)

```json
{
  "name": "clanker",
  "short_name": "clanker",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#0a0a0f",
  "theme_color": "#00f0ff",
  "icons": [
    { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png" },
    { "src": "/icons/icon-512-maskable.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
```

### iOS considerations

- iOS Safari supports PWA install since iOS 11.3 via "Add to Home Screen"
- No push notifications on iOS (W3C push not implemented in WebKit)
- `apple-mobile-web-app-capable` meta tag for standalone display
- Safe area insets (`env(safe-area-inset-*)`) for notch/island devices
- Touch events and `-webkit-overflow-scrolling` for smooth scroll on the message list

## 8. Theming

### v1: Cyberpunk (default)

Defined via TailwindCSS `@theme` with CSS custom properties:

```css
@theme {
  --color-bg-primary: #0a0a0f;
  --color-bg-surface: #12121a;
  --color-bg-card: #1a1a2e;
  --color-neon-cyan: #00f0ff;
  --color-neon-magenta: #ff00aa;
  --color-text-primary: #e0e0f0;
  --color-text-secondary: #8888aa;
}
```

Visual effects (separate `cyberpunk-effects.css`, loaded with the cyberpunk theme):

- **Scanline overlay:** repeating linear-gradient pseudo-element, 50% transparent rows, ~2px pitch
- **CRT screen effect:** CSS `filter` composite on the main container — slight blur + contrast boost, inspired by the native app's AGSL shader
- **Glitch text:** `@keyframes` clip-path animation on headings, triggered by a data attribute
- **Cut-corner cards:** `clip-path: polygon(...)` on cards and buttons
- **Font:** monospace system font stack (the native app uses a monospace-first design)

### Future themes

The theme system is CSS-only. Adding a theme means:

1. A new palette definition (e.g. `floral.css` with pastel pinks `#ffb6c1`, purples `#dda0dd`, warm whites)
2. Optional effects overrides (floral theme would likely skip the CRT/scanline effects)
3. htmx swaps the theme `<link>` or swaps `[data-theme]` attribute on `<html>`
4. The JS streaming module and all htmx routes are theme-agnostic

No architectural changes needed. The theme selector in Settings stores the choice in `localStorage` and applies it on load.

## 9. Error handling

| Failure | Behaviour |
|---|---|
| No API key set | Settings page shown on first visit; chat input disabled without key; user redirected to settings with prompt |
| OpenRouter returns 401 (bad key) | SSE stream delivers an error delta; render as a system error message in the chat; do not retry |
| OpenRouter returns 429 (rate limited) | Surface error message with "retry later"; model is already instructed to be concise |
| OpenRouter returns 402 (insufficient credits) | Surface the exact error; the SSE decoder already passes it through |
| Network disconnected | Service worker serves cached app shell; chat sends fail with a "no connection" toast; ongoing stream fails via `AbortController` timeout |
| SurrealDB WASM fails to load | Show error message on app boot; chat is non-functional; offer reload |
| Service worker update available | "New version available" toast; user triggers skipWaiting + reload |
| Stream interrupted (user navigates away) | Current assistant message marked `interrupted` in SurrealDB; stream cancelled via AbortController; no orphan data |

## 10. Performance considerations

- **Streaming rendering:** Debounced markdown re-render at ~100ms intervals. Force-emit final render on stream end (debounce swallows the last frame).
- **SurrealDB WASM binary:** Cache aggressively in the service worker (~5-10MB download on first load). The binary is loaded on app boot, not on first chat.
- **Message list:** Keep messages as DOM elements (not re-rendered). New messages append. The streaming module updates only the active bubble's `innerHTML`.
- **Image icons:** PWA icons at standard sizes (192, 512) — no full-resolution images.
- **Bundle size target:** Under 50kB gzip for JS (excluding SurrealDB WASM). The custom streaming module is ~2kB, htmx ~10kB, marked ~5kB, Tailwind purged CSS ~10kB.

## 11. Dependencies

| Package | Version (tracking) | Purpose |
|---|---|---|
| htmx | 2.x | Hypermedia-driven navigation, forms, UI |
| marked | current | Client-side markdown → HTML |
| surrealdb.js | current (WASM) | Embedded database via WASM |
| serwist | current | Service worker generation, caching strategies |
| vite | 6.x | Build tool |
| tailwindcss | 4.x | Utility-first CSS |
| typescript | current | Type safety |

Zero proprietary dependencies. All packages are MIT or similarly permissive open-source licenses.

## 12. Stack decisions log

| Decision | Rationale | Alternative considered |
|---|---|---|
| **htmx over React/Solid** | Hypermedia for CRUD is simpler and more resilient. Custom streaming JS handles the one non-hypermedia part. | React (too heavy for this scale), Solid (would mean full SPA framework for a mostly hypermedia app) |
| **No backend server** | Static hosting = zero operational cost, no server to secure, no proxy to debug | Bun/Hono proxy (simpler streaming but adds ops burden and API key exposure surface) |
| **SurrealDB WASM over IndexedDB** | Multi-model (documents + graph) for future memory/agent features. One intentional dependency instead of manual IndexedDB. | Raw IndexedDB (more control, more boilerplate), Dexie (simpler than raw but still relational, no graph) |
| **localStorage for API key** | Minimal complexity. Key is entered by the user and never shared with any server. Acceptable tradeoff for a single-user BYOK app. | Tink/Keystore (not possible in browser), session-only entry (worse UX) |
| **Custom JS module for streaming** | htmx SSE extension needs an EventSource producer; without a backend, the service worker would have to do it, which is fragile and hard to debug. | SSE through service worker (fragile, hard to debug), htmx polling (wrong pattern for streaming) |
| **Serwist for SW generation** | Vite plugin, handles precaching, runtime caching, and update flow declaratively. | vite-plugin-pwa (same capability, slightly less maintained) |
| **Theme system via CSS only** | No JS framework needed. Themes are just CSS files with custom properties. Adding a theme is a CSS file + a localStorage key. | CSS-in-JS (adds JS dependency for something CSS handles natively) |

## 13. Data flow (summary)

```
User sends message:
  htmx POST → SurrealDB (user msg) → render bubble
    → hx-trigger → JS stream module
      → fetch(api.openrouter.ai) SSE
        → per delta: accumulate text → debounced marked render → innerHTML
        → per delta: debounced SurrealDB persist
      → stream end: final render → SurrealDB (complete) → citation chips → htmx event

Navigate away mid-stream:
  AbortController.abort() → SurrealDB (interrupted) → discard partial bubble

Settings change:
  htmx POST → localStorage write → htmx confirms
```