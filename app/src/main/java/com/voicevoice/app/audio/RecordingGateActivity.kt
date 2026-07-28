package com.voicevoice.app.audio

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.voicevoice.app.R

/**
 * Android 14+ rejects creation of a microphone foreground service while an app is
 * backgrounded, even when ordinary background-FGS exemptions apply. The explicit
 * accessibility-overlay tap opens this transparent, non-touchable activity first,
 * then starts the microphone service while the app has visible while-in-use state.
 *
 * It stays visible for the recording lifetime and closes before context collection.
 * The non-focusable window preserves the external editable field's input focus.
 */
class RecordingGateActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var launchRequested = false
    private var recordingStarted = false

    private val startupTimeout = Runnable {
        if (!recordingStarted) {
            sendError(getString(R.string.error_recording))
            finishGate()
        }
    }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingForegroundService.ACTION_RECORDING_STARTED -> {
                    recordingStarted = true
                    handler.removeCallbacks(startupTimeout)
                }
                RecordingForegroundService.ACTION_RECORDING_FINISHED,
                RecordingForegroundService.ACTION_RECORDING_ERROR,
                ACTION_CLOSE -> finishGate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            )
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { alpha = WINDOW_ALPHA }
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }
        setContentView(View(this).apply { setBackgroundColor(Color.TRANSPARENT) })
        registerRecordingReceiver()
    }

    override fun onResume() {
        super.onResume()
        if (!launchRequested) {
            launchRequested = true
            // Posting guarantees the activity has reached RESUMED before the FGS request.
            window.decorView.post(::startRecordingService)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) runCatching { unregisterReceiver(recordingReceiver) }
        super.onDestroy()
    }

    private fun startRecordingService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendError(getString(R.string.error_microphone_permission))
            finishGate()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingForegroundService::class.java)
                    .setAction(RecordingForegroundService.ACTION_START),
            )
            handler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MILLIS)
        }.onFailure { error ->
            sendError(error.message ?: getString(R.string.error_recording))
            finishGate()
        }
    }

    private fun registerRecordingReceiver() {
        val filter = IntentFilter().apply {
            addAction(RecordingForegroundService.ACTION_RECORDING_STARTED)
            addAction(RecordingForegroundService.ACTION_RECORDING_FINISHED)
            addAction(RecordingForegroundService.ACTION_RECORDING_ERROR)
            addAction(ACTION_CLOSE)
        }
        ContextCompat.registerReceiver(
            this,
            recordingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun sendError(message: String) {
        sendBroadcast(
            Intent(RecordingForegroundService.ACTION_RECORDING_ERROR)
                .setPackage(packageName)
                .putExtra(RecordingForegroundService.EXTRA_ERROR, message.take(500)),
        )
    }

    private fun finishGate() {
        handler.removeCallbacks(startupTimeout)
        if (!isFinishing) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val ACTION_CLOSE = "com.voicevoice.app.action.CLOSE_RECORDING_GATE"
        private const val STARTUP_TIMEOUT_MILLIS = 10_000L
        private const val WINDOW_ALPHA = 0.01f
    }
}
