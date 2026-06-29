# Clanker PWA Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a progressive web app for OpenRouter chat, zero backend, works on Android and iOS.

**Architecture:** Static SPA served from CDN. htmx handles all CRUD and UI interactivity. A small JS module (~100 lines) manages the OpenRouter SSE streaming connection with progressive markdown rendering. SurrealDB WASM stores conversations and messages locally in the browser. Service worker caches app shell for PWA install and offline support.

**Tech Stack:** htmx 2.x, TailwindCSS 4.x, TypeScript, Vite 6.x, Serwist (SW), SurrealDB WASM, marked (markdown), Vite

**Spec:** `docs/superpowers/specs/2026-06-29-clanker-pwa-design.md`

---

## File Map

```
clanker-pwa/
├── public/
│   ├── icons/
│   │   ├── icon-192.png
│   │   ├── icon-512.png
│   │   └── icon-512-maskable.png
│   └── manifest.json
├── src/
│   ├── styles/
│   │   ├── app.css                  # Tailwind entry + @theme config
│   │   └── cyberpunk-effects.css    # Scanlines, CRT, glitch, cut-corners
│   ├── lib/
│   │   ├── db.ts                    # SurrealDB init + CRUD for conversations/messages
│   │   ├── settings.ts              # localStorage helpers (api key, model, etc.)
│   │   ├── openrouter.ts            # SSE streaming client
│   │   ├── openrouter-types.ts      # TypeScript types for OpenRouter API
│   │   ├── stream-bubble.ts         # DOM management for the streaming message bubble
│   │   └── system-prompt.ts         # SystemPrompt.TEXT + composeSystemPrompt
│   ├── routes/
│   │   ├── chat-list.html.ts        # htmx template for conversation list
│   │   ├── chat.html.ts             # htmx template for active chat
│   │   └── settings.html.ts         # htmx template for settings
│   ├── app.ts                       # App init: SurrealDB boot, theme apply, error UI
│   └── sw.ts                        # Serwist service worker config
├── index.html                       # SPA shell
├── package.json
├── tsconfig.json
├── vite.config.ts
├── tailwind.config.ts               # (v4 uses CSS config instead)
└── postcss.config.js
```

---

## Chunk 1: Foundation — Project Scaffold, PWA Shell, Tailwind Theme

### Task 1.1: Scaffold Vite project

**Files:**
- Create: `package.json`
- Create: `tsconfig.json`
- Create: `vite.config.ts`
- Create: `postcss.config.js`

- [ ] **Step 1: Create package.json**

```json
{
  "name": "clanker-pwa",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "htmx.org": "^2.0.4",
    "marked": "^15.0.0",
    "surrealdb.js": "^1.1.0",
    "serwist": "^9.0.0",
    "@serwist/vite": "^9.0.0",
    "@serwist/window": "^9.0.0"
  },
  "devDependencies": {
    "typescript": "^5.7.0",
    "vite": "^6.0.0",
    "tailwindcss": "^4.0.0",
    "@tailwindcss/vite": "^4.0.0"
  }
}
```

- [ ] **Step 2: Create tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "isolatedModules": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "allowImportingTsExtensions": true,
    "noEmit": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable", "WebWorker"]
  },
  "include": ["src"]
}
```

- [ ] **Step 3: Create vite.config.ts**

```typescript
import { defineConfig } from "vite";
import tailwindcss from "@tailwindcss/vite";
import { serwist } from "@serwist/vite";

export default defineConfig({
  plugins: [
    tailwindcss(),
    serwist({
      swSrc: "src/sw.ts",
      swDest: "sw.js",
      globDirectory: "dist",
      globPatterns: ["**/*.{html,js,css,png,svg,woff2}"],
      // SurrealDB WASM is cached separately with its own strategy
      globIgnores: ["**/surrealdb.wasm"],
    }),
  ],
});
```

- [ ] **Step 4: Create postcss.config.js**

```javascript
export default {
  plugins: {
    "@tailwindcss/postcss": {},
  },
};
```

- [ ] **Step 5: Commit**

```bash
git init
git add package.json tsconfig.json vite.config.ts postcss.config.js
git commit -m "chore: scaffold Vite project with htmx, Tailwind, Serwist, SurrealDB"
```

---

### Task 1.2: PWA manifest and icons

**Files:**
- Create: `public/manifest.json`
- Create placeholder: `public/icons/` directory

- [ ] **Step 1: Create manifest.json**

```json
{
  "name": "clanker",
  "short_name": "clanker",
  "description": "OpenRouter chat client",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#0a0a0f",
  "theme_color": "#00f0ff",
  "orientation": "portrait",
  "icons": [
    {
      "src": "/icons/icon-192.png",
      "sizes": "192x192",
      "type": "image/png"
    },
    {
      "src": "/icons/icon-512.png",
      "sizes": "512x512",
      "type": "image/png"
    },
    {
      "src": "/icons/icon-512-maskable.png",
      "sizes": "512x512",
      "type": "image/png",
      "purpose": "maskable"
    }
  ]
}
```

- [ ] **Step 2: Generate PWA icons**

Run: `nix-shell -p imagemagick --command 'mkdir -p public/icons && convert -size 192x192 xc:"#0a0a0f" -font Courier -pointsize 48 -fill "#00f0ff" -gravity center -annotate +0+0 "CL" public/icons/icon-192.png && convert -size 512x512 xc:"#0a0a0f" -font Courier -pointsize 128 -fill "#00f0ff" -gravity center -annotate +0+0 "CL" public/icons/icon-512.png && cp public/icons/icon-512.png public/icons/icon-512-maskable.png'`

- [ ] **Step 3: Commit**

```bash
git add public/
git commit -m "feat: add PWA manifest and app icons"
```

---

### Task 1.3: Index.html shell

**Files:**
- Create: `index.html`

- [ ] **Step 1: Create index.html**

```html
<!DOCTYPE html>
<html lang="en" data-theme="cyberpunk">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
  <meta name="theme-color" content="#00f0ff" />
  <meta name="apple-mobile-web-app-capable" content="yes" />
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
  <meta name="apple-mobile-web-app-title" content="clanker" />
  <link rel="manifest" href="/manifest.json" />
  <link rel="apple-touch-icon" href="/icons/icon-192.png" />
  <link rel="preconnect" href="https://api.openrouter.ai" />
  <title>clanker</title>
  <script type="module" src="/src/app.ts"></script>
  <script
    src="https://unpkg.com/htmx.org@2.0.4"
    integrity="sha384-R9T3ob3MLRqsJDvCrzU0F0iD4KG/4GT0K6aqoB8IZoLPJZLxY+H2eGYdAf0PH8R"
    crossorigin="anonymous"
    defer></script>
  <style>
    /* CRT screen effect on the root */
    body {
      margin: 0;
      padding: 0;
      background: #0a0a0f;
      color: #e0e0f0;
      font-family: "Courier New", "Consolas", "Menlo", monospace;
      filter: contrast(1.05) brightness(0.95);
    }
  </style>
</head>
<body hx-boost="true" hx-target="#app-shell" hx-select="#app-shell" hx-swap="outerHTML">
  <div id="app-shell">
    <!-- Content swapped by htmx routes -->
  </div>
  <!-- Scanline overlay -->
  <div id="scanline-overlay" aria-hidden="true"></div>
  <!-- Offline banner (hidden by default) -->
  <div id="offline-banner" class="hidden">No connection — some features unavailable</div>
</body>
</html>
```

Note: The htmx script tag uses a CDN URL. For production builds this is bundled; for dev, unpkg is fine. The Serwist service worker registration is handled by `@serwist/window` in `app.ts`.

- [ ] **Step 2: Commit**

```bash
git add index.html
git commit -m "feat: add SPA shell with htmx boost, meta tags, CRT filter"
```

---

## Chunk 2: Theme — Tailwind Config and Cyberpunk Effects

### Task 2.1: Tailwind CSS theme config

**Files:**
- Create: `src/styles/app.css`

- [ ] **Step 1: Create app.css with Tailwind theme + base styles**

```css
@import "tailwindcss";

@theme {
  /* Cyberpunk palette */
  --color-bg-primary: #0a0a0f;
  --color-bg-surface: #12121a;
  --color-bg-card: #1a1a2e;
  --color-bg-elevated: #22223a;
  --color-border-default: #2a2a44;
  --color-neon-cyan: #00f0ff;
  --color-neon-magenta: #ff00aa;
  --color-neon-green: #00ff88;
  --color-text-primary: #e0e0f0;
  --color-text-secondary: #8888aa;
  --color-text-muted: #55557a;
  --color-error: #ff4477;
  --color-success: #00ff88;
  --color-warning: #ffaa00;

  /* Typography */
  --font-mono: "Courier New", "Consolas", "Menlo", monospace;
}

/* Base layer: apply to body and common elements */
@layer base {
  body {
    background-color: var(--color-bg-primary);
    color: var(--color-text-primary);
    font-family: var(--font-mono);
    -webkit-font-smoothing: antialiased;
    overflow: hidden;
    height: 100dvh;
    width: 100dvw;
  }

  /* Scrollbar styling */
  ::-webkit-scrollbar {
    width: 6px;
  }
  ::-webkit-scrollbar-track {
    background: var(--color-bg-primary);
  }
  ::-webkit-scrollbar-thumb {
    background: var(--color-border-default);
    border-radius: 3px;
  }

  /* Safe area insets for iOS notch/island */
  .safe-top {
    padding-top: env(safe-area-inset-top, 0px);
  }
  .safe-bottom {
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
}

@layer components {
  /* Cut-corner card */
  .cut-card {
    background: var(--color-bg-card);
    border: 1px solid var(--color-border-default);
    clip-path: polygon(
      0% 8px,
      8px 0%,
      calc(100% - 8px) 0%,
      100% 8px,
      100% calc(100% - 8px),
      calc(100% - 8px) 100%,
      8px 100%,
      0% calc(100% - 8px)
    );
    padding: 16px;
  }

  /* Cut-corner button */
  .cut-btn {
    clip-path: polygon(
      0% 4px,
      4px 0%,
      calc(100% - 4px) 0%,
      100% 4px,
      100% calc(100% - 4px),
      calc(100% - 4px) 100%,
      4px 100%,
      0% calc(100% - 4px)
    );
    padding: 8px 16px;
    font-family: var(--font-mono);
    font-weight: bold;
    cursor: pointer;
    transition: opacity 0.15s;
  }
  .cut-btn:hover {
    opacity: 0.8;
  }
  .cut-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }

  .cut-btn-primary {
    background: var(--color-neon-cyan);
    color: var(--color-bg-primary);
    border: none;
  }

  .cut-btn-danger {
    background: var(--color-error);
    color: var(--color-bg-primary);
    border: none;
  }

  /* Neon text glow */
  .neon-cyan {
    color: var(--color-neon-cyan);
    text-shadow: 0 0 8px var(--color-neon-cyan), 0 0 16px color-mix(in srgb, var(--color-neon-cyan) 50%, transparent);
  }
  .neon-magenta {
    color: var(--color-neon-magenta);
    text-shadow: 0 0 8px var(--color-neon-magenta), 0 0 16px color-mix(in srgb, var(--color-neon-magenta) 50%, transparent);
  }

  /* Inline code and code blocks in markdown */
  .prose code {
    background: var(--color-bg-elevated);
    padding: 2px 6px;
    border-radius: 3px;
    font-size: 0.9em;
  }
  .prose pre {
    background: var(--color-bg-elevated);
    border: 1px solid var(--color-border-default);
    border-radius: 4px;
    padding: 12px;
    overflow-x: auto;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/styles/app.css
git commit -m "feat: add Tailwind CSS theme with cyberpunk palette and components"
```

---

### Task 2.2: Cyberpunk effects CSS

**Files:**
- Create: `src/styles/cyberpunk-effects.css`

- [ ] **Step 1: Create effects stylesheet**

```css
/* Scanline overlay — fixed pseudo-element over the entire viewport */
#scanline-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100dvh;
  pointer-events: none;
  z-index: 9999;
  background: repeating-linear-gradient(
    0deg,
    transparent,
    transparent 1px,
    rgba(0, 0, 0, 0.08) 1px,
    rgba(0, 0, 0, 0.08) 2px
  );
  transition: opacity 0.3s;
}

/* Intensity modifier class — applied from settings */
[data-fx-intensity="0"] #scanline-overlay { opacity: 0; }
[data-fx-intensity="0.25"] #scanline-overlay { opacity: 0.25; }
[data-fx-intensity="0.5"] #scanline-overlay { opacity: 0.5; }
[data-fx-intensity="0.75"] #scanline-overlay { opacity: 0.75; }
[data-fx-intensity="1"] #scanline-overlay { opacity: 1; }

/* Glitch text animation — used on the clanker header */
@keyframes glitch {
  0% { clip-path: inset(0 0 80% 0); transform: translate(-1px, 0); }
  10% { clip-path: inset(20% 0 60% 0); transform: translate(1px, 0); }
  20% { clip-path: inset(40% 0 40% 0); transform: translate(-1px, 0); }
  30% { clip-path: inset(60% 0 20% 0); transform: translate(2px, 0); }
  40% { clip-path: inset(80% 0 0% 0); transform: translate(-2px, 0); }
  50% { clip-path: inset(0 0 80% 0); transform: translate(0, 0); }
  100% { clip-path: inset(0 0 80% 0); transform: translate(0, 0); }
}

[data-glitch] {
  position: relative;
  animation: glitch 0.3s infinite;
}

[data-glitch]::before,
[data-glitch]::after {
  content: attr(data-glitch);
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  opacity: 0.3;
}

[data-glitch]::before {
  color: var(--color-neon-magenta);
  clip-path: inset(20% 0 40% 0);
  transform: translate(-2px, 0);
  animation: glitch 0.2s infinite reverse;
}

[data-glitch]::after {
  color: var(--color-neon-cyan);
  clip-path: inset(60% 0 10% 0);
  transform: translate(2px, 0);
  animation: glitch 0.25s infinite;
}

/* Offline banner */
#offline-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  background: var(--color-warning);
  color: var(--color-bg-primary);
  text-align: center;
  padding: 6px;
  font-size: 12px;
  z-index: 10000;
  font-weight: bold;
}
#offline-banner.hidden { display: none; }

/* Stream cursor — blinking underscore at end of in-progress message */
.stream-cursor::after {
  content: "_";
  animation: blink 1s step-end infinite;
  color: var(--color-neon-cyan);
}
@keyframes blink {
  50% { opacity: 0; }
}

/* Message bubbles */
.msg-bubble-user {
  background: var(--color-bg-card);
  border: 1px solid var(--color-neon-magenta);
}

.msg-bubble-assistant {
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-default);
}

.msg-bubble-system {
  background: transparent;
  color: var(--color-text-muted);
  font-style: italic;
  font-size: 0.85em;
}

/* Citation chip */
.citation-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border-default);
  border-radius: 4px;
  font-size: 11px;
  color: var(--color-text-secondary);
  text-decoration: none;
  cursor: pointer;
  transition: border-color 0.15s;
}
.citation-chip:hover {
  border-color: var(--color-neon-cyan);
  color: var(--color-neon-cyan);
}
.citation-chip::before {
  content: "🔗";
  font-size: 10px;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/styles/cyberpunk-effects.css
git commit -m "feat: add cyberpunk visual effects — scanlines, glitch, CRT, citations"
```

---

## Chunk 3: Foundation Services

### Task 3.1: Service worker (Serwist)

**Files:**
- Create: `src/sw.ts`

- [ ] **Step 1: Create service worker source**

```typescript
/// <reference lib="webworker" />
import { defaultCache } from "@serwist/vite/browser";

declare const self: ServiceWorkerGlobalScope;

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(clients.claim());
});

// Default Serwist cache strategies:
// - App shell (HTML, JS, CSS): cache-first
// - SurrealDB WASM: cache-first (large binary, don't re-download)
// - OpenRouter API calls: never cached (network-only, not intercepted at all)
// - Everything else: network-first
const cache = await import("@serwist/vite/browser");
cache.registerSerwist({
  runtimeCaching: defaultCache,
});
```

- [ ] **Step 2: Commit**

```bash
git add src/sw.ts
git commit -m "feat: add service worker with Serwist for PWA caching"
```

---

### Task 3.2: App bootstrapper

**Files:**
- Create: `src/app.ts`

- [ ] **Step 1: Create app.ts**

```typescript
import "./styles/app.css";
import "./styles/cyberpunk-effects.css";
import { initDb, isDbReady } from "./lib/db";
import { getFxIntensity } from "./lib/settings";

async function main() {
  // Load SurrealDB WASM (async — app shows loading state until ready)
  try {
    await initDb();
  } catch (err) {
    console.error("SurrealDB init failed:", err);
    document.getElementById("app-shell")!.innerHTML = `
      <div class="flex flex-col items-center justify-center h-dvh p-8 text-center">
        <h1 class="neon-magenta text-2xl mb-4">clanker</h1>
        <p class="text-text-secondary mb-2">Failed to initialize local database</p>
        <p class="text-text-muted text-sm">Try refreshing the page</p>
        <button class="cut-btn cut-btn-primary mt-6" onclick="location.reload()">Reload</button>
      </div>`;
    return;
  }

  // Apply FX intensity from settings
  const fx = getFxIntensity();
  document.documentElement.setAttribute("data-fx-intensity", String(fx));

  // Apply saved theme
  const theme = localStorage.getItem("theme") || "cyberpunk";
  document.documentElement.setAttribute("data-theme", theme);

  // Register Serwist for service worker (injected by Vite plugin)
  const { Serwist } = await import("@serwist/window");
  const serwist = new Serwist("/sw.js");
  serwist.addEventListener("installed", () => {
    // "New version available" toast
    const toast = document.createElement("div");
    toast.className = "fixed bottom-20 left-4 right-4 cut-card text-center z-50";
    toast.innerHTML = `
      <p class="text-sm mb-2">New version available</p>
      <button class="cut-btn cut-btn-primary text-sm" onclick="location.reload()">Update</button>`;
    document.body.appendChild(toast);
  });
  serwist.register();

  // Listen for network status changes
  window.addEventListener("online", () => {
    document.getElementById("offline-banner")?.classList.add("hidden");
  });
  window.addEventListener("offline", () => {
    document.getElementById("offline-banner")?.classList.remove("hidden");
  });
  // Initial check
  if (!navigator.onLine) {
    document.getElementById("offline-banner")?.classList.remove("hidden");
  }

  // Check for API key — redirect to settings if missing
  const apiKey = localStorage.getItem("api_key");
  if (!apiKey) {
    // Load settings route
    await loadRoute("/settings");
    return;
  }

  // Load default route (conversation list)
  await loadRoute("/");
}

/**
 * htmx-compatible route loader: fetch the route's HTML fragment and swap it in.
 * Because htmx's hx-boost handles subsequent navigations, this is only needed
 * for the initial load and for programmatic navigation.
 */
async function loadRoute(path: string) {
  try {
    const resp = await fetch(path, { headers: { "HX-Request": "true" } });
    const html = await resp.text();
    const shell = document.getElementById("app-shell");
    if (shell) shell.innerHTML = html;
  } catch (err) {
    console.error("Route load failed:", path, err);
  }
}

// Wire up htmx event listeners for streaming
document.addEventListener("htmx:afterRequest", (evt) => {
  // After a user message is saved, start the OpenRouter stream
  const detail = (evt as CustomEvent).detail;
  if (detail?.pathInfo?.requestPath?.endsWith("/messages")) {
    // The response includes meta attributes that the streaming module picks up
    document.dispatchEvent(new CustomEvent("clanker:start-stream", {
      detail: { conversationId: detail.pathInfo.requestPath.split("/")[2] },
    }));
  }
});

document.addEventListener("htmx:beforeSwap", (evt) => {
  // Ensure the right theme classes are applied after htmx swaps content
  const theme = localStorage.getItem("theme") || "cyberpunk";
  document.documentElement.setAttribute("data-theme", theme);
});

main();
```

- [ ] **Step 2: Commit**

```bash
git add src/app.ts
git commit -m "feat: add app bootstrapper with SurrealDB init, theme apply, SW registration"
```

---

## Chunk 4: Data Layer — SurrealDB and Settings

### Task 4.1: Settings helpers (localStorage)

**Files:**
- Create: `src/lib/settings.ts`

- [ ] **Step 1: Create settings.ts**

```typescript
// localStorage keys
export const KEYS = {
  API_KEY: "api_key",
  SELECTED_MODEL: "selected_model",
  AGENT_MD: "agent_md",
  FX_INTENSITY: "fx_intensity",
  THEME: "theme",
} as const;

// Default model for first-time users
const DEFAULT_MODEL = "openrouter/deepseek/deepseek-v4-flash";

export function getApiKey(): string | null {
  return localStorage.getItem(KEYS.API_KEY);
}

export function setApiKey(key: string): void {
  localStorage.setItem(KEYS.API_KEY, key);
}

export function clearApiKey(): void {
  localStorage.removeItem(KEYS.API_KEY);
}

export function getSelectedModel(): string {
  return localStorage.getItem(KEYS.SELECTED_MODEL) || DEFAULT_MODEL;
}

export function setSelectedModel(model: string): void {
  localStorage.setItem(KEYS.SELECTED_MODEL, model);
}

export function getAgentMd(): string {
  return localStorage.getItem(KEYS.AGENT_MD) || "";
}

export function setAgentMd(content: string): void {
  localStorage.setItem(KEYS.AGENT_MD, content);
}

export function getFxIntensity(): number {
  const v = localStorage.getItem(KEYS.FX_INTENSITY);
  if (v === null) return 0.5; // default 50%
  const n = parseFloat(v);
  return isNaN(n) ? 0.5 : Math.max(0, Math.min(1, n));
}

export function setFxIntensity(val: number): void {
  localStorage.setItem(KEYS.FX_INTENSITY, String(Math.max(0, Math.min(1, val))));
}

export function getTheme(): string {
  return localStorage.getItem(KEYS.THEME) || "cyberpunk";
}

export function setTheme(theme: string): void {
  localStorage.setItem(KEYS.THEME, theme);
  document.documentElement.setAttribute("data-theme", theme);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/settings.ts
git commit -m "feat: add localStorage settings helpers for API key, model, theme"
```

---

### Task 4.2: SurrealDB — database init and schema

**Files:**
- Create: `src/lib/db.ts`

- [ ] **Step 1: Create db.ts**

```typescript
import Surreal from "surrealdb.js";
import { getApiKey, getSelectedModel, getAgentMd } from "./settings";

let db: Surreal | null = null;
let dbReady = false;

export function isDbReady(): boolean {
  return dbReady;
}

export async function initDb(): Promise<void> {
  if (dbReady) return;

  db = new Surreal();

  // SurrealDB WASM — initialize in-memory (survives only for session)
  // For persistence across page loads, SurrealDB WASM syncs to IndexedDB automatically
  await db.connect("indxdb://clanker");
  await db.use({ namespace: "clanker", database: "clanker" });

  // Define tables via schema creation — document mode, no strict schema
  // But we run a DEFINE TABLE to ensure they exist
  try {
    await db.query(`
      DEFINE TABLE conversation SCHEMALESS;
      DEFINE TABLE message SCHEMALESS;
    `);
  } catch {
    // Tables already exist — safe to ignore
  }

  dbReady = true;
}

// ── Conversation CRUD ────────────────────────────────────────

export interface Conversation {
  id: string;
  title: string;
  model: string;
  created: string;
  updated: string;
}

export interface Message {
  id: string;
  conversation: string;
  role: "user" | "assistant" | "system" | "tool";
  content: string | null;
  tool_calls?: Array<{ id: string; name: string; arguments: string }>;
  citations?: Array<{ url: string; title?: string; content?: string }>;
  lifecycle: "complete" | "streaming" | "interrupted";
  created: string;
}

export async function createConversation(model?: string): Promise<string> {
  const now = new Date().toISOString();
  const id = crypto.randomUUID();
  await db!.create("conversation", {
    id,
    title: "New conversation",
    model: model || getSelectedModel(),
    created: now,
    updated: now,
  });
  return id;
}

export async function listConversations(): Promise<Conversation[]> {
  const result = await db!.select("conversation");
  // Sort by updated descending, limit to 50
  return (result as Conversation[])
    .sort((a, b) => new Date(b.updated).getTime() - new Date(a.updated).getTime())
    .slice(0, 50);
}

export async function getConversation(id: string): Promise<Conversation | null> {
  const result = await db!.select(`conversation:${id}`);
  return (result as Conversation | null);
}

export async function updateConversationTitle(id: string, title: string): Promise<void> {
  await db!.merge(`conversation:${id}`, { title, updated: new Date().toISOString() });
}

export async function deleteConversation(id: string): Promise<void> {
  // Delete all messages in the conversation first
  const messages = await db!.query(`SELECT id FROM message WHERE conversation = "${id}"`);
  for (const msg of (messages as any[]) || []) {
    await db!.delete(`message:${msg.id}`);
  }
  await db!.delete(`conversation:${id}`);
}

// ── Message CRUD ─────────────────────────────────────────────

export async function createMessage(msg: Omit<Message, "id" | "created">): Promise<string> {
  const id = crypto.randomUUID();
  await db!.create("message", {
    id,
    ...msg,
    created: new Date().toISOString(),
  });
  return id;
}

export async function updateMessage(
  id: string,
  updates: Partial<Pick<Message, "content" | "lifecycle" | "citations" | "tool_calls">>
): Promise<void> {
  await db!.merge(`message:${id}`, updates);
}

export async function getMessages(conversationId: string): Promise<Message[]> {
  const result = await db!.query(
    `SELECT * FROM message WHERE conversation = "${conversationId}" ORDER BY created ASC`
  );
  // surrealdb.js returns query results as an array of arrays
  const rows = result[0] as any[];
  return (rows || []) as Message[];
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/db.ts
git commit -m "feat: add SurrealDB WASM init, conversation and message CRUD"
```

---

## Chunk 5: Routes — HTML Templates

### Task 5.1: Conversation list route

**Files:**
- Create: `src/routes/chat-list.html.ts`

- [ ] **Step 1: Create chat-list template function**

```typescript
/**
 * Renders the conversation list view.
 * Called by htmx on initial load and navigation to "/".
 * Returns HTML string that htmx swaps into #app-shell.
 */

export interface ChatListEntry {
  id: string;
  title: string;
  model: string;
  updated: string;
  lastPreview?: string;
}

export function renderChatList(conversations: ChatListEntry[]): string {
  const items = conversations.length
    ? conversations.map(conv => `
      <a href="/chat/${conv.id}" class="cut-card mb-3 block hover:border-neon-cyan transition-colors no-underline">
        <div class="flex justify-between items-start">
          <h3 class="neon-cyan text-sm font-bold truncate flex-1">${escapeHtml(conv.title)}</h3>
          <span class="text-text-muted text-xs shrink-0 ml-2">${formatTime(conv.updated)}</span>
        </div>
        <p class="text-text-muted text-xs mt-1">
          ${escapeHtml(conv.model)}
          ${conv.lastPreview ? `<span class="ml-2">${escapeHtml(conv.lastPreview.slice(0, 60))}</span>` : ""}
        </p>
      </a>`).join("\n")
    : `<p class="text-text-muted text-center mt-12">No conversations yet. Start one below.</p>`;

  return `
    <div class="flex flex-col h-dvh safe-top safe-bottom">
      <!-- Header -->
      <header class="flex items-center justify-between px-4 py-3 border-b border-border-default">
        <h1 class="neon-cyan text-lg font-bold" data-glitch="clanker">clanker</h1>
        <a href="/settings" class="text-text-secondary hover:text-neon-cyan transition-colors text-lg">⚙</a>
      </header>

      <!-- Conversation list -->
      <div class="flex-1 overflow-y-auto p-4" id="chat-list" hx-target="this" hx-select="#chat-list" hx-swap="outerHTML">
        ${items}
      </div>

      <!-- New chat button -->
      <div class="p-4 border-t border-border-default">
        <button
          class="cut-btn cut-btn-primary w-full text-sm"
          hx-post="/api/conversations"
          hx-target="#app-shell"
          hx-swap="outerHTML"
          hx-select="#app-shell"
        >
          + New conversation
        </button>
      </div>
    </div>`;
}

function escapeHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  if (diffMins < 1) return "just now";
  if (diffMins < 60) return `${diffMins}m ago`;
  const diffHours = Math.floor(diffMins / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 7) return `${diffDays}d ago`;
  return d.toLocaleDateString();
}
```

This template function is called from htmx route handlers that we'll wire up in the server-side-like routing layer. For static hosting, these are served as HTML fragments. The Vite dev server serves them as API-like endpoints via a small routing helper.

For the static-site model, we use a Vite dev server plugin or a small route handler inside the app that intercepts htmx requests and returns rendered HTML. In practice with htmx + static hosting, routes map to pre-built HTML files. For v1, use a small `<script>` in `app.ts` that intercepts htmx requests and returns the rendered template — this keeps the single-page model working without a backend.

The practical approach: since we have no server, we register the htmx route handlers in `app.ts` using `htmx.on("htmx:beforeRequest", ...)` or by implementing the routes directly in `app.ts`. This is the standard pattern for htmx-on-static-hosting.

- [ ] **Step 2: Commit**

```bash
git add src/routes/chat-list.html.ts
git commit -m "feat: add conversation list route template"
```

---

### Task 5.2: Settings route

**Files:**
- Create: `src/routes/settings.html.ts`

- [ ] **Step 1: Create settings template**

```typescript
import { getApiKey, getSelectedModel, getAgentMd, getFxIntensity, getTheme } from "../lib/settings";

export function renderSettings(): string {
  const apiKey = getApiKey() || "";
  const model = getSelectedModel();
  const agentMd = getAgentMd() || "";
  const fxIntensity = getFxIntensity();
  const theme = getTheme();

  return `
    <div class="flex flex-col h-dvh safe-top safe-bottom">
      <!-- Header -->
      <header class="flex items-center px-4 py-3 border-b border-border-default">
        <a href="/" class="text-text-secondary hover:text-neon-cyan transition-colors mr-3">←</a>
        <h1 class="neon-cyan text-lg font-bold">settings</h1>
      </header>

      <!-- Settings content -->
      <div class="flex-1 overflow-y-auto p-4 space-y-6">

        <!-- API Key -->
        <section class="cut-card">
          <h2 class="text-sm font-bold text-text-secondary mb-2">API Key</h2>
          <input
            type="password"
            name="api_key"
            value="${escapeAttr(apiKey)}"
            placeholder="sk-or-..."
            class="w-full bg-bg-surface border border-border-default text-text-primary p-2 text-sm font-mono rounded-none outline-none focus:border-neon-cyan transition-colors"
          />
          <p class="text-text-muted text-xs mt-1">OpenRouter API key. Never shared. Stored locally.</p>
          <button
            class="cut-btn cut-btn-primary text-sm mt-2"
            hx-post="/api/settings/api-key"
            hx-include="[name=api_key]"
          >Save</button>
          <button
            class="cut-btn cut-btn-danger text-sm mt-2 ml-2"
            hx-post="/api/settings/api-key/clear"
          >Clear</button>
        </section>

        <!-- Model -->
        <section class="cut-card">
          <h2 class="text-sm font-bold text-text-secondary mb-2">Model</h2>
          <select
            name="model"
            class="w-full bg-bg-surface border border-border-default text-text-primary p-2 text-sm font-mono rounded-none outline-none focus:border-neon-cyan transition-colors"
            hx-post="/api/settings/model"
            hx-trigger="change"
          >
            <option value="">— select model —</option>
            <!-- Populated from OpenRouter API on load via x-init-like mechanism -->
          </select>
          <p class="text-text-muted text-xs mt-1">
            ${model ? `Current: ${escapeHtml(model)}` : "Fetching models from OpenRouter..."}
          </p>
        </section>

        <!-- Agent Instructions -->
        <section class="cut-card">
          <h2 class="text-sm font-bold text-text-secondary mb-2">Agent Instructions (AGENTS.md)</h2>
          <textarea
            name="agent_md"
            rows="6"
            class="w-full bg-bg-surface border border-border-default text-text-primary p-2 text-sm font-mono rounded-none outline-none focus:border-neon-cyan transition-colors"
            placeholder="# Agent identity

You are..."
          >${escapeHtml(agentMd)}</textarea>
          <p class="text-text-muted text-xs mt-1">Appended to the system prompt every request.</p>
          <button
            class="cut-btn cut-btn-primary text-sm mt-2"
            hx-post="/api/settings/agent-md"
            hx-include="[name=agent_md]"
          >Save</button>
        </section>

        <!-- Theme -->
        <section class="cut-card">
          <h2 class="text-sm font-bold text-text-secondary mb-2">Theme</h2>
          <select
            name="theme"
            class="w-full bg-bg-surface border border-border-default text-text-primary p-2 text-sm font-mono rounded-none outline-none focus:border-neon-cyan transition-colors"
            hx-post="/api/settings/theme"
            hx-trigger="change"
          >
            <option value="cyberpunk" ${theme === "cyberpunk" ? "selected" : ""}>Cyberpunk</option>
            <!-- Future themes added here -->
          </select>
        </section>

        <!-- FX Intensity -->
        <section class="cut-card">
          <h2 class="text-sm font-bold text-text-secondary mb-2">FX Intensity</h2>
          <input
            type="range"
            name="fx_intensity"
            min="0"
            max="1"
            step="0.25"
            value="${fxIntensity}"
            class="w-full accent-neon-cyan"
            hx-post="/api/settings/fx-intensity"
            hx-trigger="change"
          />
          <div class="flex justify-between text-text-muted text-xs mt-1">
            <span>None</span>
            <span>Full</span>
          </div>
        </section>

      </div>
    </div>`;
}

function escapeAttr(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function escapeHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/routes/settings.html.ts
git commit -m "feat: add settings route template with API key, model, agent MD, theme, FX"
```

---

### Task 5.3: Chat view route

**Files:**
- Create: `src/routes/chat.html.ts`

- [ ] **Step 1: Create chat view template**

```typescript
import type { Conversation, Message } from "../lib/db";

export interface ChatViewData {
  conversation: Conversation;
  messages: Message[];
}

export function renderChatView(data: ChatViewData): string {
  const { conversation, messages } = data;

  const messageHtml = messages.map(msg => renderMessageBubble(msg)).join("\n");

  return `
    <div class="flex flex-col h-dvh safe-top safe-bottom" id="chat-view" data-conversation-id="${escapeAttr(conversation.id)}">
      <!-- Header -->
      <header class="flex items-center justify-between px-4 py-3 border-b border-border-default shrink-0">
        <div class="flex items-center">
          <a href="/" class="text-text-secondary hover:text-neon-cyan transition-colors mr-3">←</a>
          <h1 class="neon-cyan text-sm font-bold truncate max-w-[200px]" title="${escapeAttr(conversation.title)}">
            ${escapeHtml(conversation.title)}
          </h1>
        </div>
        <a href="/settings" class="text-text-secondary hover:text-neon-cyan transition-colors text-lg">⚙</a>
      </header>

      <!-- Message list -->
      <div
        class="flex-1 overflow-y-auto p-4 space-y-4"
        id="message-list"
        hx-target="#message-list"
        hx-select="#message-list"
        hx-swap="outerHTML"
        _="on htmx:afterSwap go to bottom of #message-list"
      >
        ${messageHtml || ""}
        <!-- Streaming assistant message placeholder — populated by JS module -->
        <div id="stream-container" class="${isStreaming(messages) ? "" : "hidden"}">
          <div class="msg-bubble-assistant cut-card stream-cursor" id="stream-bubble"></div>
        </div>
      </div>

      <!-- Input bar -->
      <div class="p-4 border-t border-border-default shrink-0">
        <form
          hx-post="/api/conversations/${escapeAttr(conversation.id)}/messages"
          hx-target="#message-list"
          hx-select="#message-list"
          hx-swap="outerHTML"
          hx-on::after-request="this.reset()"
          id="message-form"
        >
          <div class="flex gap-2">
            <input
              type="text"
              name="content"
              placeholder="Type a message..."
              autocomplete="off"
              class="flex-1 bg-bg-surface border border-border-default text-text-primary p-3 text-sm font-mono rounded-none outline-none focus:border-neon-cyan transition-colors"
              required
            />
            <button type="submit" class="cut-btn cut-btn-primary text-sm" id="send-btn">Send</button>
            <button
              type="button"
              class="cut-btn cut-btn-danger text-sm hidden"
              id="stop-btn"
              onclick="document.dispatchEvent(new CustomEvent('clanker:stop-stream'))"
            >■</button>
          </div>
        </form>
      </div>
    </div>`;
}

function renderMessageBubble(msg: Message): string {
  switch (msg.role) {
    case "user":
      return `
        <div class="flex justify-end">
          <div class="msg-bubble-user cut-card max-w-[80%]">
            <p class="text-xs text-text-muted mb-1">You</p>
            <div class="text-sm">${escapeHtml(msg.content || "")}</div>
          </div>
        </div>`;

    case "assistant":
      return `
        <div class="flex justify-start">
          <div class="msg-bubble-assistant cut-card max-w-[85%]">
            <p class="text-xs text-text-muted mb-1">clanker</p>
            <div class="text-sm prose">${msg.content || ""}</div>
            ${msg.citations?.length ? renderCitations(msg.citations) : ""}
          </div>
        </div>`;

    case "system":
      return `
        <div class="text-center">
          <div class="msg-bubble-system text-xs">${escapeHtml(msg.content || "")}</div>
        </div>`;

    default:
      return "";
  }
}

function renderCitations(citations: Array<{ url: string; title?: string }>): string {
  return `
    <div class="flex flex-wrap gap-2 mt-2 pt-2 border-t border-border-default">
      ${citations.map(c => `
        <a href="${escapeAttr(c.url)}" target="_blank" class="citation-chip" rel="noopener">
          ${escapeHtml(c.title || c.url.replace(/^https?:\/\//, "").split("/")[0])}
        </a>`).join("")}
    </div>`;
}

function isStreaming(messages: Message[]): boolean {
  return messages.some(m => m.lifecycle === "streaming");
}

function escapeAttr(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function escapeHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/routes/chat.html.ts
git commit -m "feat: add chat view route template with message bubbles + streaming container"
```

---

## Chunk 6: Streaming — OpenRouter SSE Client

### Task 6.1: OpenRouter types

**Files:**
- Create: `src/lib/openrouter-types.ts`

- [ ] **Step 1: Create OpenRouter types**

```typescript
export interface ChatCompletionChunk {
  id: string;
  object: "chat.completion.chunk";
  created: number;
  model: string;
  choices: Choice[];
  usage?: Usage;
}

export interface Choice {
  index: number;
  delta: Delta;
  finish_reason: "stop" | "tool_calls" | "length" | "content_filter" | null;
}

export interface Delta {
  content?: string;
  tool_calls?: ToolCallDelta[];
  annotations?: Annotation[];
}

export interface ToolCallDelta {
  index: number;
  id?: string;
  type?: "function";
  function?: {
    name?: string;
    arguments?: string;
  };
}

export interface Annotation {
  type: "url_citation";
  url: string;
  title?: string;
  content?: string;
  start_index?: number;
  end_index?: number;
}

export interface Usage {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  cost?: number;
  server_tool_use?: {
    web_search_requests?: number;
  };
}

export interface ChatRequest {
  model: string;
  messages: Array<{
    role: "system" | "user" | "assistant" | "tool";
    content: string | null;
    tool_calls?: Array<{
      id: string;
      type: "function";
      function: { name: string; arguments: string };
    }>;
  }>;
  tools?: Array<{
    type: "function" | `openrouter:${string}`;
    function?: { name: string; description: string; parameters: Record<string, unknown> };
    parameters?: Record<string, unknown>;
  }>;
  stream?: boolean;
  include_reasoning?: boolean;
}

export function defaultServerTools() {
  return [
    { type: "openrouter:web_search" as const },
    { type: "openrouter:web_fetch" as const },
    { type: "openrouter:datetime" as const },
  ];
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/openrouter-types.ts
git commit -m "feat: add OpenRouter API types and server tool definitions"
```

---

### Task 6.2: System prompt

**Files:**
- Create: `src/lib/system-prompt.ts`

- [ ] **Step 1: Create system prompt module**

```typescript
const SYSTEM_PROMPT = `# clanker

You are clanker, an agentic AI assistant. You communicate through a chat interface and have access to tools including web search, web fetch, and current datetime.

## Capabilities
- Answer questions using web search when needed
- Fetch and read web page content
- Provide current date/time information
- Process images sent by the user
- Generate code, write text, analyze data

## Guidelines
- Think step by step for complex questions
- Use web search proactively when you need current information
- When citing sources, include clear references
- Keep responses concise and relevant
- Format code blocks with language tags for syntax highlighting
- Use markdown for rich formatting

## Safety
- You are a helpful assistant. Do not generate harmful, deceptive, or misleading content.
- Acknowledge uncertainty when you don't know something.`;

export function composeSystemPrompt(agentsMd: string): string {
  if (!agentsMd || agentsMd.trim().length === 0) {
    return SYSTEM_PROMPT;
  }
  return `${SYSTEM_PROMPT}\n\n## Additional Instructions\n\n${agentsMd}`;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/system-prompt.ts
git commit -m "feat: add system prompt and composeSystemPrompt"
```

---

### Task 6.3: OpenRouter SSE streaming client

**Files:**
- Create: `src/lib/openrouter.ts`

- [ ] **Step 1: Create SSE streaming client**

```typescript
import type {
  ChatCompletionChunk,
  ChatRequest,
  ToolCallDelta,
  Annotation,
  Usage,
  defaultServerTools,
} from "./openrouter-types";
import { composeSystemPrompt } from "./system-prompt";
import { getApiKey, getSelectedModel, getAgentMd } from "./settings";
import { getMessages, createMessage, updateMessage } from "./db";
import type { Message } from "./db";

export interface StreamCallbacks {
  onToken: (text: string) => void;
  onCitation: (citation: Annotation) => void;
  onToolCall: (delta: ToolCallDelta) => void;
  onUsage: (usage: Usage) => void;
  onDone: (finalContent: string, citations: Annotation[]) => void;
  onError: (error: string) => void;
}

let abortController: AbortController | null = null;

export function stopStream(): void {
  abortController?.abort();
  abortController = null;
}

export async function streamChat(
  conversationId: string,
  callbacks: StreamCallbacks
): Promise<void> {
  const apiKey = getApiKey();
  if (!apiKey) {
    callbacks.onError("No API key configured. Go to Settings to add one.");
    return;
  }

  const model = getSelectedModel();
  const agentMd = getAgentMd();
  const systemContent = composeSystemPrompt(agentMd);

  // Load conversation history
  const messages = await getMessages(conversationId);

  // Build the OpenAI-compatible messages array
  const openAiMessages: ChatRequest["messages"] = [
    { role: "system", content: systemContent },
    ...messages.map((m: Message) => ({
      role: m.role as "user" | "assistant" | "system" | "tool",
      content: m.content,
      ...(m.tool_calls ? { tool_calls: m.tool_calls.map(tc => ({
        id: tc.id,
        type: "function" as const,
        function: { name: tc.name, arguments: tc.arguments },
      }))} : {}),
    })),
  ];

  // Build request body
  const body: ChatRequest = {
    model,
    messages: openAiMessages,
    stream: true,
    include_reasoning: true,
    tools: defaultServerTools(),
  };

  // Create the assistant message placeholder in SurrealDB
  const assistantMsgId = await createMessage({
    conversation: conversationId,
    role: "assistant",
    content: null,
    lifecycle: "streaming",
  });

  abortController = new AbortController();

  try {
    const response = await fetch("https://api.openrouter.ai/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
        "HTTP-Referer": window.location.origin,
        "X-Title": "clanker",
      },
      body: JSON.stringify(body),
      signal: abortController.signal,
    });

    if (!response.ok) {
      const errorText = await response.text().catch(() => "");
      const errorMsg = `OpenRouter ${response.status}: ${errorText || response.statusText}`;
      callbacks.onError(errorMsg);
      await updateMessage(assistantMsgId, {
        content: errorMsg,
        lifecycle: "interrupted",
      });
      return;
    }

    // Read the SSE stream
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let accumulatedContent = "";
    const accumulatedCitations: Annotation[] = [];
    const seenCitationKeys = new Set<string>();

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Parse SSE lines from buffer
      const lines = buffer.split("\n");
      buffer = lines.pop() || ""; // Keep partial line

      for (const line of lines) {
        if (!line.startsWith("data: ")) continue;

        const data = line.slice(6).trim();
        if (data === "[DONE]") continue;

        try {
          const chunk: ChatCompletionChunk = JSON.parse(data);
          const choice = chunk.choices?.[0];

          // Text delta
          if (choice?.delta?.content) {
            accumulatedContent += choice.delta.content;
            callbacks.onToken(accumulatedContent);
          }

          // Tool call delta
          if (choice?.delta?.tool_calls) {
            for (const tc of choice.delta.tool_calls) {
              callbacks.onToolCall(tc);
            }
          }

          // Annotations (url_citations) — deduplicate by url+start_index
          if (choice?.delta?.annotations) {
            for (const ann of choice.delta.annotations) {
              const key = `${ann.url}:${ann.start_index ?? 0}`;
              if (!seenCitationKeys.has(key)) {
                seenCitationKeys.add(key);
                accumulatedCitations.push(ann);
                callbacks.onCitation(ann);
              }
            }
          }

          // Usage info (usually final chunk)
          if (chunk.usage) {
            callbacks.onUsage(chunk.usage);
          }
        } catch {
          // Skip malformed JSON lines
        }
      }
    }

    // Stream complete — persist to SurrealDB
    await updateMessage(assistantMsgId, {
      content: accumulatedContent,
      lifecycle: "complete",
      citations: accumulatedCitations.length > 0 ? accumulatedCitations.map(a => ({
        url: a.url,
        title: a.title,
        content: a.content,
      })) : undefined,
    });

    callbacks.onDone(accumulatedContent, accumulatedCitations);

  } catch (err: unknown) {
    if ((err as Error).name === "AbortError") {
      // User stopped the stream
      await updateMessage(assistantMsgId, {
        content: accumulatedContent || null,
        lifecycle: "interrupted",
      });
      return;
    }
    const errorMsg = (err as Error).message || "Unknown error";
    callbacks.onError(errorMsg);
    await updateMessage(assistantMsgId, {
      content: errorMsg,
      lifecycle: "interrupted",
    });
  } finally {
    abortController = null;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/openrouter.ts
git commit -m "feat: add OpenRouter SSE streaming client with AbortController support"
```

---

### Task 6.4: Stream bubble DOM management

**Files:**
- Create: `src/lib/stream-bubble.ts`

- [ ] **Step 1: Create stream bubble module**

```typescript
import { marked } from "marked";

let debounceTimer: ReturnType<typeof setTimeout> | null = null;
let currentContent = "";
let currentCitations: Array<{ url: string; title?: string }> = [];

const RENDER_DEBOUNCE_MS = 100;

/**
 * Shows the streaming bubble, sets it to streaming mode (blinking cursor).
 */
export function showStreamBubble(): void {
  const container = document.getElementById("stream-container");
  const bubble = document.getElementById("stream-bubble");
  if (container) container.classList.remove("hidden");
  if (bubble) bubble.classList.add("stream-cursor");
  currentContent = "";
  currentCitations = [];
}

/**
 * Updates the streaming bubble with new accumulated text.
 * Debounces the markdown render to ~100ms.
 */
export function updateStreamBubble(content: string): void {
  currentContent = content;

  if (debounceTimer) clearTimeout(debounceTimer);

  debounceTimer = setTimeout(() => {
    const bubble = document.getElementById("stream-bubble");
    if (!bubble) return;

    // Extract markdown content before the citations
    let html = marked.parse(content) as string;

    bubble.innerHTML = html;
  }, RENDER_DEBOUNCE_MS);
}

/**
 * Adds a citation chip to the streaming bubble.
 */
export function addCitation(url: string, title?: string): void {
  currentCitations.push({ url, title });

  const bubble = document.getElementById("stream-bubble");
  if (!bubble) return;

  // Remove existing citations wrapper if any
  const existing = bubble.querySelector(".stream-citations");
  if (existing) existing.remove();

  const wrapper = document.createElement("div");
  wrapper.className = "stream-citations flex flex-wrap gap-2 mt-2 pt-2 border-t border-border-default";

  for (const c of currentCitations) {
    const a = document.createElement("a");
    a.href = c.url;
    a.target = "_blank";
    a.className = "citation-chip";
    a.textContent = c.title || c.url.replace(/^https?:\/\//, "").split("/")[0];
    wrapper.appendChild(a);
  }

  bubble.appendChild(wrapper);
}

/**
 * Finalizes the streaming bubble: removes the blinking cursor,
 * does one final render, and returns the final HTML and citations.
 */
export function finalizeStreamBubble(): { html: string; citations: typeof currentCitations } {
  if (debounceTimer) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }

  const bubble = document.getElementById("stream-bubble");
  if (bubble) {
    bubble.classList.remove("stream-cursor");
    // Final render
    bubble.innerHTML = marked.parse(currentContent) as string;
    // Re-add citations
    if (currentCitations.length > 0) {
      const wrapper = document.createElement("div");
      wrapper.className = "stream-citations flex flex-wrap gap-2 mt-2 pt-2 border-t border-border-default";
      for (const c of currentCitations) {
        const a = document.createElement("a");
        a.href = c.url;
        a.target = "_blank";
        a.className = "citation-chip";
        a.textContent = c.title || c.url.replace(/^https?:\/\//, "").split("/")[0];
        wrapper.appendChild(a);
      }
      bubble.appendChild(wrapper);
    }
  }

  return { html: currentContent ? (marked.parse(currentContent) as string) : "", citations: currentCitations };
}

/**
 * Hides and resets the streaming bubble.
 */
export function hideStreamBubble(): void {
  const container = document.getElementById("stream-container");
  const bubble = document.getElementById("stream-bubble");
  if (container) container.classList.add("hidden");
  if (bubble) {
    bubble.innerHTML = "";
    bubble.classList.remove("stream-cursor");
  }
  currentContent = "";
  currentCitations = [];
  if (debounceTimer) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
}

/**
 * Converts a finalized streaming bubble into a static htmx-compatible message element.
 * Called after the stream ends to replace the ephemeral bubble with a permanent one.
 */
export function streamBubbleToHtml(): string {
  const content = currentContent;
  const citations = currentCitations;

  if (!content && citations.length === 0) return "";

  const citationsHtml = citations.length > 0
    ? `<div class="flex flex-wrap gap-2 mt-2 pt-2 border-t border-border-default">
        ${citations.map(c =>
          `<a href="${escAttr(c.url)}" target="_blank" class="citation-chip" rel="noopener">
            ${escHtml(c.title || c.url.replace(/^https?:\/\//, "").split("/")[0])}
          </a>`
        ).join("")}
      </div>`
    : "";

  return `
    <div class="flex justify-start">
      <div class="msg-bubble-assistant cut-card max-w-[85%]">
        <p class="text-xs text-text-muted mb-1">clanker</p>
        <div class="text-sm prose">${marked.parse(content) as string}</div>
        ${citationsHtml}
      </div>
    </div>`;
}

function escHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

function escAttr(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
```

- [ ] **Step 2: Commit**

```bash
git add src/lib/stream-bubble.ts
git commit -m "feat: add stream bubble DOM management with debounced markdown rendering"
```

---

## Chunk 7: Routing and Integration

### Task 7.1: In-app htmx route handlers

This task wires the htmx API endpoints to the in-app route handlers. Since there's no backend, we use htmx's event system to intercept requests and return rendered HTML from the template functions.

**Files:**
- Modify: `src/app.ts`

- [ ] **Step 1: Add htmx route handlers to app.ts**

Append to `src/app.ts` after the `main()` function:

```typescript
// ── htmx Interceptor: route handler ─────────────────────────

// Intercept htmx requests that target our API routes.
// Uses htmx:beforeRequest to intercept and handle API calls in-app
// since there's no backend server.

document.addEventListener("htmx:configRequest", (evt) => {
  const detail = (evt as CustomEvent).detail;
  const path = detail.path;

  // Intercept route loads
  if (path === "/" || path.startsWith("/chat/") || path === "/settings") {
    handleRoute(detail);
    return;
  }

  // Intercept API calls
  if (path.startsWith("/api/")) {
    handleApiRequest(detail);
    return;
  }
});

async function handleRoute(detail: any): Promise<void> {
  const path = detail.path;
  try {
    if (path === "/") {
      const { renderChatList, ChatListEntry } = await import("./routes/chat-list.html.ts");
      const { listConversations } = await import("./lib/db");
      const conversations = await listConversations();
      const html = renderChatList(conversations);
      document.getElementById("app-shell")!.innerHTML = html;
    } else if (path === "/settings") {
      const { renderSettings } = await import("./routes/settings.html.ts");
      const html = renderSettings();
      document.getElementById("app-shell")!.innerHTML = html;
      // Populate model dropdown
      populateModelDropdown();
    } else if (path.startsWith("/chat/")) {
      const convId = path.split("/")[2];
      const { renderChatView } = await import("./routes/chat.html.ts");
      const { getConversation, getMessages } = await import("./lib/db");
      const [conversation, messages] = await Promise.all([
        getConversation(convId),
        getMessages(convId),
      ]);
      if (!conversation) {
        document.getElementById("app-shell")!.innerHTML = "<p class='p-4 text-error'>Conversation not found</p>";
        return;
      }
      const html = renderChatView({ conversation, messages });
      document.getElementById("app-shell")!.innerHTML = html;
    }
    // Prevent htmx from making the actual request
    detail.elt?.dispatchEvent(new Event("htmx:abort"));
  } catch (err) {
    console.error("Route handler error:", err);
  }
}

async function handleApiRequest(detail: any): Promise<void> {
  const path = detail.path;
  const method = detail.method || "GET";
  const params = detail.parameters || {};

  try {
    if (path === "/api/conversations" && method === "POST") {
      // Create new conversation and navigate to it
      const { createConversation } = await import("./lib/db");
      const id = await createConversation();
      window.history.pushState({}, "", `/chat/${id}`);
      // Reload the route
      handleRoute({ path: `/chat/${id}` });
    } else if (path.match(/^\/api\/conversations\/([^\/]+)\/messages$/) && method === "POST") {
      const convId = path.split("/")[3];
      const content = params.content;
      if (!content || !convId) return;

      const { createMessage, getMessages } = await import("./lib/db");
      await createMessage({
        conversation: convId,
        role: "user",
        content,
        lifecycle: "complete",
      });

      // Re-render the message list
      const messages = await getMessages(convId);
      const chatView = await import("./routes/chat.html.ts");
      const { getConversation } = await import("./lib/db");
      const conversation = await getConversation(convId);
      if (conversation) {
        const html = chatView.renderChatView({ conversation, messages });
        document.getElementById("message-list")!.outerHTML = extractElement(html, "#message-list");
      }

      // Update conversation title from first message
      const { updateConversationTitle } = await import("./lib/db");
      const preview = content.slice(0, 60);
      updateConversationTitle(convId, preview);

      // Start streaming
      startStreaming(convId);
    } else if (path === "/api/settings/api-key") {
      const { setApiKey, clearApiKey } = await import("./lib/settings");
      if (params.api_key) setApiKey(params.api_key);
      else clearApiKey();
    } else if (path === "/api/settings/api-key/clear") {
      const { clearApiKey } = await import("./lib/settings");
      clearApiKey();
    } else if (path === "/api/settings/model") {
      const { setSelectedModel } = await import("./lib/settings");
      if (params.model) setSelectedModel(params.model);
    } else if (path === "/api/settings/agent-md") {
      const { setAgentMd } = await import("./lib/settings");
      if (params.agent_md !== undefined) setAgentMd(params.agent_md);
    } else if (path === "/api/settings/theme") {
      const { setTheme } = await import("./lib/settings");
      if (params.theme) setTheme(params.theme);
    } else if (path === "/api/settings/fx-intensity") {
      const { setFxIntensity } = await import("./lib/settings");
      if (params.fx_intensity !== undefined) {
        setFxIntensity(parseFloat(params.fx_intensity));
        document.documentElement.setAttribute("data-fx-intensity", String(params.fx_intensity));
      }
    }

    // Prevent htmx from making the actual request
    detail.elt?.dispatchEvent(new Event("htmx:abort"));
  } catch (err) {
    console.error("API handler error:", err);
  }
}

function extractElement(html: string, selector: string): string {
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, "text/html");
  const el = doc.querySelector(selector);
  return el ? el.outerHTML : html;
}

async function populateModelDropdown(): Promise<void> {
  const apiKey = getApiKey();
  if (!apiKey) return;

  try {
    const resp = await fetch("https://api.openrouter.ai/api/v1/models", {
      headers: { Authorization: `Bearer ${apiKey}` },
    });
    if (!resp.ok) return;
    const data = await resp.json();
    const models = data.data || [];
    const select = document.querySelector<HTMLSelectElement>("select[name=model]");
    if (!select) return;

    const currentModel = getSelectedModel();
    select.innerHTML = '<option value="">— select model —</option>';
    for (const m of models) {
      const opt = document.createElement("option");
      opt.value = m.id;
      opt.textContent = m.id;
      if (m.id === currentModel) opt.selected = true;
      select.appendChild(opt);
    }
  } catch {
    // Model list fetch failed — current model still works
  }
}

async function startStreaming(conversationId: string): Promise<void> {
  const { streamChat } = await import("./lib/openrouter");
  const { showStreamBubble, updateStreamBubble, addCitation,
          finalizeStreamBubble, hideStreamBubble, streamBubbleToHtml } = await import("./lib/stream-bubble");

  showStreamBubble();

  // Switch Send → Stop button
  const sendBtn = document.getElementById("send-btn");
  const stopBtn = document.getElementById("stop-btn");
  const msgForm = document.getElementById("message-form") as HTMLFormElement;
  if (sendBtn) sendBtn.classList.add("hidden");
  if (stopBtn) stopBtn.classList.remove("hidden");
  if (msgForm) msgForm.querySelector<HTMLInputElement>("[name=content]")!.disabled = true;

  await streamChat(conversationId, {
    onToken(content: string) {
      updateStreamBubble(content);
    },
    onCitation(citation) {
      addCitation(citation.url, citation.title);
    },
    onToolCall(_delta) {
      // Tool calls are server-executed (OpenRouter server tools) — no client action needed
    },
    onUsage(_usage) {
      // Could surface cost in UI
    },
    onDone(_content, citations) {
      // Finalize the streaming bubble
      finalizeStreamBubble();

      // Convert to static HTML and append to message list
      const html = streamBubbleToHtml();
      if (html) {
        // Hide the streaming container and append the permanent message
        hideStreamBubble();
        const list = document.getElementById("message-list");
        if (list) {
          const temp = document.createElement("div");
          temp.innerHTML = html;
          list.appendChild(temp.firstElementChild!);
          list.scrollTop = list.scrollHeight;
        }
      }

      // Re-enable input
      if (sendBtn) sendBtn.classList.remove("hidden");
      if (stopBtn) stopBtn.classList.add("hidden");
      if (msgForm) msgForm.querySelector<HTMLInputElement>("[name=content]")!.disabled = false;
    },
    onError(error) {
      hideStreamBubble();
      const list = document.getElementById("message-list");
      if (list) {
        list.innerHTML += `
          <div class="flex justify-start">
            <div class="msg-bubble-assistant cut-card max-w-[85%] border-error">
              <p class="text-xs text-error mb-1">error</p>
              <div class="text-sm text-error">${escHtml(error)}</div>
            </div>
          </div>`;
        list.scrollTop = list.scrollHeight;
      }
      if (sendBtn) sendBtn.classList.remove("hidden");
      if (stopBtn) stopBtn.classList.add("hidden");
      if (msgForm) msgForm.querySelector<HTMLInputElement>("[name=content]")!.disabled = false;
    },
  });
}

function escHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}
```

Also need to add the `getApiKey` and `getSelectedModel` imports to the existing `app.ts` imports.

- [ ] **Step 2: Verify it compiles**

Run: `npx tsc --noEmit`
Expected: TypeScript compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add src/app.ts
git commit -m "feat: wire htmx route handlers and API endpoints for in-app routing"
```

---

## Chunk 8: Integration and Testing

### Task 8.1: Dev server smoke test

- [ ] **Step 1: Start dev server**

Run: `npx vite`
Expected: Dev server starts on localhost, serves the app shell.

- [ ] **Step 2: Open in browser and verify**

- Page loads with cyberpunk background (#0a0a0f)
- htmx-boosted navigation works (click Settings, click back)
- PWA manifest is detectable (DevTools → Application → Manifest)
- Service worker registers (DevTools → Application → Service Workers)

- [ ] **Step 3: Verify conversation creation**

- Navigate to "/" 
- Click "New conversation" button
- A new conversation is created in SurrealDB
- Browser navigates to /chat/{id}
- Chat view loads with empty message area

- [ ] **Step 4: Verify settings persistence**

- Navigate to Settings
- Enter an API key, save
- Reload page — API key should persist in localStorage
- Type agent instructions, save
- Reload — agent instructions persist

- [ ] **Step 5: Verify production build**

Run: `npx vite build`
Expected: Static files output to `dist/` — index.html, JS/CSS bundles, SW, manifest, icons.

- [ ] **Step 6: Commit**

```bash
git commit -m "chore: smoke test and verify PWA build"
```

---

## Chunk 9: Edge Cases and Polish

### Task 9.1: Handle interrupted streams on page navigation

**Files:**
- Modify: `src/app.ts`

- [ ] **Step 1: Abort active stream on navigation**

Add a before-navigation handler:

```typescript
// Cancel any active stream before navigating away
document.addEventListener("htmx:beforeOnLoad", () => {
  const { stopStream } = require("./lib/openrouter");
  stopStream();
});

// Also handle browser back/forward
window.addEventListener("popstate", () => {
  const { stopStream } = require("./lib/openrouter");
  stopStream();
});
```

- [ ] **Step 2: Handle SurrealDB init failure gracefully**

Already present in `app.ts` — the init error shows a centered error card. Verify by temporarily breaking the SurrealDB init path and loading the app.

- [ ] **Step 3: Commit**

```bash
git add src/app.ts
git commit -m "fix: abort active stream on navigation"
```

---

### Task 9.2: Final .gitignore and README

**Files:**
- Create: `.gitignore`

- [ ] **Step 1: Create .gitignore**

```
node_modules/
dist/
*.local
.DS_Store
```

- [ ] **Step 2: Commit**

```bash
git add .gitignore
git commit -m "chore: add .gitignore"
```

---

## Plan Review

After completing each chunk above, a plan reviewer should verify:
1. Each task produces self-contained, testable changes
2. File paths are correct
3. Imports between files are consistent
4. No dead code or placeholder logic