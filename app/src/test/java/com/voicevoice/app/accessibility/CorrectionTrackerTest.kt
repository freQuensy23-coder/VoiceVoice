package com.voicevoice.app.accessibility

import com.voicevoice.app.model.AutoInsertionReceipt
import com.voicevoice.app.model.NodeBounds
import com.voicevoice.app.model.TargetIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTrackerTest {
    private val target = TargetIdentity(
        packageName = "example.app",
        windowId = 7,
        viewId = "message",
        className = "android.widget.EditText",
        bounds = NodeBounds(0, 0, 500, 120),
    )

    @Test
    fun correctionsAreTrackedOnlyAfterAutomaticInsertion() {
        val tracker = CorrectionTracker()
        tracker.onTextChanged(target, "manual clipboard paste")
        assertNull(tracker.consumePendingCorrection())
        assertFalse(tracker.hasActiveSession())

        tracker.begin(receipt(prefix = "", inserted = "helo", suffix = ""))
        tracker.onTextChanged(target, "hello")
        val correction = tracker.consumePendingCorrection()

        assertTrue(tracker.hasActiveSession())
        assertEquals("helo", correction?.originalText)
        assertEquals("hello", correction?.correctedText)
    }

    @Test
    fun changesOutsideAnchoredInsertedSegmentAreIgnored() {
        val tracker = CorrectionTracker()
        tracker.begin(receipt(prefix = "Before ", inserted = "wrng", suffix = " after"))
        tracker.onTextChanged(target, "Changed prefix wrng after")
        assertNull(tracker.consumePendingCorrection())
    }

    @Test
    fun unrelatedFieldIsIgnored() {
        val tracker = CorrectionTracker()
        tracker.begin(receipt(prefix = "", inserted = "text", suffix = ""))
        tracker.onTextChanged(target.copy(viewId = "other"), "edited")
        assertNull(tracker.consumePendingCorrection())
    }

    private fun receipt(prefix: String, inserted: String, suffix: String) = AutoInsertionReceipt(
        target = target,
        prefix = prefix,
        insertedText = inserted,
        suffix = suffix,
        fullTextAfterInsertion = prefix + inserted + suffix,
    )
}
