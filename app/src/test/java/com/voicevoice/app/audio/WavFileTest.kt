package com.voicevoice.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavFileTest {
    @Test
    fun headerIsValidPcm16MonoWav() {
        val output = ByteArrayOutputStream()
        WavFile.writeHeader(output, dataSize = 32_000, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
        val bytes = output.toByteArray()

        assertEquals(44, bytes.size)
        assertArrayEquals("RIFF".toByteArray(), bytes.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), bytes.copyOfRange(8, 12))
        assertArrayEquals("data".toByteArray(), bytes.copyOfRange(36, 40))
        assertEquals(32_000, littleEndianInt(bytes, 40))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
