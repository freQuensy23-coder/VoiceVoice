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

    @Test
    fun unchangedTextAndClearDoNotProduceCorrections() {
        val tracker = CorrectionTracker()
        tracker.begin(receipt(prefix = "", inserted = "text", suffix = ""))
        tracker.onTextChanged(target, "text")
        assertNull(tracker.consumePendingCorrection())

        tracker.onTextChanged(target, "edited")
        tracker.clear()
        assertFalse(tracker.hasActiveSession())
        assertNull(tracker.consumePendingCorrection())
    }

    @Test
    fun sequentialCorrectionsUsePreviousCorrectionAsSource() {
        val tracker = CorrectionTracker()
        tracker.begin(receipt(prefix = "Before ", inserted = "helo", suffix = " after"))

        tracker.onTextChanged(target, "Before hello after")
        val first = tracker.consumePendingCorrection()
        tracker.onTextChanged(target, "Before Hello after")
        val second = tracker.consumePendingCorrection()

        assertEquals("helo", first?.originalText)
        assertEquals("hello", first?.correctedText)
        assertEquals("hello", second?.originalText)
        assertEquals("Hello", second?.correctedText)
        assertEquals("Before Hello after", second?.fullFieldText)
    }

    @Test
    fun targetWithoutViewIdMatchesOnlySameClassAndBounds() {
        val anonymousTarget = target.copy(viewId = null)
        val tracker = CorrectionTracker()
        tracker.begin(receipt(prefix = "", inserted = "text", suffix = "", target = anonymousTarget))

        tracker.onTextChanged(anonymousTarget.copy(bounds = NodeBounds(1, 0, 500, 120)), "wrong")
        assertNull(tracker.consumePendingCorrection())

        tracker.onTextChanged(anonymousTarget, "right")
        assertEquals("right", tracker.consumePendingCorrection()?.correctedText)
    }

    private fun receipt(prefix: String, inserted: String, suffix: String) = AutoInsertionReceipt(
        target = target,
        prefix = prefix,
        insertedText = inserted,
        suffix = suffix,
        fullTextAfterInsertion = prefix + inserted + suffix,
    )

    private fun receipt(
        prefix: String,
        inserted: String,
        suffix: String,
        target: TargetIdentity,
    ) = AutoInsertionReceipt(
        target = target,
        prefix = prefix,
        insertedText = inserted,
        suffix = suffix,
        fullTextAfterInsertion = prefix + inserted + suffix,
    )
}
