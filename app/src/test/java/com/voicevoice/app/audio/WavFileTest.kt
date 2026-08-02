package com.voicevoice.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class WavFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun headerIsValidPcm16MonoWav() {
        val output = ByteArrayOutputStream()
        WavFile.writeHeader(output, dataSize = 32_000, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
        val bytes = output.toByteArray()

        assertEquals(44, bytes.size)
        assertArrayEquals("RIFF".toByteArray(), bytes.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), bytes.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(), bytes.copyOfRange(12, 16))
        assertArrayEquals("data".toByteArray(), bytes.copyOfRange(36, 40))
        assertEquals(32_036, littleEndianInt(bytes, 4))
        assertEquals(16_000, littleEndianInt(bytes, 24))
        assertEquals(32_000, littleEndianInt(bytes, 28))
        assertEquals(32_000, littleEndianInt(bytes, 40))
    }

    @Test
    fun silenceWriterProducesExpectedPcmPayload() {
        val output = temporaryFolder.newFile("silence.wav")

        WavFile.writeSilence(output, durationMillis = 100, sampleRate = 8_000)

        val bytes = output.readBytes()
        assertEquals(1_644, bytes.size)
        assertEquals(1_600, littleEndianInt(bytes, 40))
        assertTrue(bytes.copyOfRange(44, bytes.size).all { it == 0.toByte() })
    }

    @Test
    fun pcmWrapperPreservesSamplesAfterHeader() {
        val pcm = temporaryFolder.newFile("input.pcm")
        val samples = byteArrayOf(1, 2, -1, 0, 7, 8)
        pcm.writeBytes(samples)
        val wav = temporaryFolder.newFile("wrapped.wav")

        WavFile.wrapPcm16Mono(pcm, wav, sampleRate = 16_000)

        val bytes = wav.readBytes()
        assertEquals(44 + samples.size, bytes.size)
        assertEquals(samples.size, littleEndianInt(bytes, 40))
        assertArrayEquals(samples, bytes.copyOfRange(44, bytes.size))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
