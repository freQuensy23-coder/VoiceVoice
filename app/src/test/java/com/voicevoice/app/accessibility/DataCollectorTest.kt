package com.voicevoice.app.accessibility

import com.voicevoice.app.model.NodeBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataCollectorTest {
    @Test
    fun generalCollectorReturnsContextAndVocabularyWithoutPasswords() {
        val snapshot = AccessibilitySnapshot(
            packageName = "example.app",
            roots = listOf(
                node(text = "Alexey opened user_profile for @openrouter"),
                node(text = "super-secret", password = true),
            ),
        )

        val result = GeneralDataCollector().collect(snapshot)

        assertTrue(result.contextForLlm.contains("Alexey"))
        assertFalse(result.contextForLlm.contains("super-secret"))
        assertTrue(result.audioModelTerms.contains("Alexey"))
        assertTrue(result.audioModelTerms.contains("user_profile"))
        assertTrue(result.audioModelTerms.contains("@openrouter"))
    }

    @Test
    fun registryUsesCustomCollectorsAndFallsBackToGeneral() {
        val snapshot = AccessibilitySnapshot("org.telegram.messenger", listOf(node(text = "VoiceVoice")))
        val telegram = DataCollectorRegistry().resolve(snapshot.packageName).collect(snapshot)
        val fallback = DataCollectorRegistry().resolve("unknown.package").collect(snapshot.copy(packageName = "unknown.package"))

        assertTrue(telegram.contextForLlm.startsWith("Telegram conversation context"))
        assertTrue(fallback.contextForLlm.startsWith("Application package"))
        assertEquals(listOf("VoiceVoice"), telegram.audioModelTerms)
    }

    @Test
    fun whatsappVariantsUseConversationCollector() {
        val snapshot = AccessibilitySnapshot("com.whatsapp.w4b", listOf(node(text = "OpenRouter")))

        val result = DataCollectorRegistry().resolve(snapshot.packageName).collect(snapshot)

        assertTrue(result.contextForLlm.startsWith("WhatsApp conversation context"))
        assertEquals(listOf("OpenRouter"), result.audioModelTerms)
    }

    @Test
    fun collectorFlattensChildrenDescriptionsAndWhitespaceInStableOrder() {
        val snapshot = AccessibilitySnapshot(
            packageName = "example.app",
            roots = listOf(
                node(
                    text = "  First\n  line ",
                    contentDescription = "avatar for Alexey",
                    children = listOf(
                        node(text = "Second line"),
                        node(text = "First line"),
                    ),
                ),
            ),
        )

        val result = GeneralDataCollector().collect(snapshot)

        val visible = result.contextForLlm.substringAfter("Visible accessibility content:\n").lines()
        assertEquals(listOf("First line", "avatar for Alexey", "Second line"), visible)
        assertTrue(result.audioModelTerms.contains("Alexey"))
    }

    @Test
    fun passwordNodesAndTheirChildrenNeverReachEitherOutput() {
        val snapshot = AccessibilitySnapshot(
            packageName = "example.app",
            roots = listOf(
                node(
                    text = "password",
                    contentDescription = "secret VoiceSecret",
                    password = true,
                    children = listOf(node(text = "NestedSecret")),
                ),
                node(text = "VisibleName"),
            ),
        )

        val result = GeneralDataCollector().collect(snapshot)

        assertFalse(result.contextForLlm.contains("VoiceSecret"))
        assertFalse(result.contextForLlm.contains("NestedSecret"))
        assertFalse(result.audioModelTerms.contains("VoiceSecret"))
        assertEquals(listOf("VisibleName"), result.audioModelTerms)
    }

    @Test
    fun contextAndAudioTermsAreBounded() {
        val longLines = (0 until 400).map { index ->
            node(text = "VisibleName$index " + "x".repeat(900))
        }
        val snapshot = AccessibilitySnapshot("example.app", longLines)

        val result = GeneralDataCollector().collect(snapshot)

        assertTrue(result.contextForLlm.length <= 12_000)
        assertEquals(64, result.audioModelTerms.size)
        assertEquals("VisibleName0", result.audioModelTerms.first())
        assertEquals("VisibleName63", result.audioModelTerms.last())
    }

    private fun node(
        text: String? = null,
        contentDescription: String? = null,
        password: Boolean = false,
        children: List<AccessibilityNodeSnapshot> = emptyList(),
    ) = AccessibilityNodeSnapshot(
        text = text,
        contentDescription = contentDescription,
        viewId = null,
        className = "android.widget.TextView",
        editable = false,
        password = password,
        bounds = NodeBounds(0, 0, 100, 40),
        children = children,
    )
}
