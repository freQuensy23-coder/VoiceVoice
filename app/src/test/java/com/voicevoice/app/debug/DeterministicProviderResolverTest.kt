package com.voicevoice.app.debug

import com.voicevoice.app.audio.WavFile
import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.Settings
import com.voicevoice.app.model.VoiceVoiceException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeterministicProviderResolverTest {
    private val resolver = DeterministicProviderResolver()
    private val settings = Settings()

    @Test
    fun providersExerciseWavVocabularyAndScreenContext() = runTest {
        val wav = File.createTempFile("voicevoice-manual-test-", ".wav").apply {
            deleteOnExit()
            WavFile.writeSilence(this)
        }

        val transcript = resolver.voiceProvider(settings).transcribe(
            RecordedAudio(wav),
            listOf("Alexey", "VoiceVoice"),
            settings,
        )
        val result = resolver.llmProvider(settings).postProcess(
            transcript,
            "Alexey is discussing VoiceVoice.",
        )

        assertEquals("send alexey the voicevoice architecture tomorrow", transcript)
        assertEquals("Send Alexey the VoiceVoice architecture tomorrow.", result)
    }

    @Test
    fun voiceProviderRejectsInvalidManualTestAudio() = runTest {
        val invalid = File.createTempFile("voicevoice-manual-test-", ".wav").apply {
            deleteOnExit()
            writeText("not a wav")
        }

        val error = runCatching {
            resolver.voiceProvider(settings).transcribe(RecordedAudio(invalid), emptyList(), settings)
        }.exceptionOrNull()

        assertTrue(error is VoiceVoiceException)
    }
}
