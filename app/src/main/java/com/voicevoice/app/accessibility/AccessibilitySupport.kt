package com.voicevoice.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voicevoice.app.collector.AccessibilityNodeSnapshot
import com.voicevoice.app.collector.AccessibilityScreen
import com.voicevoice.app.collector.NodeBounds
import com.voicevoice.app.history.HistoryRepository
import java.util.concurrent.Executors

class AccessibilityTreeSnapshotter {
    fun capture(service: AccessibilityService): AccessibilityScreen {
        val root = service.rootInActiveWindow
            ?: return AccessibilityScreen(
                packageName = "",
                windowId = -1,
                capturedAt = System.currentTimeMillis(),
                nodes = emptyList(),
            )
        return try {
            val nodes = ArrayList<AccessibilityNodeSnapshot>()
            walk(root, nodes, depth = 0)
            AccessibilityScreen(
                packageName = root.packageName?.toString().orEmpty(),
                windowId = root.windowId,
                capturedAt = System.currentTimeMillis(),
                nodes = nodes,
            )
        } finally {
            recycle(root)
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeSnapshot>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_NODES) return
        val bounds = Rect().also(node::getBoundsInScreen)
        output += AccessibilityNodeSnapshot(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            editable = node.isEditable,
            focused = node.isFocused,
            password = node.isPassword,
            bounds = NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )
        for (index in 0 until node.childCount) {
            if (output.size >= MAX_NODES) break
            val child = node.getChild(index) ?: continue
            try {
                walk(child, output, depth + 1)
            } finally {
                recycle(child)
            }
        }
    }

    companion object {
        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 50
    }
}

data class NodeFingerprint(
    val packageName: String,
    val windowId: Int,
    val viewId: String?,
    val className: String?,
    val bounds: NodeBounds,
) {
    fun matches(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName?.toString() != packageName || node.windowId != windowId) return false
        val actualViewId = node.viewIdResourceName
        if (viewId != null && actualViewId != null) return viewId == actualViewId

        val actualBounds = Rect().also(node::getBoundsInScreen)
        return node.className?.toString() == className &&
            actualBounds.left == bounds.left &&
            actualBounds.top == bounds.top &&
            actualBounds.right == bounds.right &&
            actualBounds.bottom == bounds.bottom
    }

    companion object {
        fun from(node: AccessibilityNodeInfo): NodeFingerprint {
            val bounds = Rect().also(node::getBoundsInScreen)
            return NodeFingerprint(
                packageName = node.packageName?.toString().orEmpty(),
                windowId = node.windowId,
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                bounds = NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            )
        }
    }
}

data class InsertionRecord(
    val fingerprint: NodeFingerprint,
    val previousFullText: String,
    val expectedFullText: String,
    val prefix: String,
    val suffix: String,
    val insertedText: String,
)

class TextInserter {
    fun captureFocusedTarget(service: AccessibilityService): NodeFingerprint? {
        val focused = obtainFocusedEditable(service) ?: return null
        return try {
            if (!focused.isEditable || !focused.isFocused || focused.isPassword) null
            else NodeFingerprint.from(focused)
        } finally {
            recycle(focused)
        }
    }

    fun insertIntoFocusedField(
        service: AccessibilityService,
        text: String,
        expectedTarget: NodeFingerprint,
    ): InsertionRecord? {
        if (text.isEmpty()) return null
        val focused = obtainFocusedEditable(service) ?: return null
        try {
            if (!focused.isEditable || !focused.isFocused || focused.isPassword) return null
            if (!expectedTarget.matches(focused)) return null

            val before = focused.text?.toString().orEmpty()
            val start = focused.textSelectionStart.takeIf { it in 0..before.length } ?: before.length
            val end = focused.textSelectionEnd.takeIf { it in start..before.length } ?: start
            val prefix = before.substring(0, start)
            val suffix = before.substring(end)
            val expected = prefix + text + suffix
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected)
            }
            val changed = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ||
                focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!changed) return null

            focused.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    val cursor = prefix.length + text.length
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            return InsertionRecord(
                fingerprint = NodeFingerprint.from(focused),
                previousFullText = before,
                expectedFullText = expected,
                prefix = prefix,
                suffix = suffix,
                insertedText = text,
            )
        } finally {
            recycle(focused)
        }
    }

    private fun obtainFocusedEditable(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        try {
            val direct = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (direct != null) {
                try {
                    if (direct.isEditable && direct.isFocused) {
                        return AccessibilityNodeInfo.obtain(direct)
                    }
                } finally {
                    if (direct !== root) recycle(direct)
                }
            }
            return findFocusedEditable(root)
        } finally {
            recycle(root)
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findFocusedEditable(child)?.let { return it }
            } finally {
                recycle(child)
            }
        }
        return null
    }
}

object CorrectionTextExtractor {
    fun extract(fullText: String, prefix: String, suffix: String): String? {
        if (fullText.length < prefix.length + suffix.length) return null
        if (!fullText.startsWith(prefix) || !fullText.endsWith(suffix)) return null
        return fullText.substring(prefix.length, fullText.length - suffix.length)
    }
}

class CorrectionTracker(
    private val historyRepository: HistoryRepository,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val databaseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voicevoice-correction-history").apply { isDaemon = true }
    }
    private var tracked: TrackedInsertion? = null
    private var pendingCorrection: String? = null
    private var closed = false
    private val commitRunnable = Runnable { commitPendingCorrection() }

    fun arm(historyId: Long, insertion: InsertionRecord) {
        if (closed) return
        clear()
        tracked = TrackedInsertion(
            historyId = historyId,
            insertion = insertion,
            lastLoggedText = insertion.insertedText,
        )
    }

    fun clear() {
        if (closed) return
        handler.removeCallbacks(commitRunnable)
        commitPendingCorrection()
        tracked = null
    }

    fun close() {
        if (closed) return
        handler.removeCallbacks(commitRunnable)
        commitPendingCorrection()
        tracked = null
        closed = true
        databaseExecutor.shutdown()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (closed) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusedView(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChange(event)
        }
    }

    private fun handleFocusedView(event: AccessibilityEvent) {
        val current = tracked ?: return
        val source = event.source ?: return
        try {
            if (source.isEditable && !current.insertion.fingerprint.matches(source)) {
                clear()
            }
        } finally {
            recycle(source)
        }
    }

    private fun handleTextChange(event: AccessibilityEvent) {
        val current = tracked ?: return
        if (event.packageName?.toString() != current.insertion.fingerprint.packageName) return
        if (event.windowId != current.insertion.fingerprint.windowId) return

        val source = event.source ?: return
        try {
            if (!current.insertion.fingerprint.matches(source)) return
            val fullText = source.text?.toString()
                ?: event.text.lastOrNull()?.toString()
                ?: return
            if (fullText == current.insertion.expectedFullText) return

            val corrected = CorrectionTextExtractor.extract(
                fullText = fullText,
                prefix = current.insertion.prefix,
                suffix = current.insertion.suffix,
            ) ?: return
            if (corrected == current.lastLoggedText || corrected == current.insertion.insertedText) return

            pendingCorrection = corrected
            handler.removeCallbacks(commitRunnable)
            handler.postDelayed(commitRunnable, CORRECTION_DEBOUNCE_MILLIS)
        } finally {
            recycle(source)
        }
    }

    private fun commitPendingCorrection() {
        val current = tracked ?: run {
            pendingCorrection = null
            return
        }
        val corrected = pendingCorrection ?: return
        pendingCorrection = null
        if (corrected == current.lastLoggedText || corrected == current.insertion.insertedText) return

        current.lastLoggedText = corrected
        val historyId = current.historyId
        val original = current.insertion.insertedText
        val packageName = current.insertion.fingerprint.packageName
        runCatching {
            databaseExecutor.execute {
                historyRepository.insertCorrection(
                    parentId = historyId,
                    originalText = original,
                    correctedText = corrected,
                    packageName = packageName,
                )
            }
        }
    }

    private data class TrackedInsertion(
        val historyId: Long,
        val insertion: InsertionRecord,
        var lastLoggedText: String,
    )

    companion object {
        private const val CORRECTION_DEBOUNCE_MILLIS = 850L
    }
}

@Suppress("DEPRECATION")
private fun recycle(node: AccessibilityNodeInfo) {
    node.recycle()
}
