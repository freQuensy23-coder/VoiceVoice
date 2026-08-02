package com.voicevoice.app.pipeline

import com.voicevoice.app.data.HistoryRepository
import com.voicevoice.app.data.SettingsRepository
import com.voicevoice.app.model.AutoInsertionReceipt
import com.voicevoice.app.model.DataCollectionResult
import com.voicevoice.app.model.HistoryEntry
import com.voicevoice.app.model.HistoryType
import com.voicevoice.app.model.NodeBounds
import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.Settings
import com.voicevoice.app.model.TargetIdentity
import com.voicevoice.app.model.VoiceVoiceException
import com.voicevoice.app.provider.LlmProvider
import com.voicevoice.app.provider.ProviderResolver
import com.voicevoice.app.provider.VoiceProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoicePipelineTest {
    @Test
    fun transcriptionUsesBothCollectorValuesAndDeliversEveryEnabledOutput() = runTest {
        val settings = Settings(openRouterApiKey = "key")
        val repositories = Fixtures(settings)
        repositories.voice.response = " raw Alexey "
        repositories.llm.postProcessed = " Send Alexey. "
        repositories.gateway.collection = DataCollectionResult(
            contextForLlm = "Telegram conversation with Alexey",
            audioModelTerms = listOf("Alexey", "VoiceVoice"),
        )
        repositories.gateway.insertionResult = receipt("Send Alexey.")

        val result = repositories.pipeline.transcribe(audio(), repositories.gateway)

        assertEquals("Send Alexey.", result.text)
        assertTrue(result.insertedAutomatically)
        assertEquals(HistoryType.TRANSCRIPTION, result.historyType)
        assertEquals(listOf("Alexey", "VoiceVoice"), repositories.voice.receivedTerms)
        assertEquals(settings, repositories.voice.receivedSettings)
        assertEquals("raw Alexey", repositories.llm.postProcessText)
        assertEquals("Telegram conversation with Alexey", repositories.llm.postProcessContext)
        assertEquals(listOf("Send Alexey."), repositories.gateway.clipboard)
        assertEquals(listOf("Send Alexey."), repositories.gateway.insertions)
        assertEquals(repositories.gateway.insertionResult, repositories.gateway.registered.single())
        assertEquals(
            StoredHistory(
                HistoryType.TRANSCRIPTION,
                "Send Alexey.",
                "raw Alexey",
                "org.telegram.messenger",
            ),
            repositories.history.records.single(),
        )
    }

    @Test
    fun disabledPostProcessingReturnsRawTextWithoutResolvingLlm() = runTest {
        val repositories = Fixtures(
            Settings(
                openRouterApiKey = "key",
                postProcessEnabled = false,
                autoInsertEnabled = false,
            ),
        )
        repositories.voice.response = "  untouched speech  "

        val result = repositories.pipeline.transcribe(audio(), repositories.gateway)

        assertEquals("untouched speech", result.text)
        assertEquals(0, repositories.providers.llmResolutions)
        assertTrue(repositories.gateway.insertions.isEmpty())
        assertEquals("untouched speech", repositories.history.records.single().text)
        assertNull(repositories.history.records.single().sourceText)
    }

    @Test
    fun clipboardStillReceivesTextWhenInsertionAndHistoryAreDisabled() = runTest {
        val repositories = Fixtures(
            Settings(
                openRouterApiKey = "key",
                autoInsertEnabled = false,
                storeHistory = false,
            ),
        )
        repositories.voice.response = "draft"
        repositories.llm.postProcessed = "final"

        val result = repositories.pipeline.transcribe(audio(), repositories.gateway)

        assertFalse(result.insertedAutomatically)
        assertEquals(listOf("final"), repositories.gateway.clipboard)
        assertTrue(repositories.gateway.insertions.isEmpty())
        assertTrue(repositories.gateway.registered.isEmpty())
        assertTrue(repositories.history.records.isEmpty())
    }

    @Test
    fun failedAutomaticInsertionIsNotRegisteredButResultIsPreserved() = runTest {
        val repositories = Fixtures(Settings(openRouterApiKey = "key"))
        repositories.voice.response = "speech"
        repositories.llm.postProcessed = "speech"
        repositories.gateway.insertionResult = null

        val result = repositories.pipeline.transcribe(audio(), repositories.gateway)

        assertFalse(result.insertedAutomatically)
        assertEquals(listOf("speech"), repositories.gateway.clipboard)
        assertTrue(repositories.gateway.registered.isEmpty())
        assertEquals(1, repositories.history.records.size)
    }

    @Test
    fun translationUsesScreenContextTargetLanguageAndTranslationHistory() = runTest {
        val repositories = Fixtures(
            Settings(openRouterApiKey = "key", targetLanguage = "Hebrew"),
        )
        repositories.gateway.collection = DataCollectionResult("chat context", listOf("unused"))
        repositories.llm.translated = "שלום"
        repositories.gateway.insertionResult = receipt("שלום")

        val result = repositories.pipeline.translate("Hello", repositories.gateway)

        assertEquals("שלום", result.text)
        assertEquals(HistoryType.TRANSLATION, result.historyType)
        assertEquals("Hello", repositories.llm.translationText)
        assertEquals("Hebrew", repositories.llm.translationTarget)
        assertEquals("chat context", repositories.llm.translationContext)
        assertEquals(
            StoredHistory(HistoryType.TRANSLATION, "שלום", "Hello", "org.telegram.messenger"),
            repositories.history.records.single(),
        )
    }

    @Test
    fun emptyProviderOutputsFailBeforeClipboardInsertionOrHistory() = runTest {
        val voiceFailure = Fixtures(Settings(openRouterApiKey = "key"))
        voiceFailure.voice.response = "   "

        val voiceError = runCatching {
            voiceFailure.pipeline.transcribe(audio(), voiceFailure.gateway)
        }.exceptionOrNull()

        assertTrue(voiceError is VoiceVoiceException)
        assertTrue(voiceFailure.gateway.clipboard.isEmpty())
        assertTrue(voiceFailure.history.records.isEmpty())

        val llmFailure = Fixtures(Settings(openRouterApiKey = "key"))
        llmFailure.voice.response = "speech"
        llmFailure.llm.postProcessed = " "

        val llmError = runCatching {
            llmFailure.pipeline.transcribe(audio(), llmFailure.gateway)
        }.exceptionOrNull()

        assertTrue(llmError is VoiceVoiceException)
        assertTrue(llmFailure.gateway.clipboard.isEmpty())
        assertTrue(llmFailure.history.records.isEmpty())
    }

    @Test
    fun blankTranslationIsRejectedBeforeProvidersOrDelivery() = runTest {
        val repositories = Fixtures(Settings(openRouterApiKey = "key"))

        val error = runCatching {
            repositories.pipeline.translate(" \n ", repositories.gateway)
        }.exceptionOrNull()

        assertTrue(error is VoiceVoiceException)
        assertEquals(0, repositories.providers.llmResolutions)
        assertTrue(repositories.gateway.clipboard.isEmpty())
        assertTrue(repositories.history.records.isEmpty())
    }

    private fun audio(): RecordedAudio {
        val file = File.createTempFile("voicevoice-pipeline-", ".wav")
        file.writeBytes(byteArrayOf(1, 2, 3))
        file.deleteOnExit()
        return RecordedAudio(file)
    }

    private fun receipt(text: String) = AutoInsertionReceipt(
        target = TargetIdentity(
            packageName = "org.telegram.messenger",
            windowId = 7,
            viewId = "message",
            className = "android.widget.EditText",
            bounds = NodeBounds(0, 0, 500, 100),
        ),
        prefix = "",
        insertedText = text,
        suffix = "",
        fullTextAfterInsertion = text,
    )

    private class Fixtures(settings: Settings) {
        val settingsRepository = FakeSettingsRepository(settings)
        val history = FakeHistoryRepository()
        val voice = FakeVoiceProvider()
        val llm = FakeLlmProvider()
        val providers = FakeProviderResolver(voice, llm)
        val gateway = FakeGateway()
        val pipeline = VoicePipeline(settingsRepository, history, providers)
    }

    private class FakeSettingsRepository(
        private var settings: Settings,
    ) : SettingsRepository {
        override fun load(): Settings = settings

        override fun save(settings: Settings) {
            this.settings = settings
        }

        override fun update(transform: (Settings) -> Settings): Settings {
            settings = transform(settings)
            return settings
        }
    }

    private data class StoredHistory(
        val type: HistoryType,
        val text: String,
        val sourceText: String?,
        val appPackage: String?,
    )

    private class FakeHistoryRepository : HistoryRepository {
        val records = mutableListOf<StoredHistory>()

        override fun add(
            type: HistoryType,
            text: String,
            sourceText: String?,
            appPackage: String?,
            createdAtMillis: Long,
        ): Long {
            records += StoredHistory(type, text, sourceText, appPackage)
            return records.size.toLong()
        }

        override fun list(limit: Int): List<HistoryEntry> = emptyList()

        override fun latestResultText(): String? = records.lastOrNull()?.text

        override fun clear() {
            records.clear()
        }
    }

    private class FakeVoiceProvider : VoiceProvider {
        var response = "raw"
        var receivedTerms: List<String>? = null
        var receivedSettings: Settings? = null

        override suspend fun transcribe(
            recordedAudio: RecordedAudio,
            audioModelTerms: List<String>,
            settings: Settings,
        ): String {
            receivedTerms = audioModelTerms
            receivedSettings = settings
            return response
        }
    }

    private class FakeLlmProvider : LlmProvider {
        var postProcessed = "final"
        var translated = "translated"
        var postProcessText: String? = null
        var postProcessContext: String? = null
        var translationText: String? = null
        var translationTarget: String? = null
        var translationContext: String? = null

        override suspend fun postProcess(transcribedText: String, context: String): String {
            postProcessText = transcribedText
            postProcessContext = context
            return postProcessed
        }

        override suspend fun translate(text: String, targetLanguage: String, context: String): String {
            translationText = text
            translationTarget = targetLanguage
            translationContext = context
            return translated
        }
    }

    private class FakeProviderResolver(
        private val voice: VoiceProvider,
        private val llm: LlmProvider,
    ) : ProviderResolver {
        var llmResolutions = 0

        override fun voiceProvider(settings: Settings): VoiceProvider = voice

        override fun llmProvider(settings: Settings): LlmProvider {
            llmResolutions++
            return llm
        }
    }

    private class FakeGateway : AccessibilityGateway {
        var collection = DataCollectionResult("context", listOf("VoiceVoice"))
        var insertionResult: AutoInsertionReceipt? = null
        val clipboard = mutableListOf<String>()
        val insertions = mutableListOf<String>()
        val registered = mutableListOf<AutoInsertionReceipt>()

        override fun collectContext(): DataCollectionResult = collection

        override fun copyToClipboard(text: String) {
            clipboard += text
        }

        override fun insertIntoFocusedField(text: String): AutoInsertionReceipt? {
            insertions += text
            return insertionResult
        }

        override fun registerAutomaticInsertion(receipt: AutoInsertionReceipt) {
            registered += receipt
        }

        override fun currentApplicationPackage(): String = "org.telegram.messenger"
    }
}
