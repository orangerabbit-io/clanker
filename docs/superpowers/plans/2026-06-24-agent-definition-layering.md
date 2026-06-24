# Agent Definition Layering Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace clanker's character-card persona system with a two-layer agent definition — a fixed, app-versioned system prompt plus a single user-editable `AGENTS.md` — composed into the System message at request time.

**Architecture:** A new `:core:agent` KMP module owns the fixed `SystemPrompt` text and a pure `composeSystemPrompt(agentsMd)` function (testable on `jvmTest`, matching the project's other core modules). `AGENTS.md` is a single global string in the existing Preferences `SettingsStore`, edited in Settings. `ChatViewModel.send` composes `systemPrompt + "\n\n" + agentsMd` per request, never storing it in history. The entire `:core:character` module and all per-character/persona scaffolding are deleted.

**Tech Stack:** Kotlin Multiplatform (jvm + android targets), AGP 9.1.1, Compose, DataStore Preferences, Metro DI, `kotlin("test")` on `jvmTest`.

**Spec:** `docs/superpowers/specs/2026-06-24-agent-definition-layering-design.md`

**Build/test commands** (Nix — every Gradle run goes through `nix develop`; the `aapt2FromMavenOverride` `-P` flag is required):
- Core test: `nix develop --command bash -c './gradlew :core:agent:jvmTest'`
- App build: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`

> **Deviation from spec:** the spec said the `SystemPrompt` constant lives "in the app module for now." This plan instead creates `:core:agent` (the spec's named eventual home) because the app module has no unit-test setup and the composition logic must be test-driven. If the reviewer/owner prefers app-module placement, Task 1 collapses into adding `app/src/test` infra instead.

---

## Chunk 1: Two-layer agent definition, retire character cards

### Task 1: Create `:core:agent` module with the system prompt + composition (TDD)

**Files:**
- Create: `core/agent/build.gradle.kts`
- Create: `core/agent/src/commonMain/kotlin/io/orangerabbit/clanker/core/agent/SystemPrompt.kt`
- Create: `core/agent/src/commonTest/kotlin/io/orangerabbit/clanker/core/agent/SystemPromptTest.kt`
- Modify: `settings.gradle.kts:19-23` (add the module include)

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add after the `:core:network` include (leave `:core:character` for now — it is removed in Task 5):

```kotlin
include(":core:agent")
```

- [ ] **Step 2: Create the module build file**

`core/agent/build.gradle.kts` (mirrors `:core:character` minus serialization):

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm()
    android {
        namespace = "io.orangerabbit.clanker.core.agent"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

- [ ] **Step 3: Write the failing test**

`core/agent/src/commonTest/kotlin/io/orangerabbit/clanker/core/agent/SystemPromptTest.kt`:

```kotlin
package io.orangerabbit.clanker.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemPromptTest {

    @Test
    fun systemPromptTextIsNonBlankAndNamesClanker() {
        assertTrue(SystemPrompt.TEXT.isNotBlank())
        assertTrue(SystemPrompt.TEXT.contains("clanker", ignoreCase = true))
    }

    @Test
    fun blankAgentsMdYieldsSystemPromptOnly() {
        assertEquals(SystemPrompt.TEXT, composeSystemPrompt(""))
    }

    @Test
    fun whitespaceOnlyAgentsMdIsTreatedAsBlank() {
        assertEquals(SystemPrompt.TEXT, composeSystemPrompt("   \n\t "))
    }

    @Test
    fun nonBlankAgentsMdIsAppendedAfterDoubleNewline() {
        val instructions = "Use conventional commits."
        assertEquals(
            SystemPrompt.TEXT + "\n\n" + instructions,
            composeSystemPrompt(instructions),
        )
    }

    @Test
    fun agentsMdOuterWhitespaceIsTrimmedBeforeAppending() {
        assertEquals(
            SystemPrompt.TEXT + "\n\n" + "Be terse.",
            composeSystemPrompt("  Be terse.  "),
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `nix develop --command bash -c './gradlew :core:agent:jvmTest'`
Expected: compile failure — `SystemPrompt` / `composeSystemPrompt` unresolved.

- [ ] **Step 5: Write the minimal implementation**

`core/agent/src/commonMain/kotlin/io/orangerabbit/clanker/core/agent/SystemPrompt.kt`:

```kotlin
package io.orangerabbit.clanker.core.agent

/**
 * The fixed, app-versioned agent definition (Layer 0). Not user-editable; it ships with the binary
 * and changes only with releases. It is capability-honest: it must not claim tools that do not yet
 * exist. Tool-use framing grows here as the tool registry lands. User preferences live in
 * `AGENTS.md` (Layer 1) and are appended by [composeSystemPrompt].
 */
object SystemPrompt {
    val TEXT: String = """
        You are clanker, an AI agent running on the user's Android device.
        Respond directly and concisely. Do not roleplay or invent a persona.

        You currently have no tools and cannot take actions on the user's systems or access
        external services. Do not claim or imply otherwise. When such capabilities are added,
        this definition will describe them explicitly.

        The user may supply additional instructions and preferences below. Follow them unless they
        conflict with safety or with this definition.
    """.trimIndent()
}

/**
 * Composes the System message sent on each request: the fixed [SystemPrompt.TEXT], plus the user's
 * `AGENTS.md` appended after a blank line when non-blank. Blank/whitespace-only instructions yield
 * the fixed prompt unchanged. Called at request time and never stored in history, so edits apply
 * retroactively.
 */
fun composeSystemPrompt(agentsMd: String): String =
    if (agentsMd.isBlank()) SystemPrompt.TEXT
    else SystemPrompt.TEXT + "\n\n" + agentsMd.trim()
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `nix develop --command bash -c './gradlew :core:agent:jvmTest'`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts core/agent
git commit -m "feat(agent): add :core:agent with fixed system prompt and AGENTS.md composition

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Add `agentsMd` to `SettingsStore`; remove per-character overrides

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/data/SettingsStore.kt`

This task has no unit test (the app module has no test infra and DataStore I/O is integration-level); it is verified by the app build in Task 6. Keep the diff mechanical.

- [ ] **Step 1: Add the `agentsMd` field to `Settings`**

In `SettingsStore.kt`, add to the `Settings` data class (after `lifetimeCostUsd`):

```kotlin
    /** User-editable agent instructions (Layer 1, `AGENTS.md`). Empty by default. */
    val agentsMd: String = "",
```

- [ ] **Step 2: Map it in `toSettings()`**

Add to the `Preferences.toSettings()` builder:

```kotlin
        agentsMd = this[AGENTS_MD] ?: "",
```

- [ ] **Step 3: Add the preference key**

In the `companion object`, add:

```kotlin
        val AGENTS_MD = stringPreferencesKey("agents_md")
```

- [ ] **Step 4: Add the setter**

Alongside the other `set...` methods:

```kotlin
    suspend fun setAgentsMd(value: String) =
        context.settingsDataStore.edit { it[AGENTS_MD] = value }.let { }
```

- [ ] **Step 5: Remove the per-character override surface**

Delete:
- the `CharacterModels` data class (currently `SettingsStore.kt:35`);
- `setCharacterModels(name, models)` and `characterModels(name)` methods;
- the `chatKey(name)` / `imageKey(name)` helpers in the companion object.

Update **both** KDocs that mention per-character overrides: the `Settings` data class KDoc (SettingsStore.kt:19 — change to "model defaults, FX intensity, lifetime spend, and the agent instructions doc") and the `SettingsStore` class KDoc (SettingsStore.kt:37-41 — drop the "Per-character overrides are keyed by character name…" sentence).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/data/SettingsStore.kt
git commit -m "feat(settings): store global AGENTS.md; drop per-character model overrides

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Rewire `ChatViewModel` to the two-layer prompt; remove card logic

**Files:**
- Modify: `app/build.gradle.kts:39` (swap module dependency)
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt:128` (repoint the deleted getters)

No unit test (ViewModel depends on Android + Metro); verified by Task 6's app build.

- [ ] **Step 1: Swap the module dependency**

In `app/build.gradle.kts`, replace:

```kotlin
    implementation(project(":core:character"))
```

with:

```kotlin
    implementation(project(":core:agent"))
```

- [ ] **Step 2: Fix imports**

In `ChatViewModel.kt`, remove these imports:

```kotlin
import io.orangerabbit.clanker.core.character.CharacterCard
import io.orangerabbit.clanker.core.character.CharacterCardParser
import io.orangerabbit.clanker.core.character.Persona
import io.orangerabbit.clanker.data.CharacterModels
```

and add:

```kotlin
import io.orangerabbit.clanker.core.agent.composeSystemPrompt
```

- [ ] **Step 3: Trim `UiState`**

Remove the `characterChatModel`, `characterImageModel`, and `character` fields, and both computed getters (`effectiveChatModel`, `effectiveImageModel`). Add:

```kotlin
        /** User-editable agent instructions (Layer 1, `AGENTS.md`). */
        val agentsMd: String = "",
```

Update the class KDoc (currently mentions "per-character overrides" and "character override ?: global default") to describe the fixed system prompt + `AGENTS.md` model.

- [ ] **Step 4: Feed `agentsMd` from settings**

In the `settingsStore.settings.collect { s -> ... }` block in `init`, add to the `copy(...)`:

```kotlin
                        agentsMd = s.agentsMd,
```

- [ ] **Step 5: Add the `setAgentsMd` action; remove `setCharacterModels`**

Delete `setCharacterModels(...)`. Add:

```kotlin
    fun setAgentsMd(value: String) {
        _state.update { it.copy(agentsMd = value) }
        viewModelScope.launch { settingsStore.setAgentsMd(value) }
    }
```

- [ ] **Step 6: Remove `applyCardBytes` and `isPng`**

Delete the entire `applyCardBytes(bytes: ByteArray)` function and the `isPng(bytes)` helper.

- [ ] **Step 7: Replace persona composition and model selection in `send`**

In `send`, replace the system-message block and request construction:

```kotlin
            val provider = openRouterProvider(apiKey = current.apiKey, engine = engine)
            // The agent definition (fixed) + AGENTS.md (user) is composed at request time,
            // never stored in history, so edits apply retroactively.
            val systemMessages = listOf(
                ChatMessage.System(MessageId(newId()), composeSystemPrompt(current.agentsMd)),
            )
            val request = ChatRequest(
                model = if (current.imageMode) current.defaultImageModel else current.defaultChatModel,
                messages = systemMessages + history,
                modalities = if (current.imageMode) listOf("image", "text") else emptyList(),
            )
```

- [ ] **Step 8: Repoint the `ChatScreen` model consumer**

`ChatScreen.kt:128` reads the deleted getters. Replace:

```kotlin
        val activeModel = if (state.imageMode) state.effectiveImageModel else state.effectiveChatModel
```

with:

```kotlin
        val activeModel = if (state.imageMode) state.defaultImageModel else state.defaultChatModel
```

Then confirm no other `ChatScreen` reference to the removed symbols remains:

Run: `grep -n "effectiveChatModel\|effectiveImageModel\|state.character" app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt`
Expected: no matches.

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatViewModel.kt app/src/main/kotlin/io/orangerabbit/clanker/ui/ChatScreen.kt
git commit -m "feat(ui): compose system prompt + AGENTS.md per request; remove card logic

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Replace the persona/override UI with an `AGENTS.md` editor

**Files:**
- Modify: `app/src/main/kotlin/io/orangerabbit/clanker/ui/SettingsScreen.kt`

- [ ] **Step 1: Remove the card picker**

Delete the `cardPicker` launcher block (`SettingsScreen.kt:58-65`). Remove the now-unused imports: `rememberLauncherForActivityResult`, `ActivityResultContracts`, `Dispatchers`. Keep `rememberCoroutineScope`/`scope` only if still referenced elsewhere; if not, remove `scope` and the `launch`/`rememberCoroutineScope` imports too.

- [ ] **Step 2: Remove the per-character override dropdowns**

Delete the `state.character?.let { card -> ... }` block (`SettingsScreen.kt:155-180`).

- [ ] **Step 3: Replace the "persona" section with an "agent" section**

Replace the persona section header + IMPORT PERSONA button (`SettingsScreen.kt:182-192`) with a multiline `AGENTS.md` editor:

```kotlin
        ThemedSectionHeader(title = "agent", accentColor = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(
            value = state.agentsMd,
            onValueChange = viewModel::setAgentsMd,
            label = { Text("AGENT INSTRUCTIONS (AGENTS.md)", style = MaterialTheme.typography.labelMedium) },
            placeholder = {
                Text(
                    "Project conventions and preferences, e.g. 'use conventional commits.'",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = 4,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
```

- [ ] **Step 4: Stop passing `includeDefaultOption`**

The two remaining `ModelDropdown` calls (default chat/image) never set `includeDefaultOption`, so no change is needed there. The `USE_DEFAULT` const and the `includeDefaultOption` path in `ModelDropdown` are now dead. Remove the `USE_DEFAULT` const (line 50) and the `includeDefaultOption` parameter + its `DropdownMenuItem` branch from `ModelDropdown` to avoid unused-symbol warnings.

- [ ] **Step 5: Verify no dangling `state.character` references remain**

Run: `grep -n "character\|Persona\|cardPicker\|USE_DEFAULT\|includeDefaultOption" app/src/main/kotlin/io/orangerabbit/clanker/ui/SettingsScreen.kt`
Expected: no matches.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/orangerabbit/clanker/ui/SettingsScreen.kt
git commit -m "feat(ui): replace persona import with an AGENTS.md editor in settings

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Delete the `:core:character` module and the orphaned domain ids

**Files:**
- Delete: `core/character/` (entire module)
- Modify: `settings.gradle.kts` (remove the include)
- Modify: `core/model/src/commonMain/kotlin/io/orangerabbit/clanker/core/model/Conversation.kt`
- Modify: `core/model/src/commonMain/kotlin/io/orangerabbit/clanker/core/model/Ids.kt`

Do this only after Tasks 3–4 (nothing references `:core:character` anymore).

- [ ] **Step 1: Confirm there are no remaining references**

Run: `grep -rn -e 'core\.character' -e 'CharacterCard' -e 'Persona' -e 'CharacterModels' --include='*.kt' app/src core/model/src core/network/src core/designsystem/src`
Expected: no matches.

- [ ] **Step 2: Remove the module include and dependency**

- In `settings.gradle.kts`, delete `include(":core:character")`.
- Confirm `app/build.gradle.kts` no longer has `implementation(project(":core:character"))` (removed in Task 3).

- [ ] **Step 3: Delete the module directory**

```bash
git rm -r core/character
```

- [ ] **Step 4: Drop `characterId` from `Conversation`**

In `Conversation.kt`, remove the `val characterId: CharacterId?,` field and replace the class KDoc (which currently describes persona-at-request-time) with:

```kotlin
/**
 * A chat thread. The agent definition (fixed system prompt + AGENTS.md) is composed into the
 * System message at request time rather than baked into stored history, so edits apply
 * retroactively. An agent-profile reference will return here when profiles land.
 */
```

- [ ] **Step 5: Drop the orphaned `CharacterId`**

In `Ids.kt`, remove:

```kotlin
@JvmInline
value class CharacterId(val value: String)
```

- [ ] **Step 6: Verify the model module still compiles**

Run: `nix develop --command bash -c './gradlew :core:model:jvmTest'`
Expected: PASS (or BUILD SUCCESSFUL with no tests).

- [ ] **Step 7: Commit**

```bash
git add -A core/character settings.gradle.kts core/model
git commit -m "refactor: delete :core:character module and orphaned CharacterId

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Full build, app assembly, and DESIGN.md update

**Files:**
- Modify: `DESIGN.md` (§4, §7, §14)

- [ ] **Step 1: Run the full module test suite**

Run: `nix develop --command bash -c './gradlew :core:agent:jvmTest :core:network:jvmTest :core:model:jvmTest'`
Expected: PASS.

- [ ] **Step 2: Assemble the app**

Run: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`
Expected: BUILD SUCCESSFUL, APK produced. (This is the real check that the card removal left no dangling references.)

- [ ] **Step 3: Update DESIGN.md**

- §4: replace the "Persona is composed into the System message at request time" description with the two-layer model (fixed `SystemPrompt` + global `AGENTS.md`, composed at request time).
- §7: remove/rewrite the Character Card V2/V3 section — clanker no longer imports cards; identity is the system prompt + `AGENTS.md`.
- §14: add a short new subsection titled "Decision log / reversals" (§14 is currently "Open Decisions for the User" — do not edit that table) recording:

  > **2026-06-24 — Persona/character cards removed.** Clanker is an agent harness only. The SillyTavern character-card path (`:core:character`, `Persona`, macros, per-character overrides) is deleted in favour of a two-layer prompt: a fixed, app-versioned system prompt plus a single user-editable global `AGENTS.md`, composed at request time. Multiple agent profiles, per-conversation persistence (Room), and tool descriptions in the prompt are sequenced as later work. Spec: `docs/superpowers/specs/2026-06-24-agent-definition-layering-design.md`.

- [ ] **Step 4: Commit**

```bash
git add DESIGN.md
git commit -m "docs(design): record character-card removal and two-layer prompt model

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Done when

- `:core:agent:jvmTest` passes (5 tests) and the fixed prompt is capability-honest (no invented tools).
- `:app:assembleDebug` succeeds with no references to `:core:character`, `Persona`, `CharacterCard`, `CharacterModels`, or `CharacterId`.
- Settings shows an editable `AGENTS.md` field; no persona-import or per-character override UI remains.
- Sending a message always includes a System message = `SystemPrompt.TEXT` (+ `AGENTS.md` when non-blank), built per request and never stored in history.
- `DESIGN.md` reflects the reversal.
