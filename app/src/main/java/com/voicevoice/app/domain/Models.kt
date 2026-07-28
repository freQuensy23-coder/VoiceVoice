package com.voicevoice.app.domain

import java.io.File

class AppSettings(
    val apiKey: String,
    val voiceProviderId: String,
    val voiceModel: String,
    val llmProviderId: String,
    val llmModel: String,
    val languageHint: String,
    val translationEnabled: Boolean,
    val targetLanguage: String,
) {
    override fun toString(): String =
        "AppSettings(apiKey=<redacted>, voiceProviderId=$voiceProviderId, " +
            "voiceModel=$voiceModel, llmProviderId=$llmProviderId, llmModel=$llmModel, " +
            "languageHint=$languageHint, translationEnabled=$translationEnabled, " +
            "targetLanguage=$targetLanguage)"
}

data class RecordedAudio(
    val file: File,
    val format: String,
    val durationMillis: Long,
)

enum class HistoryKind {
    TRANSCRIPTION,
    TRANSLATION,
    CORRECTION,
}

data class HistoryEntry(
    val id: Long,
    val kind: HistoryKind,
    val sourceText: String,
    val resultText: String,
    val packageName: String?,
    val automaticallyInserted: Boolean,
    val parentId: Long?,
    val createdAt: Long,
)

enum class RuntimePhase {
    IDLE,
    RECORDING,
    PROCESSING,
    COMPLETE,
    ERROR,
}

data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.IDLE,
    val message: String = "Ready",
    val updatedAt: Long = System.currentTimeMillis(),
)
