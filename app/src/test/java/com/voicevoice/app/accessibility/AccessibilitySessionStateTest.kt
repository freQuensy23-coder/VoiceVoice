package com.voicevoice.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySessionStateTest {
    @Test
    fun newSessionStartsIdleAndEmpty() {
        val session = AccessibilitySessionState()

        assertEquals(OverlayState.IDLE, session.overlayState)
        assertEquals("", session.statusMessage)
        assertFalse(session.deterministicDebugRecording)
        assertNull(session.deterministicDebugJob)
        assertNull(session.correctionPersistenceJob)
    }

    @Test
    fun recordingStateSurvivesServiceInstanceChanges() {
        val session = AccessibilitySessionState()
        val tracker = session.correctionTracker

        session.overlayState = OverlayState.PROCESSING
        session.statusMessage = "Transcribing"
        session.deterministicDebugRecording = true

        assertEquals(OverlayState.PROCESSING, session.overlayState)
        assertEquals("Transcribing", session.statusMessage)
        assertSame(tracker, session.correctionTracker)
        assertTrue(session.deterministicDebugRecording)
    }
}
