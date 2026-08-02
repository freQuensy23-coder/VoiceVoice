package com.voicevoice.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voicevoice.app.model.VoiceVoiceException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class WavAudioRecorder(private val context: Context) {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null
    private var pcmFile: File? = null

    fun start() {
        check(!recording.get()) { "Recording is already active" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw VoiceVoiceException("Microphone permission is required")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            WavFile.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw VoiceVoiceException("This device cannot initialize 16 kHz microphone capture")
        val bufferSize = maxOf(minBuffer * 2, 8_192)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WavFile.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw VoiceVoiceException("Microphone initialization failed")
        }

        val file = File.createTempFile("voicevoice-", ".pcm", context.cacheDir)
        pcmFile = file
        audioRecord = recorder
        recording.set(true)
        recorder.startRecording()
        writerThread = Thread({ writeLoop(recorder, file, bufferSize) }, "voicevoice-audio-writer").apply {
            start()
        }
    }

    fun stop(): File {
        if (!recording.getAndSet(false)) throw VoiceVoiceException("No recording is active")
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        writerThread?.join(3_000)
        recorder?.release()
        audioRecord = null
        writerThread = null

        val pcm = pcmFile ?: throw VoiceVoiceException("Recorded audio file is missing")
        val wav = File.createTempFile("voicevoice-", ".wav", context.cacheDir)
        WavFile.wrapPcm16Mono(pcm, wav)
        pcm.delete()
        pcmFile = null
        if (wav.length() <= 44L) {
            wav.delete()
            throw VoiceVoiceException("No audio was captured")
        }
        return wav
    }

    fun cancel() {
        if (recording.getAndSet(false)) runCatching { audioRecord?.stop() }
        writerThread?.join(1_000)
        audioRecord?.release()
        audioRecord = null
        writerThread = null
        pcmFile?.delete()
        pcmFile = null
    }

    private fun writeLoop(recorder: AudioRecord, file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        FileOutputStream(file).use { output ->
            while (recording.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) output.write(buffer, 0, read)
            }
        }
    }
}
