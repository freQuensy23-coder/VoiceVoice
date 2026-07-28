package com.voicevoice.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.voicevoice.app.MainActivity
import com.voicevoice.app.R
import com.voicevoice.app.domain.RecordedAudio
import com.voicevoice.app.domain.RuntimePhase
import com.voicevoice.app.domain.RuntimeState
import com.voicevoice.app.runtime.AppRuntime
import com.voicevoice.app.runtime.RecordingBridge
import com.voicevoice.app.settings.DebugModeController
import java.io.File
import java.io.OutputStream

/**
 * Long-lived microphone foreground-service host.
 *
 * Android 14+ does not allow a microphone foreground service to be created after the app has
 * already moved to the background. MainActivity therefore prepares this host while it is visible.
 * The Accessibility overlay only sends start/stop commands to the already-running host.
 */
class VoiceRecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L
    private var manualRecordingActive = false
    private var prepared = false
    private var shuttingDown = false

    private val runtimeListener: (RuntimeState) -> Unit = { state ->
        if (prepared) updateNotification(state)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppRuntime.addListener(runtimeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_PREPARE, null -> {
                if (prepareForegroundHost()) START_STICKY else START_NOT_STICKY
            }

            ACTION_START -> {
                if (prepared) {
                    startRecording()
                } else {
                    AppRuntime.update(
                        RuntimePhase.ERROR,
                        "Open VoiceVoice to prepare background recording",
                    )
                }
                START_STICKY
            }

            ACTION_STOP -> {
                if (prepared) stopRecording()
                START_STICKY
            }

            ACTION_CANCEL -> {
                if (prepared) cancelRecording(updateRuntime = true)
                START_STICKY
            }

            ACTION_SHUTDOWN -> {
                shutdownHost()
                START_NOT_STICKY
            }

            else -> START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppRuntime.removeListener(runtimeListener)
        val interrupted = recorder != null || manualRecordingActive
        releaseRecorder(deleteOutput = true)
        manualRecordingActive = false
        prepared = false
        preparedInstance = false

        if (interrupted && !shuttingDown) {
            RecordingBridge.publish(
                Result.failure(IllegalStateException("Recording service stopped unexpectedly")),
            )
            AppRuntime.update(
                RuntimePhase.ERROR,
                "Recorder host stopped; open VoiceVoice to restore background recording",
            )
        }
        super.onDestroy()
    }

    private fun prepareForegroundHost(): Boolean {
        if (prepared) {
            updateNotification(AppRuntime.state())
            return true
        }

        return runCatching {
            startAsForeground(buildNotification(AppRuntime.state()))
            prepared = true
            preparedInstance = true
            if (AppRuntime.state().phase != RuntimePhase.RECORDING &&
                AppRuntime.state().phase != RuntimePhase.PROCESSING
            ) {
                AppRuntime.update(RuntimePhase.IDLE, "Background recorder ready")
            }
            true
        }.getOrElse { error ->
            prepared = false
            preparedInstance = false
            AppRuntime.update(
                RuntimePhase.ERROR,
                safeMessage(error, "Unable to prepare background recording"),
            )
            stopSelf()
            false
        }
    }

    private fun startRecording() {
        if (recorder != null || manualRecordingActive) return
        startedAt = System.currentTimeMillis()

        if (DebugModeController.isEnabled(this)) {
            manualRecordingActive = true
            AppRuntime.update(
                RuntimePhase.RECORDING,
                "Recording test audio… tap the floating button to stop",
            )
            return
        }

        try {
            val file = File.createTempFile("voicevoice-", ".m4a", cacheDir)
            outputFile = file
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            AppRuntime.update(RuntimePhase.RECORDING, "Recording… tap the floating button to stop")
        } catch (error: Throwable) {
            releaseRecorder(deleteOutput = true)
            AppRuntime.update(RuntimePhase.ERROR, safeMessage(error, "Unable to start recording"))
        }
    }

    private fun stopRecording() {
        if (manualRecordingActive) {
            stopManualRecording()
            return
        }

        val activeRecorder = recorder ?: return
        val file = outputFile
        AppRuntime.update(RuntimePhase.PROCESSING, "Collecting context and transcribing…")
        try {
            activeRecorder.stop()
            require(file != null && file.isFile && file.length() > 0L) {
                "The recording is empty"
            }
            RecordingBridge.publish(
                Result.success(
                    RecordedAudio(
                        file = file,
                        format = "m4a",
                        durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                    ),
                ),
            )
            outputFile = null
        } catch (error: Throwable) {
            file?.delete()
            RecordingBridge.publish(Result.failure(error))
            AppRuntime.update(RuntimePhase.ERROR, safeMessage(error, "Unable to finish recording"))
        } finally {
            activeRecorder.release()
            recorder = null
            outputFile = null
        }
    }

    private fun stopManualRecording() {
        manualRecordingActive = false
        AppRuntime.update(RuntimePhase.PROCESSING, "Collecting context and transcribing…")
        try {
            val file = createManualTestAudioFile()
            RecordingBridge.publish(
                Result.success(
                    RecordedAudio(
                        file = file,
                        format = "wav",
                        durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(1_000L),
                    ),
                ),
            )
        } catch (error: Throwable) {
            RecordingBridge.publish(Result.failure(error))
            AppRuntime.update(RuntimePhase.ERROR, safeMessage(error, "Unable to finish test recording"))
        }
    }

    private fun cancelRecording(updateRuntime: Boolean) {
        releaseRecorder(deleteOutput = true)
        manualRecordingActive = false
        if (updateRuntime) AppRuntime.update(RuntimePhase.IDLE, "Background recorder ready")
    }

    private fun releaseRecorder(deleteOutput: Boolean) {
        recorder?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        recorder = null
        if (deleteOutput) outputFile?.delete()
        outputFile = null
    }

    private fun shutdownHost() {
        shuttingDown = true
        cancelRecording(updateRuntime = false)
        prepared = false
        preparedInstance = false
        stopForegroundCompat()
        stopSelf()
        AppRuntime.update(RuntimePhase.IDLE, "Background recorder stopped")
    }

    private fun createManualTestAudioFile(): File {
        val sampleRate = 8_000
        val channelCount = 1
        val bitsPerSample = 16
        val sampleCount = sampleRate
        val dataSize = sampleCount * channelCount * bitsPerSample / 8
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        val file = File.createTempFile("voicevoice-manual-", ".wav", cacheDir)

        file.outputStream().buffered().use { output ->
            output.writeAscii("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(channelCount)
            output.writeLittleEndianInt(sampleRate)
            output.writeLittleEndianInt(byteRate)
            output.writeLittleEndianShort(blockAlign)
            output.writeLittleEndianShort(bitsPerSample)
            output.writeAscii("data")
            output.writeLittleEndianInt(dataSize)
            output.write(ByteArray(dataSize))
        }
        return file
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: RuntimeState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RuntimeState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(
                when (state.phase) {
                    RuntimePhase.RECORDING -> "VoiceVoice is recording"
                    RuntimePhase.PROCESSING -> "VoiceVoice is processing"
                    else -> "VoiceVoice is ready"
                },
            )
            .setContentText(
                when (state.phase) {
                    RuntimePhase.RECORDING -> "Use the floating button to stop"
                    RuntimePhase.PROCESSING -> "Transcribing and applying screen context"
                    RuntimePhase.COMPLETE -> "The latest result is ready"
                    RuntimePhase.ERROR -> state.message.take(120)
                    RuntimePhase.IDLE -> "Background recorder is ready for the floating microphone"
                },
            )
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (state.phase == RuntimePhase.RECORDING) {
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, VoiceRecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_mic, "Stop", stop)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Background voice recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps VoiceVoice ready for user-initiated floating-button recording"
                setSound(null, null)
            },
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "voicevoice_recording"
        private const val NOTIFICATION_ID = 41023
        private const val ACTION_PREPARE = "com.voicevoice.app.action.PREPARE_RECORDING"
        private const val ACTION_START = "com.voicevoice.app.action.START_RECORDING"
        private const val ACTION_STOP = "com.voicevoice.app.action.STOP_RECORDING"
        private const val ACTION_CANCEL = "com.voicevoice.app.action.CANCEL_RECORDING"
        private const val ACTION_SHUTDOWN = "com.voicevoice.app.action.SHUTDOWN_RECORDING"

        @Volatile
        private var preparedInstance = false

        fun isPrepared(): Boolean = preparedInstance

        /** Must be called while a VoiceVoice activity is visible. */
        fun prepare(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceRecordingService::class.java).setAction(ACTION_PREPARE),
            )
        }

        fun start(context: Context) {
            check(preparedInstance) {
                "Open VoiceVoice to prepare background recording"
            }
            context.startService(
                Intent(context, VoiceRecordingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            if (!preparedInstance) return
            context.startService(
                Intent(context, VoiceRecordingService::class.java).setAction(ACTION_STOP),
            )
        }

        fun cancel(context: Context) {
            if (!preparedInstance) return
            context.startService(
                Intent(context, VoiceRecordingService::class.java).setAction(ACTION_CANCEL),
            )
        }

        fun shutdown(context: Context) {
            if (!preparedInstance) return
            context.startService(
                Intent(context, VoiceRecordingService::class.java).setAction(ACTION_SHUTDOWN),
            )
        }

        private fun safeMessage(error: Throwable, fallback: String): String =
            error.message?.take(180)?.takeIf(String::isNotBlank) ?: fallback
    }
}

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeLittleEndianShort(value: Int) {
    write(value and 0xff)
    write(value shr 8 and 0xff)
}

private fun OutputStream.writeLittleEndianInt(value: Int) {
    write(value and 0xff)
    write(value shr 8 and 0xff)
    write(value shr 16 and 0xff)
    write(value shr 24 and 0xff)
}
