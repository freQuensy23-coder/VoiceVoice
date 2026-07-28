package com.voicevoice.app.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import com.voicevoice.app.AppContainer
import com.voicevoice.app.accessibility.AccessibilityTreeSnapshotter
import com.voicevoice.app.accessibility.CorrectionTracker
import com.voicevoice.app.accessibility.NodeFingerprint
import com.voicevoice.app.accessibility.TextInserter
import com.voicevoice.app.accessibility.VoiceAccessibilityService
import com.voicevoice.app.domain.HistoryKind
import com.voicevoice.app.domain.RecordedAudio
import com.voicevoice.app.domain.RuntimePhase
import com.voicevoice.app.settings.DebugModeController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProcessingCoordinator(
    private val service: VoiceAccessibilityService,
    private val container: AppContainer,
    private val correctionTracker: CorrectionTracker,
    private val snapshotter: AccessibilityTreeSnapshotter = AccessibilityTreeSnapshotter(),
    private val textInserter: TextInserter = TextInserter(),
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voicevoice-processing").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)

    fun process(recordedAudio: RecordedAudio) {
        if (!processing.compareAndSet(false, true)) {
            recordedAudio.file.delete()
            return
        }

        correctionTracker.clear()
        AppRuntime.update(RuntimePhase.PROCESSING, "Collecting screen context…")
        val insertionTarget = textInserter.captureFocusedTarget(service)
        val screen = snapshotter.capture(service)
        val collection = container.dataCollectors.collect(screen)

        executor.execute {
            try {
                val settings = container.settingsRepository.load()
                val deterministic = DebugModeController.isEnabled(service)
                val voiceProvider = container.providerFactory.voiceProvider(settings, deterministic)
                val llmProvider = container.providerFactory.llmProvider(settings, deterministic)

                AppRuntime.update(RuntimePhase.PROCESSING, "Transcribing audio…")
                if (deterministic) {
                    // Keep the loading state visible long enough for an autonomous visual agent to
                    // capture and reopen evidence without introducing sleeps into production flows.
                    Thread.sleep(MANUAL_TEST_PROCESSING_DELAY_MILLIS)
                }
                val transcription = voiceProvider.transcribe(
                    recordedAudio = recordedAudio,
                    audioTerms = collection.audioTerms,
                    settings = settings,
                ).trim()
                require(transcription.isNotEmpty()) { "The transcription is empty" }

                AppRuntime.update(
                    RuntimePhase.PROCESSING,
                    if (settings.translationEnabled) {
                        "Translating with screen context…"
                    } else {
                        "Correcting with screen context…"
                    },
                )
                val result = llmProvider.postProcess(
                    transcribedText = transcription,
                    context = collection.context,
                ).trim()
                require(result.isNotEmpty()) { "Post-processing returned empty text" }

                mainHandler.post {
                    finishSuccessfully(
                        settingsTranslationEnabled = settings.translationEnabled,
                        transcription = transcription,
                        result = result,
                        screenPackageName = screen.packageName,
                        insertionTarget = insertionTarget,
                    )
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    processing.set(false)
                    AppRuntime.update(
                        RuntimePhase.ERROR,
                        error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Transcription failed",
                    )
                }
            } finally {
                recordedAudio.file.delete()
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun finishSuccessfully(
        settingsTranslationEnabled: Boolean,
        transcription: String,
        result: String,
        screenPackageName: String,
        insertionTarget: NodeFingerprint?,
    ) {
        runCatching {
            val clipboard = service.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("VoiceVoice transcription", result))

            val insertion = insertionTarget?.let { target ->
                textInserter.insertIntoFocusedField(service, result, target)
            }
            val packageName = insertion?.fingerprint?.packageName
                ?: screenPackageName.takeIf(String::isNotBlank)
            val historyId = container.historyRepository.insertResult(
                kind = if (settingsTranslationEnabled) {
                    HistoryKind.TRANSLATION
                } else {
                    HistoryKind.TRANSCRIPTION
                },
                sourceText = transcription,
                resultText = result,
                packageName = packageName,
                automaticallyInserted = insertion != null,
            )
            if (insertion != null) {
                correctionTracker.arm(historyId, insertion)
            }

            processing.set(false)
            AppRuntime.update(
                RuntimePhase.COMPLETE,
                if (insertion != null) {
                    "Copied, inserted, and saved to history"
                } else {
                    "Copied and saved to history"
                },
            )
        }.onFailure { error ->
            processing.set(false)
            AppRuntime.update(
                RuntimePhase.ERROR,
                error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Unable to save the result",
            )
        }
    }

    companion object {
        private const val MANUAL_TEST_PROCESSING_DELAY_MILLIS = 15_000L
    }
}
