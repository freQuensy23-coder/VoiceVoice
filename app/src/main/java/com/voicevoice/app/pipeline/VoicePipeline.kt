package com.voicevoice.app.pipeline

import com.voicevoice.app.data.HistoryRepository
import com.voicevoice.app.data.SettingsRepository
import com.voicevoice.app.model.AutoInsertionReceipt
import com.voicevoice.app.model.DataCollectionResult
import com.voicevoice.app.model.HistoryType
import com.voicevoice.app.model.PipelineResult
import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.VoiceVoiceException
import com.voicevoice.app.provider.ProviderFactory

interface AccessibilityGateway {
    fun collectContext(): DataCollectionResult
    fun copyToClipboard(text: String)
    fun insertIntoFocusedField(text: String): AutoInsertionReceipt?
    fun registerAutomaticInsertion(receipt: AutoInsertionReceipt)
    fun currentApplicationPackage(): String?
}

class VoicePipeline(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val providerFactory: ProviderFactory,
) {
    suspend fun transcribe(
        audio: RecordedAudio,
        gateway: AccessibilityGateway,
    ): PipelineResult {
        val settings = settingsRepository.load()
        val collected = gateway.collectContext()
        val voiceProvider = providerFactory.voiceProvider(settings)
        val rawText = voiceProvider.transcribe(audio, collected.audioModelTerms, settings).trim()
        if (rawText.isBlank()) throw VoiceVoiceException("Transcription is empty")

        val finalText = if (settings.postProcessEnabled) {
            providerFactory.llmProvider(settings)
                .postProcess(rawText, collected.contextForLlm)
                .trim()
        } else {
            rawText
        }
        if (finalText.isBlank()) throw VoiceVoiceException("Post-processing returned empty text")
        return deliver(
            text = finalText,
            sourceText = rawText.takeIf { it != finalText },
            type = HistoryType.TRANSCRIPTION,
            gateway = gateway,
        )
    }

    suspend fun translate(
        sourceText: String,
        gateway: AccessibilityGateway,
    ): PipelineResult {
        if (sourceText.isBlank()) throw VoiceVoiceException("There is no text to translate")
        val settings = settingsRepository.load()
        val collected = gateway.collectContext()
        val translated = providerFactory.llmProvider(settings)
            .translate(sourceText, settings.targetLanguage, collected.contextForLlm)
            .trim()
        if (translated.isBlank()) throw VoiceVoiceException("Translation is empty")
        return deliver(
            text = translated,
            sourceText = sourceText,
            type = HistoryType.TRANSLATION,
            gateway = gateway,
        )
    }

    private fun deliver(
        text: String,
        sourceText: String?,
        type: HistoryType,
        gateway: AccessibilityGateway,
    ): PipelineResult {
        val settings = settingsRepository.load()
        gateway.copyToClipboard(text)
        val receipt = if (settings.autoInsertEnabled) gateway.insertIntoFocusedField(text) else null
        receipt?.let(gateway::registerAutomaticInsertion)
        if (settings.storeHistory) {
            historyRepository.add(
                type = type,
                text = text,
                sourceText = sourceText,
                appPackage = gateway.currentApplicationPackage(),
            )
        }
        return PipelineResult(
            text = text,
            insertedAutomatically = receipt != null,
            historyType = type,
        )
    }
}
