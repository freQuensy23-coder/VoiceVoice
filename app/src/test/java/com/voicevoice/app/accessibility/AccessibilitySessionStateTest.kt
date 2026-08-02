package com.voicevoice.app.accessibility

import com.voicevoice.app.model.AutoInsertionReceipt
import com.voicevoice.app.model.NodeBounds
import com.voicevoice.app.model.TargetIdentity
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
        assertNull(session.lastInsertionReceipt)
        assertFalse(session.deterministicDebugRecording)
        assertNull(session.deterministicDebugJob)
        assertNull(session.correctionPersistenceJob)
    }

    @Test
    fun recordingResultAndInsertionSurviveServiceInstanceChanges() {
        val session = AccessibilitySessionState()
        val receipt = receipt("Send Alexey the VoiceVoice architecture tomorrow.")
        val tracker = session.correctionTracker

        session.overlayState = OverlayState.PROCESSING
        session.statusMessage = "Transcribing"
        session.lastInsertionReceipt = receipt
        session.deterministicDebugRecording = true

        assertEquals(OverlayState.PROCESSING, session.overlayState)
        assertEquals("Transcribing", session.statusMessage)
        assertSame(receipt, session.lastInsertionReceipt)
        assertSame(tracker, session.correctionTracker)
        assertTrue(session.deterministicDebugRecording)
    }

    private fun receipt(text: String) = AutoInsertionReceipt(
        target = TargetIdentity(
            packageName = "com.example.notes",
            windowId = 1,
            viewId = "note",
            className = "android.widget.EditText",
            bounds = NodeBounds(0, 0, 100, 100),
        ),
        prefix = "",
        insertedText = text,
        suffix = "",
        fullTextAfterInsertion = text,
    )
}
