# VoiceVoice architecture

## Runtime components

`VoiceVoiceAccessibilityService` owns the accessibility overlay, active-screen capture, focused-field insertion, and correction event handling.

`RecordingGateActivity` is a transparent, non-touchable, non-focusable activity opened directly by the user's floating-control tap. It starts the microphone foreground service while VoiceVoice has the visible while-in-use state required by modern Android versions, stays alive for the recording lifetime, and closes before context collection. The external editable field keeps input focus.

`RecordingForegroundService` owns microphone capture. Production recording uses 16 kHz, mono, PCM-16 `AudioRecord` and writes a WAV file. The service declares the microphone foreground-service type. Deterministic recording is compile-time restricted to debug builds.

`VoicePipeline` orchestrates collection, transcription, post-processing, clipboard delivery, optional insertion, and history without knowing provider-specific request parameters.

`AppGraph` constructs repositories, collectors, providers, and the pipeline. The production UI contains setup, model settings, API-key storage, local-model state, and history.

## DataCollector

```kotlin
interface DataCollector {
    fun collect(snapshot: AccessibilitySnapshot): DataCollectionResult
}

data class DataCollectionResult(
    val contextForLlm: String,
    val audioModelTerms: List<String>,
)
```

The return object contains exactly two downstream values. Tree acquisition is outside the collector contract.

`AccessibilitySnapshotFactory` reads application windows matching the active package. Accessibility overlays, the keyboard, and unrelated system windows are excluded before tree copying. Password-node text and descriptions are removed.

`GeneralDataCollector` traverses the available snapshot, bounds context size, and extracts handles, names, identifiers, variables, and other likely vocabulary terms.

`TelegramDataCollector` and `WhatsAppDataCollector` produce conversation-oriented context. `DataCollectorRegistry` selects them by package name and uses the general collector for every other application.

## VoiceProvider

```kotlin
interface VoiceProvider {
    suspend fun transcribe(
        recordedAudio: RecordedAudio,
        audioModelTerms: List<String>,
        settings: Settings,
    ): String
}
```

The rest of the application does not construct model parameters. `OpenRouterVoiceProvider` internally chooses the transcription endpoint request shape, validates language hints, and places vocabulary into supported provider-specific options. The provider receives only recorded audio, the collector's string list, and `Settings`.

## LlmProvider

```kotlin
interface LlmProvider {
    suspend fun postProcess(transcribedText: String, context: String): String
}
```

`OpenRouterLlmProvider` receives its model/settings when constructed. Call sites pass only the transcript and context for post-processing.

## Settings

`Settings` stores provider IDs, model IDs, language configuration, auto-insert behavior, history behavior, local-model IDs, and the OpenRouter key. `SecureSettingsRepository` encrypts the key through an Android Keystore AES-GCM key and stores non-secret configuration in private preferences.

No CI secret is copied into an Android build. Runtime users enter their own key in the application.

## Processing sequence

```text
Accessibility floating Start
  -> transparent RecordingGateActivity reaches visible state
  -> RecordingForegroundService starts AudioRecord
Accessibility floating Stop
  -> recording gate closes
  -> WAV finalized
  -> DataCollectorRegistry selects collector for the active application
  -> DataCollectionResult(contextForLlm, audioModelTerms)
  -> VoiceProvider(recordedAudio, audioModelTerms, Settings)
  -> LlmProvider.postProcess(transcription, contextForLlm)
  -> clipboard
  -> focused editable field, when available and enabled
  -> local history
  -> correction session only after successful automatic insertion
```

The overlay displays starting, recording, processing, success, and error states. It remains non-focusable so the external editable field keeps input focus.

## Correction tracking

A correction session is created only from `registerAutomaticInsertion`, which is called only after `ACTION_SET_TEXT` succeeds. The session records:

- target package plus view ID or class/bounds identity;
- stable text prefix and suffix;
- the exact inserted segment;
- the expected full field text.

`TYPE_VIEW_TEXT_CHANGED` events are accepted only from the same target. Changes are debounced. A correction is stored only when the stable prefix and suffix still delimit the inserted segment and that segment changed.

Clipboard-only delivery clears any previous correction session and does not create a new one. Therefore text manually pasted from the clipboard is not correction-tracked when it was not automatically inserted by VoiceVoice.

## History

`SqliteHistoryRepository` stores `TRANSCRIPTION` and `CORRECTION` entries. Corrections retain the previous inserted segment as `sourceText`. Accessibility context and API keys are never written to history.

## Local models

Local providers can be added behind the same provider interfaces. `ExplicitLocalModelManager` has no startup side effects. A caller must explicitly request a descriptor download. The manager requires HTTPS across redirects, rejects unsafe IDs and paths, checks free storage, validates SHA-256, atomically installs the file in the private model directory, and then records its ID in settings.

## Agent test mode

`ManualTestHostActivity` exists only in the debug source set. It selects deterministic implementations without network access, provides an editable target and context terms, and exposes user-driven correction controls. The production manifest does not contain this activity.

The existing isolated Codex harness installs the debug APK, enables the Accessibility Service through ADB, interacts with the same recording gate, overlay, pipeline, insertion, history, and correction code, and captures screenshot/XML evidence for every verdict.
