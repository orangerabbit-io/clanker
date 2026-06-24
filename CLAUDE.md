# clanker

Native Android agentic AI harness (KMP). Architecture & confirmed decisions: see `DESIGN.md` (§14 for the decision log).

## Build & test

The Android SDK + JDK come from the Nix flake; there is no system toolchain.
AGP needs the SDK at *configuration* time, so every Gradle run goes through `nix develop`.

- Build app: `nix develop --command bash -c 'AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"; ./gradlew -Pandroid.aapt2FromMavenOverride="$AAPT2" :app:assembleDebug'`
- Run unit tests: same wrapper, task `:core:network:jvmTest` (tests run on the KMP `jvm()` target).
- Live API check: `OPENROUTER_API_KEY=... ./gradlew :core:network:jvmTest --tests '*LiveOpenRouterTest' --rerun-tasks` (CIO engine; self-skips without the key).
- The `aapt2FromMavenOverride` `-P` flag is REQUIRED on Nix — Gradle's bundled aapt2 won't run. The flake env-var form does not work (bash can't hold dotted var names).

## Gotchas (cost real time to discover)

- AGP 9 has built-in Kotlin: do NOT apply `org.jetbrains.kotlin.android`.
- KMP libraries use `com.android.kotlin.multiplatform.library` + `kotlin { android { } }` — NOT `kotlin.multiplatform` + `com.android.library`.
- AGP 9.1.1 requires Gradle ≥ 9.3.1 (set in the wrapper).
- Metro requires Kotlin ≥ 2.3 and its version tracks the Kotlin compiler — bump them together.
- All plugins are declared `apply false` in the root build so modules share one classpath version.
- compileSdk is 36 (nixpkgs pin caps platforms at 36, cmdline-tools at 20.0); `lifecycle` is pinned 2.9.4 because 2.10+ demands compileSdk 37.

## Conventions

- Package root `io.orangerabbit.clanker`. `:core:*` modules stay UI-free (KMP-cheap-later).
- Wire/OpenAI DTOs live ONLY in `:core:network` and never escape the `LlmProvider` boundary.
- Provider/decoder logic is test-driven (`OpenAiSseDecoder`, `OpenAiCompatibleProvider`).
- No plaintext secrets persisted; API keys are in-memory until Tink/Keystore lands.
