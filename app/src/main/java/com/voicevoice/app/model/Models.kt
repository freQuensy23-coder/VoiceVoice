package com.voicevoice.app.model

import java.io.File

data class Settings(
    val openRouterApiKey: String = "",
    val voiceProviderId: String = OPENROUTER_PROVIDER,
    val voiceModel: String = DEFAULT_VOICE_MODEL,
    val llmProviderId: String = OPENROUTER_PROVIDER,
    val llmModel: String = DEFAULT_LLM_MODEL,
    val languageHint: String = "",
    val postProcessEnabled: Boolean = true,
    val autoInsertEnabled: Boolean = true,
    val storeHistory: Boolean = true,
    val debugDeterministicMode: Boolean = false,
    val downloadedLocalModelIds: Set<String> = emptySet(),
) {
    companion object {
        const val OPENROUTER_PROVIDER = "openrouter"
        const val DEFAULT_VOICE_MODEL = "openai/whisper-large-v3"
        const val DEFAULT_LLM_MODEL = "openai/gpt-5.6-luna"
    }
}

data class RecordedAudio(
    val file: File,
    val format: String = "wav",
)

/** The collector contract intentionally contains exactly the two values used downstream. */
data class DataCollectionResult(
    val contextForLlm: String,
    val audioModelTerms: List<String>,
)

enum class HistoryType {
    TRANSCRIPTION,
    CORRECTION,
}

data class HistoryEntry(
    val id: Long,
    val type: HistoryType,
    val text: String,
    val sourceText: String?,
    val appPackage: String?,
    val createdAtMillis: Long,
)

data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class TargetIdentity(
    val packageName: String,
    val windowId: Int,
    val viewId: String?,
    val className: String?,
    val bounds: NodeBounds,
)

data class AutoInsertionReceipt(
    val target: TargetIdentity,
    val prefix: String,
    val insertedText: String,
    val suffix: String,
    val fullTextAfterInsertion: String,
)

data class PipelineResult(
    val text: String,
    val insertedAutomatically: Boolean,
    val historyType: HistoryType,
)

class VoiceVoiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
