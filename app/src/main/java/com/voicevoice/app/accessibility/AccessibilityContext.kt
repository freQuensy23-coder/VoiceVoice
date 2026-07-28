package com.voicevoice.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.voicevoice.app.model.DataCollectionResult
import com.voicevoice.app.model.NodeBounds
import java.util.ArrayDeque
import java.util.LinkedHashSet

interface DataCollector {
    fun collect(snapshot: AccessibilitySnapshot): DataCollectionResult
}

data class AccessibilitySnapshot(
    val packageName: String,
    val roots: List<AccessibilityNodeSnapshot>,
)

data class AccessibilityNodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val editable: Boolean,
    val password: Boolean,
    val bounds: NodeBounds,
    val children: List<AccessibilityNodeSnapshot>,
)

class AccessibilitySnapshotFactory(
    private val maxNodes: Int = 700,
    private val maxDepth: Int = 25,
) {
    fun capture(service: AccessibilityService): AccessibilitySnapshot {
        val roots = mutableListOf<AccessibilityNodeSnapshot>()
        var remaining = maxNodes
        var activePackage = service.rootInActiveWindow?.packageName?.toString().orEmpty()

        val windowRoots = runCatching {
            service.windows
                .asSequence()
                .filter { it.root != null }
                .mapNotNull { it.root }
                .toList()
        }.getOrDefault(emptyList())

        val candidates = if (windowRoots.isNotEmpty()) {
            windowRoots
        } else {
            listOfNotNull(service.rootInActiveWindow)
        }

        for (root in candidates) {
            if (remaining <= 0) break
            val packageName = root.packageName?.toString().orEmpty()
            if (activePackage.isBlank() && packageName.isNotBlank()) activePackage = packageName
            val result = copyNode(root, depth = 0, budget = remaining)
            result.node?.let(roots::add)
            remaining -= result.consumed
        }
        return AccessibilitySnapshot(activePackage, roots)
    }

    private fun copyNode(node: AccessibilityNodeInfo, depth: Int, budget: Int): CopyResult {
        if (budget <= 0 || depth > maxDepth) return CopyResult(null, 0)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        var consumed = 1
        val children = mutableListOf<AccessibilityNodeSnapshot>()
        for (index in 0 until node.childCount) {
            if (consumed >= budget) break
            val child = node.getChild(index) ?: continue
            val childResult = copyNode(child, depth + 1, budget - consumed)
            consumed += childResult.consumed
            childResult.node?.let(children::add)
        }
        return CopyResult(
            node = AccessibilityNodeSnapshot(
                text = if (node.isPassword) null else node.text?.toString(),
                contentDescription = if (node.isPassword) null else node.contentDescription?.toString(),
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                editable = node.isEditable,
                password = node.isPassword,
                bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
                children = children,
            ),
            consumed = consumed,
        )
    }

    private data class CopyResult(
        val node: AccessibilityNodeSnapshot?,
        val consumed: Int,
    )
}

class GeneralDataCollector : DataCollector {
    override fun collect(snapshot: AccessibilitySnapshot): DataCollectionResult {
        val visibleText = collectVisibleText(snapshot)
        val context = buildString {
            append("Application package: ")
            append(snapshot.packageName.ifBlank { "unknown" })
            append('\n')
            append("Visible accessibility content:\n")
            append(visibleText.joinToString("\n"))
        }.take(MAX_CONTEXT_CHARS)
        return DataCollectionResult(
            contextForLlm = context,
            audioModelTerms = TermExtractor.extract(visibleText),
        )
    }

    internal fun collectVisibleText(snapshot: AccessibilitySnapshot): List<String> {
        val output = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeSnapshot>()
        snapshot.roots.forEach(queue::addLast)
        while (queue.isNotEmpty() && output.size < MAX_TEXT_ITEMS) {
            val node = queue.removeFirst()
            if (!node.password) {
                node.text.cleanAccessibilityText()?.let(output::add)
                node.contentDescription.cleanAccessibilityText()?.let(output::add)
                node.children.forEach(queue::addLast)
            }
        }
        return output.toList()
    }

    private fun String?.cleanAccessibilityText(): String? {
        val cleaned = this?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return cleaned.takeIf { it.isNotEmpty() && it.length <= MAX_ITEM_CHARS }
    }

    private companion object {
        const val MAX_CONTEXT_CHARS = 12_000
        const val MAX_TEXT_ITEMS = 350
        const val MAX_ITEM_CHARS = 1_000
    }
}

class TelegramDataCollector(
    private val general: GeneralDataCollector = GeneralDataCollector(),
) : DataCollector {
    override fun collect(snapshot: AccessibilitySnapshot): DataCollectionResult {
        val text = general.collectVisibleText(snapshot)
        return DataCollectionResult(
            contextForLlm = buildConversationContext("Telegram", snapshot.packageName, text),
            audioModelTerms = TermExtractor.extract(text),
        )
    }
}

class WhatsAppDataCollector(
    private val general: GeneralDataCollector = GeneralDataCollector(),
) : DataCollector {
    override fun collect(snapshot: AccessibilitySnapshot): DataCollectionResult {
        val text = general.collectVisibleText(snapshot)
        return DataCollectionResult(
            contextForLlm = buildConversationContext("WhatsApp", snapshot.packageName, text),
            audioModelTerms = TermExtractor.extract(text),
        )
    }
}

class DataCollectorRegistry(
    private val general: DataCollector = GeneralDataCollector(),
    private val telegram: DataCollector = TelegramDataCollector(),
    private val whatsApp: DataCollector = WhatsAppDataCollector(),
) {
    fun resolve(packageName: String): DataCollector = when (packageName) {
        "org.telegram.messenger", "org.telegram.messenger.web" -> telegram
        "com.whatsapp", "com.whatsapp.w4b" -> whatsApp
        else -> general
    }
}

object TermExtractor {
    private val handlePattern = Regex("@[\\p{L}\\p{N}_.-]{2,64}")
    private val identifierPattern = Regex("[\\p{L}_][\\p{L}\\p{N}_-]{2,64}")

    fun extract(lines: List<String>): List<String> {
        val terms = LinkedHashSet<String>()
        for (line in lines) {
            handlePattern.findAll(line).forEach { terms += it.value }
            identifierPattern.findAll(line).forEach { match ->
                val token = match.value
                val interesting = token.any(Char::isUpperCase) ||
                    token.contains('_') ||
                    token.contains('-') ||
                    token.any(Char::isDigit)
                if (interesting && token.length in 3..65) terms += token
            }
            if (terms.size >= MAX_TERMS) break
        }
        return terms.take(MAX_TERMS)
    }

    private const val MAX_TERMS = 64
}

private fun buildConversationContext(
    applicationName: String,
    packageName: String,
    visibleText: List<String>,
): String = buildString {
    append(applicationName)
    append(" conversation context from accessibility. Package: ")
    append(packageName)
    append(". Items are ordered as exposed by the current accessibility tree.\n")
    visibleText.forEachIndexed { index, value ->
        append(index + 1)
        append(". ")
        append(value)
        append('\n')
    }
}.take(12_000)
