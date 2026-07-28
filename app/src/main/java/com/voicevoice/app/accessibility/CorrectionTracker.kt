package com.voicevoice.app.accessibility

import com.voicevoice.app.model.AutoInsertionReceipt
import com.voicevoice.app.model.TargetIdentity

data class CorrectionCandidate(
    val originalText: String,
    val correctedText: String,
    val fullFieldText: String,
)

/**
 * Tracks one field only after a successful automatic insertion.
 * Clipboard copies never create a session, so manual clipboard insertion is not tracked.
 */
class CorrectionTracker {
    private var session: Session? = null

    fun begin(receipt: AutoInsertionReceipt) {
        session = Session(
            target = receipt.target,
            prefix = receipt.prefix,
            suffix = receipt.suffix,
            insertedText = receipt.insertedText,
            fullText = receipt.fullTextAfterInsertion,
            pendingText = null,
        )
    }

    fun clear() {
        session = null
    }

    fun onTextChanged(target: TargetIdentity, fullText: String) {
        val current = session ?: return
        if (!sameAccessibilityTarget(current.target, target)) return
        if (fullText == current.fullText) return
        current.pendingText = fullText
    }

    fun consumePendingCorrection(): CorrectionCandidate? {
        val current = session ?: return null
        val changed = current.pendingText ?: return null
        current.pendingText = null

        if (!changed.startsWith(current.prefix) || !changed.endsWith(current.suffix)) return null
        val start = current.prefix.length
        val end = changed.length - current.suffix.length
        if (end < start) return null
        val corrected = changed.substring(start, end)
        if (corrected == current.insertedText) {
            current.fullText = changed
            return null
        }

        val candidate = CorrectionCandidate(
            originalText = current.insertedText,
            correctedText = corrected,
            fullFieldText = changed,
        )
        current.insertedText = corrected
        current.fullText = changed
        return candidate
    }

    fun hasActiveSession(): Boolean = session != null

    private data class Session(
        val target: TargetIdentity,
        val prefix: String,
        val suffix: String,
        var insertedText: String,
        var fullText: String,
        var pendingText: String?,
    )
}

internal fun sameAccessibilityTarget(expected: TargetIdentity, actual: TargetIdentity): Boolean {
    if (expected.packageName != actual.packageName) return false
    if (!expected.viewId.isNullOrBlank() || !actual.viewId.isNullOrBlank()) {
        return expected.viewId == actual.viewId
    }
    return expected.className == actual.className && expected.bounds == actual.bounds
}
