# VoiceVoice

[![Android CI](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/android-ci.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/android-ci.yml)
[![Manual Test Build](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/manual-test-build.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/manual-test-build.yml)
[![Codex Android Manual Tests](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/codex-manual-tests.yml/badge.svg)](https://github.com/freQuensy23-coder/VoiceVoice/actions/workflows/codex-manual-tests.yml)

VoiceVoice is an Android background transcription application. An Accessibility service shows a draggable floating microphone over the current application. While `MainActivity` is visible, VoiceVoice prepares a persistent microphone foreground-service host. The floating button then starts and stops recording through that already-running host, which keeps background recording compatible with Android's while-in-use foreground-service restrictions.

## Processing flow

1. `MainActivity` prepares `VoiceRecordingService` while the application is visible and microphone permission is active.
2. `VoiceAccessibilityService` owns the `TYPE_ACCESSIBILITY_OVERLAY` microphone and sends start/stop commands to the prepared host.
3. `VoiceRecordingService` records AAC audio in an M4A container without being created from the background.
4. `AccessibilityTreeSnapshotter` creates a bounded immutable snapshot of the active Accessibility tree.
5. `DataCollectorRegistry` selects Telegram, WhatsApp, a registered custom collector, or the generic collector. Every collector returns exactly `CollectorResult(context, audioTerms)`.
6. `VoiceProvider` receives only `RecordedAudio`, the collector term list, and `AppSettings`.
7. `LlmProvider` receives only the transcription and collector context.
8. The final result is copied to the clipboard, inserted at the current selection when the same editable field is still focused, and persisted in SQLite history.
9. `CorrectionTracker` is armed only after successful automatic insertion. Debounced changes to that same Accessibility node are saved as correction history entries. Clipboard-only/manual insertions never arm tracking.

If Android terminates the prepared recorder host, the floating control reports that VoiceVoice must be opened again before another background recording can start. It never attempts to create a microphone foreground service silently from another application's screen.

## Providers

The production implementation uses OpenRouter:

- STT: `POST /api/v1/audio/transcriptions`, default model `openai/whisper-large-v3`.
- Post-processing/translation: `POST /api/v1/chat/completions`, default model `google/gemini-3.6-flash`.
- Terms extracted from the current screen are passed only inside `OpenRouterVoiceProvider`; Groq receives them through its provider-specific vocabulary prompt.
- Accessibility context is marked as untrusted reference data in the LLM prompt to prevent instructions displayed by another application from controlling processing.

Provider-specific options stay inside each provider implementation. The rest of the application only knows the interfaces. A `LocalModelDownloadManager` contract makes local model downloads explicit and user-triggered; no model download starts during application initialization.

## Configuration and secrets

Open the application, grant microphone permission, save an OpenRouter key, and enable the VoiceVoice Accessibility service. Opening VoiceVoice with microphone permission prepares the background recorder host before you switch to another application. The key is encrypted with an Android Keystore AES-GCM key and Android backup is disabled.

The repository and APK contain no API key. CI secrets are intentionally not converted into `BuildConfig` fields or packaged into pull-request artifacts. Debug manual tests use a valid local WAV fixture plus deterministic providers while exercising the production foreground-host lifecycle, Accessibility overlay, collection, clipboard, insertion, history, and correction code.

## Translation

Enable **Translate result** and choose a target language. Translation follows the same provider contracts. Translation results and later corrections are stored as separate history records.

## Build

Requirements:

- Android SDK 37
- JDK 17

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

The minimum supported version is Android 8.0 (API 26).

## Agent-driven manual tests

The existing standalone Codex Android harness remains unchanged. `manual_test/tests.yaml` contains two end-to-end scenarios:

- prepared recorder host → floating recording → processing → clipboard → automatic insertion → history;
- prepared recorder host in the background → translation → automatic insertion → user correction through the same editable field → correction history.

Each scenario runs on a fresh emulator and uses one autonomous agent plus inspected evidence screenshots. The harness implementation, authentication isolation, CI trust boundary, local commands, and report contract are documented in [`manual_test/README.md`](manual_test/README.md).
