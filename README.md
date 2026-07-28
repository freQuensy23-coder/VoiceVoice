# VoiceVoice

[![Android CI](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/android-ci.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/android-ci.yml)
[![Manual Test Build](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/manual-test-build.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/manual-test-build.yml)
[![Codex Android Manual Tests](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/codex-manual-tests.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/codex-manual-tests.yml)

VoiceVoice is an Android background voice-transcription application built around an Accessibility Service and a floating microphone control. It records speech, collects context from the active accessibility tree, transcribes audio through a pluggable `VoiceProvider`, post-processes through a pluggable `LlmProvider`, copies the result to the clipboard, inserts it into the focused editable field when possible, and stores local history.

The previous starter screen and local recording toggle mock have been removed.

## Implemented flow

1. Enable the VoiceVoice Accessibility Service and grant microphone permission.
2. A floating `Start` control is shown over applications.
3. Tap `Start`, speak, then tap `Stop`.
4. `DataCollector` extracts an LLM context string and a vocabulary list from the active accessibility tree.
5. `VoiceProvider` transcribes the WAV recording.
6. `LlmProvider` corrects the transcript using the context string.
7. VoiceVoice copies the final text to the clipboard, inserts it into the focused editable field when enabled, and writes history.
8. The floating `Translate` control translates the last result into the configured language.
9. Accessibility text changes are logged as corrections only when VoiceVoice previously inserted text automatically into that same field. Clipboard-only delivery creates no correction-tracking session.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for contracts and implementation details.

## Providers

The production implementation currently includes OpenRouter providers:

- Speech-to-text: `openai/whisper-large-v3` by default, sent to OpenRouter's dedicated transcription endpoint.
- Post-processing and translation: `openai/gpt-5.6-luna` by default through chat completions.

Models are editable in the application. The OpenRouter key is entered at runtime and encrypted with an Android Keystore-backed AES-GCM key. It is never committed, logged, or compiled into the APK.

Local-provider interfaces are available. `ExplicitLocalModelManager` performs an HTTPS download only after an explicit `requestDownload` call, verifies SHA-256, then records the installed model ID. No local model is bundled or downloaded during startup.

## Build

Requirements:

- Android SDK 37
- JDK 17

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

VoiceVoice supports Android 8.0 (API 26) and newer and targets API 37.

## Manual agent testing

The existing standalone Codex Android manual-test harness remains the trust boundary. `manual_test/tests.yaml` now covers:

- production setup and secret-safe provider configuration;
- floating Start/Stop/loading/transcription flow;
- context-aware automatic insertion and history;
- correction tracking after automatic insertion;
- translation replacement and correction tracking.

Debug agent tests use a deterministic recorder and deterministic providers selected only by the debug test host. Production builds always use the configured provider implementations.

From `manual_test/`:

```bash
uv sync --extra test
uv run pytest
uv run ruff check .
uv build
```

The credentialed Codex workflow remains restricted to trusted owner-authored pull requests and publishes evidence-backed results as the `codex-android-manual-tests` commit status.

## Privacy behavior

- Password accessibility nodes are excluded from collected context.
- Context is bounded and sent only for the active transcription or translation request; raw context is not stored in history.
- History is stored locally in SQLite and can be cleared from the app.
- API keys are encrypted at rest through Android Keystore.
- Accessibility insertion and correction tracking are scoped to the currently focused editable node.
