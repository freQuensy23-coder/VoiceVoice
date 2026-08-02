package com.voicevoice.app.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

object WavFile {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    fun wrapPcm16Mono(pcmFile: File, wavFile: File, sampleRate: Int = SAMPLE_RATE) {
        val dataSize = pcmFile.length()
        FileOutputStream(wavFile).use { output ->
            writeHeader(output, dataSize, sampleRate, CHANNELS, BITS_PER_SAMPLE)
            FileInputStream(pcmFile).use { input -> input.copyTo(output) }
        }
    }

    fun writeSilence(wavFile: File, durationMillis: Int = 500, sampleRate: Int = SAMPLE_RATE) {
        val sampleCount = (sampleRate * durationMillis / 1_000).coerceAtLeast(1)
        val dataSize = sampleCount * 2L
        FileOutputStream(wavFile).use { output ->
            writeHeader(output, dataSize, sampleRate, CHANNELS, BITS_PER_SAMPLE)
            val block = ByteArray(2_048)
            var remaining = dataSize
            while (remaining > 0) {
                val count = minOf(block.size.toLong(), remaining).toInt()
                output.write(block, 0, count)
                remaining -= count
            }
        }
    }

    internal fun writeHeader(
        output: OutputStream,
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt((36L + dataSize).toInt())
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(channels)
        output.writeLittleEndianInt(sampleRate)
        output.writeLittleEndianInt(byteRate)
        output.writeLittleEndianShort(blockAlign)
        output.writeLittleEndianShort(bitsPerSample)
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeLittleEndianInt(dataSize.toInt())
    }

    private fun OutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
        write(value shr 16 and 0xff)
        write(value shr 24 and 0xff)
    }

    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
    }
}
