package com.voicevoice.app.debug

import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.Settings
import com.voicevoice.app.model.VoiceVoiceException
import com.voicevoice.app.provider.LlmProvider
import com.voicevoice.app.provider.ProviderResolver
import com.voicevoice.app.provider.VoiceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DeterministicProviderResolver : ProviderResolver {
    override fun voiceProvider(settings: Settings): VoiceProvider = DeterministicVoiceProvider

    override fun llmProvider(settings: Settings): LlmProvider = DeterministicLlmProvider
}

private object DeterministicVoiceProvider : VoiceProvider {
    override suspend fun transcribe(
        recordedAudio: RecordedAudio,
        audioModelTerms: List<String>,
        settings: Settings,
    ): String {
        val header = withContext(Dispatchers.IO) {
            recordedAudio.file.inputStream().use { input ->
                ByteArray(WAV_HEADER_BYTES).also { bytes ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    if (offset != bytes.size) throw VoiceVoiceException("Manual test recording is not a valid WAV file")
                }
            }
        }
        if (
            !header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) ||
            !header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))
        ) {
            throw VoiceVoiceException("Manual test recording is not a valid WAV file")
        }
        val hasScreenVocabulary = audioModelTerms.any { it.equals("Alexey", ignoreCase = true) } &&
            audioModelTerms.any { it.equals("VoiceVoice", ignoreCase = true) }
        return if (hasScreenVocabulary) {
            "send alexey the voicevoice architecture tomorrow"
        } else {
            "send the architecture tomorrow"
        }
    }

    private const val WAV_HEADER_BYTES = 44
}

private object DeterministicLlmProvider : LlmProvider {
    override suspend fun postProcess(transcribedText: String, context: String): String {
        val hasTranscriptVocabulary = transcribedText.contains("alexey", ignoreCase = true) &&
            transcribedText.contains("voicevoice", ignoreCase = true)
        val hasScreenContext = context.contains("Alexey", ignoreCase = true) &&
            context.contains("VoiceVoice", ignoreCase = true)
        return if (hasTranscriptVocabulary && hasScreenContext) {
            "Send Alexey the VoiceVoice architecture tomorrow."
        } else {
            "Send the architecture tomorrow."
        }
    }
}
