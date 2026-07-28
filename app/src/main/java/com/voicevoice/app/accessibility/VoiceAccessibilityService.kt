package com.voicevoice.app.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.voicevoice.app.VoiceVoiceApplication
import com.voicevoice.app.domain.RuntimePhase
import com.voicevoice.app.domain.RuntimeState
import com.voicevoice.app.recording.VoiceRecordingService
import com.voicevoice.app.runtime.AppRuntime
import com.voicevoice.app.runtime.ProcessingCoordinator
import com.voicevoice.app.runtime.RecordingBridge
import com.voicevoice.app.settings.DebugModeController

class VoiceAccessibilityService : AccessibilityService() {
    private lateinit var overlay: FloatingMicOverlay
    private lateinit var correctionTracker: CorrectionTracker
    private lateinit var processingCoordinator: ProcessingCoordinator

    private val runtimeListener: (RuntimeState) -> Unit = { state ->
        if (::overlay.isInitialized) overlay.update(state)
    }
    private val recordingListener: (Result<com.voicevoice.app.domain.RecordedAudio>) -> Unit = { result ->
        result.onSuccess { audio -> processingCoordinator.process(audio) }
            .onFailure { error ->
                AppRuntime.update(
                    RuntimePhase.ERROR,
                    error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Recording failed",
                )
            }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val container = (application as VoiceVoiceApplication).container
        correctionTracker = CorrectionTracker(container.historyRepository)
        processingCoordinator = ProcessingCoordinator(this, container, correctionTracker)
        overlay = FloatingMicOverlay(this, ::onFloatingButtonClicked)
        overlay.show()
        AppRuntime.addListener(runtimeListener)
        RecordingBridge.setListener(recordingListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && ::correctionTracker.isInitialized) {
            correctionTracker.onAccessibilityEvent(event)
        }
    }

    override fun onInterrupt() {
        AppRuntime.update(RuntimePhase.ERROR, "Accessibility service was interrupted")
    }

    override fun onDestroy() {
        if (AppRuntime.state().phase == RuntimePhase.RECORDING) {
            runCatching { VoiceRecordingService.cancel(this) }
        }
        RecordingBridge.setListener(null)
        AppRuntime.removeListener(runtimeListener)
        if (::overlay.isInitialized) overlay.remove()
        if (::correctionTracker.isInitialized) correctionTracker.close()
        if (::processingCoordinator.isInitialized) processingCoordinator.close()
        super.onDestroy()
    }

    private fun onFloatingButtonClicked() {
        when (AppRuntime.state().phase) {
            RuntimePhase.RECORDING -> runCatching { VoiceRecordingService.stop(this) }
                .onFailure { error -> showRecorderError(error, "Unable to stop recording") }

            RuntimePhase.PROCESSING -> Unit
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppRuntime.update(RuntimePhase.ERROR, "Grant microphone permission in VoiceVoice")
            return
        }
        if (!VoiceRecordingService.isPrepared()) {
            AppRuntime.update(
                RuntimePhase.ERROR,
                "Open VoiceVoice once to prepare background recording",
            )
            return
        }

        val settings = (application as VoiceVoiceApplication).container.settingsRepository.load()
        val deterministic = DebugModeController.isEnabled(this)
        if (!deterministic && settings.voiceProviderId == "openrouter" && settings.apiKey.isBlank()) {
            AppRuntime.update(RuntimePhase.ERROR, "Configure an OpenRouter API key in VoiceVoice")
            return
        }
        runCatching { VoiceRecordingService.start(this) }
            .onFailure { error -> showRecorderError(error, "Unable to start recording") }
    }

    private fun showRecorderError(error: Throwable, fallback: String) {
        AppRuntime.update(
            RuntimePhase.ERROR,
            error.message?.take(180)?.takeIf(String::isNotBlank) ?: fallback,
        )
    }
}
