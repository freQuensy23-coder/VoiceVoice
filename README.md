# VoiceVoice

[![Android CI](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/android-ci.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/android-ci.yml)
[![Main APK](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/main-apk.yml/badge.svg?branch=main)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/main-apk.yml)
[![Manual Test Build](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/manual-test-build.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/manual-test-build.yml)
[![Codex Android Manual Tests](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/codex-manual-tests.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/codex-manual-tests.yml)

VoiceVoice is an Android background voice-transcription application built around an Accessibility Service and a floating microphone control. It records speech, collects context from the active accessibility tree, transcribes audio through a pluggable `VoiceProvider`, post-processes through a pluggable `LlmProvider`, copies the result to the clipboard, inserts it into the focused editable field when possible, and stores local history.

## Implemented flow

1. Enable the VoiceVoice Accessibility Service and grant microphone permission.
2. A floating `Start` control is shown over applications.
3. Tap `Start`, speak, then tap `Stop`. A transparent, non-focusable recording gate gives the microphone foreground service the visible while-in-use state required by modern Android versions without taking input focus from the selected field.
4. `DataCollector` extracts an LLM context string and a vocabulary list from the active application's accessibility tree.
5. `VoiceProvider` transcribes the WAV recording.
6. `LlmProvider` corrects the transcript using the context string.
7. VoiceVoice copies the final text to the clipboard, inserts it into the focused editable field when enabled, and writes history.
8. Accessibility text changes are logged as corrections only when VoiceVoice previously inserted text automatically into that same field. Clipboard-only delivery clears correction tracking and creates no new session.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for contracts and implementation details.

## Providers

The production implementation currently includes OpenRouter providers:

- Speech-to-text: `openai/whisper-large-v3` by default, sent to OpenRouter's dedicated transcription endpoint.
- Post-processing: `openai/gpt-5.6-luna` by default through chat completions.

Models are editable in the application. The OpenRouter key is entered at runtime and encrypted with an Android Keystore-backed AES-GCM key. It is never committed, logged, or compiled into the APK.

Local-provider interfaces are available. `ExplicitLocalModelManager` downloads only after an explicit `requestDownload` call, keeps redirects on HTTPS, rejects unsafe paths, verifies SHA-256, and then records the installed model ID. No local model is bundled or downloaded during startup.

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

## APK from `main`

Every push to `main`, including a merge commit, runs the [Main APK workflow](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/main-apk.yml). The workflow checks out the exact commit, builds an installable debug APK, generates its SHA-256 checksum, and uploads both files as the `voicevoice-apk-<commit SHA>` artifact for 30 days.

Open the workflow run for the required commit and download the artifact from its **Artifacts** section. Production distribution signing is intentionally separate from this commit-level development build.

## Manual agent testing

The existing standalone Codex Android manual-test harness remains the trust boundary. Its complete security and runtime contract is documented in [manual_test/README.md](manual_test/README.md). `manual_test/tests.yaml` now covers:

- production setup and secret-safe provider configuration;
- floating Start/Stop/loading/transcription flow;
- context-aware automatic insertion and history;
- correction tracking after automatic insertion;
- clipboard-only delivery, manual paste, and the required absence of correction tracking;

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
- Accessibility overlays, keyboards, and unrelated system windows are excluded from the active application snapshot.
- Context is bounded and sent only for the active transcription request; raw context is not stored in history.
- History is stored locally in SQLite and can be cleared from the app.
- API keys are encrypted at rest through Android Keystore.
- Accessibility insertion and correction tracking are scoped to the currently focused editable node.
