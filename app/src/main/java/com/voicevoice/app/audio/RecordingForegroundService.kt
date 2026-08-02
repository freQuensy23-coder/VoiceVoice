package com.voicevoice.app.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voicevoice.app.MainActivity
import com.voicevoice.app.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordingForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    private var recorder: WavAudioRecorder? = null
    private var active = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (active) return
        startAsForeground()
        runCatching {
            WavAudioRecorder(this).also {
                recorder = it
                it.start()
            }
            active = true
            sendState(ACTION_RECORDING_STARTED)
        }.onFailure { error ->
            closeRecordingGate()
            sendError(error.message ?: "Could not start recording")
            stopForegroundAndSelf()
        }
    }

    private fun stopRecording() {
        if (!active || !stopping.compareAndSet(false, true)) {
            closeRecordingGate()
            return
        }
        closeRecordingGate()
        executor.execute {
            runCatching {
                val output = recorder?.stop() ?: error("Recorder is unavailable")
                sendBroadcast(
                    Intent(ACTION_RECORDING_FINISHED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_AUDIO_PATH, output.absolutePath),
                )
            }.onFailure { error ->
                sendError(error.message ?: "Could not finish recording")
            }
            active = false
            stopping.set(false)
            recorder = null
            stopForegroundAndSelf()
        }
    }

    private fun startAsForeground() {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop_recording), stopIntent)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun sendState(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun sendError(message: String) {
        sendBroadcast(
            Intent(ACTION_RECORDING_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR, message.take(500)),
        )
    }

    private fun closeRecordingGate() {
        sendBroadcast(Intent(RecordingGateActivity.ACTION_CLOSE).setPackage(packageName))
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        closeRecordingGate()
        recorder?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.voicevoice.app.action.START_RECORDING"
        const val ACTION_STOP = "com.voicevoice.app.action.STOP_RECORDING"
        const val ACTION_RECORDING_STARTED = "com.voicevoice.app.event.RECORDING_STARTED"
        const val ACTION_RECORDING_FINISHED = "com.voicevoice.app.event.RECORDING_FINISHED"
        const val ACTION_RECORDING_ERROR = "com.voicevoice.app.event.RECORDING_ERROR"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "voicevoice_recording"
        private const val NOTIFICATION_ID = 41_001
    }
}
