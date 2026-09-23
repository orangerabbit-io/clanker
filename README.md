# Clanker

Agentic OpenRouter client built with Kotlin Multiplatform and Compose.

## Features

- Streaming chat with the OpenRouter chat-completions API
- Server-side tools: `web_search`, `web_fetch`, `datetime`, with per-chat toggles and cited sources
- Per-chat spend caps (`stop_server_tools_when`) enforced by OpenRouter
- Markdown rendering, reasoning display, and cost reporting per response
- BYOK: bring your own key via OpenRouter OAuth (PKCE), stored encrypted on device
- Wire-faithful persistence: raw API payloads stored verbatim alongside typed fields (SQLDelight)

## Building

Requires Nix (for the pinned toolchain) and JDK 21.

```sh
nix develop
./gradlew :composeApp:assembleDebug   # debug APK
./gradlew :shared:jvmTest             # JVM test suite
```

Debug builds install with the debug signing key. Release APKs are built by CI
(`.github/workflows/release.yml`) and attached to GitHub releases.

## Install via Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app → source: GitHub → repository URL: `https://github.com/orangerabbit-io/clanker`
3. Track releases, APK filter (regex): `clanker-.*\.apk`

Notes:

- Updates only install when the release APK is signed with the same key as the
  installed build. If the signing key changes, uninstall first and reinstall.
- APK versioning is tag-driven: `versionName` comes from the semantic-release
  tag and `versionCode` from the commit count, so Obtainium can present updates
  after the first release.

## Maintainers

Release signing is driven by the `CLANKER_KEYSTORE`,
`CLANKER_KEYSTORE_PASSWORD`, `CLANKER_KEY_ALIAS`, and `CLANKER_KEY_PASSWORD`
repository secrets (`CLANKER_KEYSTORE` is the base64-encoded JKS). The same
signing key must be used for every release, or Obtainium users cannot update.

Post-release, run the on-device checklist in
[docs/phase1-smoke.md](docs/phase1-smoke.md).

## License

MIT — see [LICENSE](LICENSE).
