# Phase 1 On-Device Smoke Checklist (Operator Runbook)

This checklist is run by an operator on real hardware **after merge**. The signed
release APK ships via the release pipeline (Task 12 CI attaches
`clanker-v<version>.apk` to each GitHub Release); the operator installs it via
Obtainium. The iOS pass requires a macOS machine (compile is verified locally in
CI; linking/framework + simulator/device run are macOS-only).

Fill in the results table at the bottom. Each fix discovered by this run gets
its own conventional commit.

## Prerequisites

- **Android device** (minSdk 28), one of:
  - **Obtainium**: point it at this repo's GitHub Releases and install the
    latest `clanker-v<version>.apk`; or
  - **adb**: enable USB debugging, then `adb install clanker-v<version>.apk`.
    For the corruption test in item 2, a **debug build** is required
    (`nix develop -c ./gradlew :composeApp:assembleDebug`, then
    `adb install composeApp/build/outputs/apk/debug/app-debug.apk`) because
    release builds are not `run-as`-inspectable.
- **OpenRouter account with credits**, and an API key available for the
  documented paste-code fallback (or complete the PKCE `clanker://oauth`
  connect flow).
- **iOS pass (optional second pass)**: macOS machine with Xcode and the
  simulator/device; same checklist items, results recorded in the same table.
  Note for item 2 on iOS: secrets live in the OS Keychain, which cannot be
  corrupted from a shell — record the negative test as N/A there and rely on
  the fail-closed unit contract instead.

## Smoke items

### 1. Paste-code connect: key stored encrypted, survives restart

1. Launch the app → Settings/Connect → choose the documented paste-code
   fallback → paste the OpenRouter key → connect.
2. Confirm the app reports connected.
3. Force-stop and relaunch:
   `adb shell am force-stop io.orangerabbit.clanker` then relaunch from the
   launcher (or just relaunch on iOS).

**Expected:** still connected on relaunch — no re-connect prompt. The key is
stored as Tink AEAD ciphertext (Android) / Keychain item (iOS), never
plaintext.

### 2. Negative: corrupt secret-store ciphertext → fail closed

Concrete procedure (Android, debug build):

1. Confirm the ciphertext file exists (the filename is the URL-safe Base64 of
   the secret id `openrouter_key`):
   ```bash
   adb shell run-as io.orangerabbit.clanker ls files/secrets/
   # expect: b3BlbnJvdXRlcl9rZXk
   ```
2. Corrupt it:
   ```bash
   adb shell run-as io.orangerabbit.clanker \
     sh -c 'echo not-ciphertext > files/secrets/b3BlbnJvdXRlcl9rZXk'
   ```
3. Force-stop and relaunch the app.
4. Verify fail-closed behavior:
   - The app surfaces a decryption/storage error instead of silently showing
     "connected"; chat cannot send with a bad key.
   - No plaintext key is readable in app files:
     ```bash
     adb shell run-as io.orangerabbit.clanker sh -c 'grep -r sk-or files/'
     # expect: no matches
     ```
5. Restore: reconnect via paste-code — `put` overwrites the corrupted file
   with fresh valid ciphertext; relaunch and confirm connected.

**Expected:** fail closed with an error; no plaintext key anywhere in app
files; app recovers after reconnect.

### 3. Web search: sources row, request count, cost

1. New chat. Ensure the WEB_SEARCH tool is enabled in Settings.
2. Ask something requiring current information
   (e.g. "What is today's date and one headline from this week?").
3. Watch the answer area and the usage/cost row.

**Expected:** a sources row appears for the answer; the usage shows
`web_search_requests` > 0; the per-request cost is displayed.

### 4. Mid-stream interruption (airplane mode)

1. Start a chat that produces a long streaming answer.
2. Mid-stream, toggle airplane mode on.
3. Observe the partial answer, then toggle airplane mode off and send again.

**Expected:** the partial answer is preserved and marked "interrupted";
after connectivity returns, the next send works normally.

### 5. Per-request spend cap

1. In Settings → spend caps, set the per-request cap to `0.01` USD.
2. Send a request large enough to exceed it (long answer, or one that
   triggers server tools).

**Expected:** the "spend cap reached" banner appears and the budget-exhausted
state is sticky (no silent retry). Reset the cap afterwards.

### 6. Keep-awake during runs

1. Start a streaming run and let the screen idle past the device's normal
   timeout.
2. After the run completes, wait through the normal idle window.

**Expected:** the screen stays awake while a run is in flight; after the run,
normal screen-timeout behavior resumes.

## Results table

| # | Item | Android result | iOS result | Notes |
|---|------|----------------|------------|-------|
| 1 | Encrypted key persists across restart | TODO | TODO | |
| 2 | Corrupt ciphertext fails closed, no plaintext key | TODO | TODO (N/A: Keychain not shell-corruptible) | |
| 3 | Web search sources row + request count + cost | TODO | TODO | |
| 4 | Airplane-mode mid-stream: partial preserved, marked interrupted | TODO | TODO | |
| 5 | Per-request cap exceeded → "spend cap reached" banner | TODO | TODO | |
| 6 | Screen stays awake during run, normal timeout after | TODO | TODO | |

Fixes found during this run: (list commit SHAs + subjects here)