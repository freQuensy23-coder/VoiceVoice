package com.voicevoice.app.accessibility

import kotlinx.coroutines.Job

internal enum class OverlayState {
    IDLE,
    RECORDING,
    PROCESSING,
    SUCCESS,
    ERROR,
}

/**
 * Process-scoped state survives AccessibilityService reconnects caused by
 * UiAutomation and system configuration changes.
 */
internal class AccessibilitySessionState {
    val correctionTracker = CorrectionTracker()
    var overlayState: OverlayState = OverlayState.IDLE
    var statusMessage: String = ""
    var deterministicDebugRecording: Boolean = false
    var deterministicDebugJob: Job? = null
    var correctionPersistenceJob: Job? = null
}
