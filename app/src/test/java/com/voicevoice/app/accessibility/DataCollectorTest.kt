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

    private fun node(text: String? = null, password: Boolean = false) = AccessibilityNodeSnapshot(
        text = text,
        contentDescription = null,
        viewId = null,
        className = "android.widget.TextView",
        editable = false,
        password = password,
        bounds = NodeBounds(0, 0, 100, 40),
        children = emptyList(),
    )
}
