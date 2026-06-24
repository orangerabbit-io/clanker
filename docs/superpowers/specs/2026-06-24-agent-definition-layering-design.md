# Agent Definition Layering — Design

**Date:** 2026-06-24
**Status:** Approved (design); implementation plan pending
**Scope:** Replace the character-card persona system with a two-layer agent definition
(fixed system prompt + user-editable `AGENTS.md`). Retire all roleplay scaffolding.

## Problem

Clanker is an **agentic AI harness** (per `DESIGN.md`), but its only identity layer today is
the imported character card. When no card is loaded the system prompt is *empty* — the model is
a blank OpenAI-compatible assistant with no notion of what clanker is or what it can do. When a
SillyTavern-style card *is* loaded, the only "self" the model receives is a roleplay persona, so
it roleplays. The character-card feature accidentally imported the identity of a different product
(a roleplay companion) into a tool harness.

The fix is not to manage personas better. It is to give clanker the identity layer an agent
harness actually needs — a fixed operating frame — and demote user-supplied instruction to a
conventional, capability-free preferences layer.

## Decisions

These were settled during brainstorming and are fixed for this work:

1. **Clanker is an agent harness only.** Persona/roleplay is not a product pillar. Character
   cards and their scaffolding (greetings, example dialogue, scenarios, alternate greetings) are
   removed, not managed.
2. **Two-layer prompt, following established convention** (Claude Code's system prompt + `CLAUDE.md`;
   Codex/Cursor's system prompt + `AGENTS.md`; etc.):
   - **System prompt** — harness-owned, fixed, versioned with the app binary, *not* user-editable.
   - **`AGENTS.md`** — user-editable markdown, layered on top. Named after the cross-tool open
     standard (agents.md).
3. **Single global `AGENTS.md`.** No per-project / per-directory hierarchy (clanker has no project
   tree). Multiple named profiles and per-conversation overrides are explicitly **later** work.
4. **Capability lives in the tool registry, not prose.** The system prompt carries only the
   *behavioral framing* for tools; the actual tools are the structured `ChatRequest.tools` array
   (empty today). The system prompt must not claim capabilities that do not yet exist.
5. **Composed at request time, never stored in history** — preserving the existing retroactive-edit
   property.
6. **Delete `:core:character` outright** (git retains it). **Drop the `{{char}}`/`{{user}}` macros.**

## Architecture

### Layer 0 — System prompt (fixed)

A constant in the app module (e.g. `io.orangerabbit.clanker.agent.SystemPrompt`), versioned with
releases. Content today is deliberately minimal because no tools exist yet:

- Establishes clanker as an AI agent running on the user's Android device.
- States its operating posture (direct, capability-honest).
- Carries a tool-use framing section that is **empty/placeholder now** and grows as the tool
  registry lands (web search, SSH verbs, etc.). It must not assert tools it cannot call.

Lives in the app module for now. When the agent loop is built it can move to `:core:agent`
(anticipated in `DESIGN.md`) — but that module is not created in this step (nothing else to put in
it yet).

### Layer 1 — `AGENTS.md` (user-editable)

- Single global markdown document. Default: empty.
- Stored in DataStore (new `agentsMd: String` preference in `SettingsStore`, alongside the existing
  non-secret settings). Not a secret; plaintext DataStore Proto is appropriate.
- Edited via a new multiline field in the Settings UI, labelled "Agent instructions (`AGENTS.md`)"
  with a hint: *"Project conventions and preferences, e.g. 'use conventional commits.'"*
- Import/export to a real `.md` file is a nice-to-have, **not** in this scope.

### Request assembly

In `ChatViewModel.send` (replacing the current persona block at ChatViewModel.kt:254–257):

```
val agentsMd = current.agentsMd            // from SettingsStore-backed UiState
val systemText = buildString {
    append(SystemPrompt.text)
    if (agentsMd.isNotBlank()) { append("\n\n"); append(agentsMd) }
}
val systemMessages = listOf(ChatMessage.System(MessageId(newId()), systemText))
val request = ChatRequest(
    model = ...,                           // unchanged (global default; per-character override removed)
    messages = systemMessages + history,
    modalities = ...,
)
```

The System message is **always present now**, even with no user instructions. Still built at
request time; never appended to stored history.

## Removals

- `:core:character` module deleted entirely: `CharacterCard`, `CharacterCardParser` (PNG chunk
  walker + JSON/DTO parsing), `Persona` (incl. `substituteMacros`, `greeting`), and their tests
  (`CharacterCardParserTest`, `PersonaTest`).
- Gradle: drop the `:core:character` module from `settings.gradle`/version catalog and remove
  `:app`'s dependency on it.
- `ChatViewModel`: remove `applyCardBytes`, greeting seeding, and the `character` field from
  `UiState`; remove the `{{char}}`/`{{user}}` macro path.
- UI: remove the card-import entrypoint and its file-picker plumbing from `ChatScreen`.
- `SettingsStore`: remove the **per-character** model override (keyed by character name); collapse to
  the existing global default chat/image models. Lifetime spend, FX intensity, default models stay.
- `Conversation.characterId`: **dropped now** (YAGNI); re-added as `agentProfileId` when profiles land.

## Data flow (after)

```
[app launch] SystemPrompt.text (constant) + SettingsStore.agentsMd (DataStore) loaded into UiState
     |
[user sends] ChatViewModel.send
     |  systemText = SystemPrompt.text + ("\n\n" + agentsMd if non-blank)
     v
ChatRequest(messages = [System(systemText)] + history)
     v
OpenAiCompatibleProvider.toWire -> [{role:"system", ...}, ...] -> SSE stream (unchanged downstream)
```

## Testing

- Prompt assembly (new, in app module): system-only (blank `AGENTS.md`) yields exactly
  `SystemPrompt.text`; non-blank `AGENTS.md` yields `system + "\n\n" + agentsMd`; whitespace-only
  `AGENTS.md` treated as blank.
- `SettingsStore`: `agentsMd` round-trips through DataStore; default is empty string.
- Confirm the build is green after `:core:character` removal (no dangling references).

## Out of scope (sequenced later)

| Item | Blocked on |
|---|---|
| Multiple named agent profiles, switch/remove | this layer landing first |
| Per-conversation persistence + chat list/switch/delete | Room (`DESIGN.md` v1) |
| Tool descriptions injected into the system prompt | tool registry / agent loop |
| `AGENTS.md` import/export to file | — (deferred polish) |

## Impact on DESIGN.md

`DESIGN.md` §4/§7 (persona composed at request time; Character Card V2/V3 as canonical model)
should be updated to reflect that the persona/character-card path is removed in favour of the
system-prompt + `AGENTS.md` two-layer model. A decision-log (§14) entry should record this reversal.
This doc-update is part of implementation, not a separate spec.
