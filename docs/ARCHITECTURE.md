# VoiceVoice architecture

## Runtime components

`VoiceVoiceAccessibilityService` owns the accessibility overlay, active-screen capture, focused-field insertion, and correction event handling.

`RecordingForegroundService` owns microphone capture. Production recording uses 16 kHz, mono, PCM-16 `AudioRecord` and writes a WAV file. The service declares the microphone foreground-service type and is started only from the user's floating-control action.

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

`GeneralDataCollector` traverses the available snapshot, removes password-node values, bounds context size, and extracts handles, names, identifiers, variables, and other likely vocabulary terms.

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
    suspend fun translate(text: String, targetLanguage: String, context: String): String
}
```

`OpenRouterLlmProvider` receives its model/settings when constructed. Call sites pass only the transcript and context for post-processing. Translation adds the requested target language.

## Settings

`Settings` stores provider IDs, model IDs, language configuration, auto-insert behavior, history behavior, local-model IDs, and the OpenRouter key. `SecureSettingsRepository` encrypts the key through an Android Keystore AES-GCM key and stores non-secret configuration in private preferences.

No CI secret is copied into an Android build. Runtime users enter their own key in the application.

## Processing sequence

```text
Accessibility floating Start
  -> RecordingForegroundService starts AudioRecord
Accessibility floating Stop
  -> WAV finalized
  -> DataCollectorRegistry selects collector
  -> DataCollectionResult(contextForLlm, audioModelTerms)
  -> VoiceProvider(recordedAudio, audioModelTerms, Settings)
  -> LlmProvider.postProcess(transcription, contextForLlm)
  -> clipboard
  -> focused editable field, when available and enabled
  -> local history
  -> correction session only after successful automatic insertion
```

The overlay displays starting, recording, processing, success, and error states. It remains non-focusable so the external editable field keeps input focus.

## Translation sequence

The floating `Translate` action uses the last result or latest transcription/translation history entry. It collects fresh screen context, invokes `LlmProvider.translate`, copies the translation, and attempts to replace the previous automatic insertion in the same field. If replacement is no longer safe, normal focused-field insertion is attempted. A successful translation insertion starts a new correction session.

## Correction tracking

A correction session is created only from `registerAutomaticInsertion`, which is called only after `ACTION_SET_TEXT` succeeds. The session records:

- target package, window, view ID or class/bounds identity;
- stable text prefix and suffix;
- the exact inserted segment;
- the expected full field text.

`TYPE_VIEW_TEXT_CHANGED` events are accepted only from the same target. Changes are debounced. A correction is stored only when the stable prefix and suffix still delimit the inserted segment and that segment changed.

Clipboard delivery does not create a session. Therefore text manually pasted from the clipboard is not correction-tracked when it was not automatically inserted by VoiceVoice.

## History

`SqliteHistoryRepository` stores `TRANSCRIPTION`, `TRANSLATION`, and `CORRECTION` entries. Corrections retain the previous inserted segment as `sourceText`. Accessibility context and API keys are never written to history.

## Local models

Local providers can be added behind the same provider interfaces. `ExplicitLocalModelManager` has no startup side effects. A caller must explicitly request a descriptor download; the manager requires HTTPS, validates SHA-256, atomically installs the file, and then records its ID in settings.

## Agent test mode

`ManualTestHostActivity` exists only in the debug source set. It selects deterministic implementations without network access, provides an editable target and context terms, and exposes user-driven correction controls. The production manifest does not contain this activity.

The existing isolated Codex harness installs the debug APK, enables the Accessibility Service through ADB, interacts with the same overlay/pipeline/insertion/history code, and captures screenshot/XML evidence for every verdict.
