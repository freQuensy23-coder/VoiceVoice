package com.voicevoice.app

import com.voicevoice.app.accessibility.CorrectionTextExtractor
import com.voicevoice.app.collector.AccessibilityNodeSnapshot
import com.voicevoice.app.collector.AccessibilityScreen
import com.voicevoice.app.collector.CollectorResult
import com.voicevoice.app.collector.DataCollector
import com.voicevoice.app.collector.DataCollectorRegistry
import com.voicevoice.app.collector.NodeBounds
import com.voicevoice.app.collector.TermExtractor
import com.voicevoice.app.domain.AppSettings
import com.voicevoice.app.provider.PostProcessingPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureUnitTest {
    @Test
    fun termExtractor_keepsNamesAndIdentifiers() {
        val terms = TermExtractor.extract(
            listOf("Message Aleksei Petrov about vertex_location and GPT5 in OpenRouter"),
        )
        assertTrue("Aleksei" in terms)
        assertTrue("Aleksei Petrov" in terms)
        assertTrue("vertex_location" in terms)
        assertTrue("GPT5" in terms)
        assertTrue("OpenRouter" in terms)
    }

    @Test
    fun collectorRegistry_usesTelegramSpecificContextAndExactlyTwoOutputs() {
        val result = DataCollectorRegistry().collect(
            screen(packageName = "org.telegram.messenger", text = "Hello Aleksei"),
        )
        assertTrue(result.context.startsWith("Telegram conversation context"))
        assertTrue("Aleksei" in result.audioTerms)
    }

    @Test
    fun collectorRegistry_allowsApplicationSpecificOverridesAndKeepsFallback() {
        val custom = object : DataCollector {
            override fun collect(screen: AccessibilityScreen): CollectorResult =
                CollectorResult(context = "custom", audioTerms = listOf("DomainTerm"))
        }
        val registry = DataCollectorRegistry(customCollectors = mapOf("com.example.app" to custom))

        assertEquals(
            CollectorResult(context = "custom", audioTerms = listOf("DomainTerm")),
            registry.collect(screen(packageName = "COM.EXAMPLE.APP", text = "ignored")),
        )
        assertTrue(
            registry.collect(screen(packageName = "com.unknown.app", text = "Visible"))
                .context
                .startsWith("Active Android package: com.unknown.app"),
        )
    }

    @Test
    fun postProcessingPrompt_treatsScreenAsUntrustedAndSupportsTranslation() {
        val settings = AppSettings(
            apiKey = "secret",
            voiceProviderId = "openrouter",
            voiceModel = "voice",
            llmProviderId = "openrouter",
            llmModel = "llm",
            languageHint = "",
            translationEnabled = true,
            targetLanguage = "Spanish",
        )
        val system = PostProcessingPromptBuilder.systemPrompt(settings)
        assertTrue(system.contains("Spanish"))
        assertTrue(system.contains("untrusted"))
        assertFalse(settings.toString().contains("secret"))
    }

    @Test
    fun correctionExtractor_returnsOnlyTheAutomaticallyInsertedSegment() {
        assertEquals(
            "Hola corregida",
            CorrectionTextExtractor.extract(
                fullText = "Before Hola corregida after",
                prefix = "Before ",
                suffix = " after",
            ),
        )
    }

    @Test
    fun correctionExtractor_rejectsChangesOutsideTheInsertedSegment() {
        assertNull(
            CorrectionTextExtractor.extract(
                fullText = "Changed prefix Hola after",
                prefix = "Before ",
                suffix = " after",
            ),
        )
    }

    private fun screen(packageName: String, text: String): AccessibilityScreen =
        AccessibilityScreen(
            packageName = packageName,
            windowId = 1,
            capturedAt = 1L,
            nodes = listOf(
                AccessibilityNodeSnapshot(
                    text = text,
                    contentDescription = null,
                    viewId = "$packageName:id/message",
                    className = "android.widget.TextView",
                    editable = false,
                    focused = false,
                    password = false,
                    bounds = NodeBounds(0, 0, 100, 20),
                ),
            ),
        )
}
