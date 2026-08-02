package com.voicevoice.app.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.voicevoice.app.BuildConfig
import com.voicevoice.app.MainActivity
import com.voicevoice.app.R
import com.voicevoice.app.VoiceVoiceApplication
import com.voicevoice.app.audio.RecordingForegroundService
import com.voicevoice.app.audio.RecordingGateActivity
import com.voicevoice.app.audio.WavFile
import com.voicevoice.app.model.AutoInsertionReceipt
import com.voicevoice.app.model.DataCollectionResult
import com.voicevoice.app.model.HistoryType
import com.voicevoice.app.model.NodeBounds
import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.TargetIdentity
import com.voicevoice.app.pipeline.AccessibilityGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class VoiceVoiceAccessibilityService : AccessibilityService(), AccessibilityGateway {
    private val graph by lazy { (application as VoiceVoiceApplication).graph }
    private val session get() = graph.accessibilitySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val correctionTracker get() = session.correctionTracker

    private lateinit var windowManager: WindowManager
    private var overlayRoot: View? = null
    private var microphoneButton: Button? = null
    private var statusText: TextView? = null
    private var lastObservedPackage: String? = null
    private var receiverRegistered = false

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingForegroundService.ACTION_RECORDING_STARTED -> {
                    renderState(OverlayState.RECORDING, getString(R.string.overlay_recording))
                }
                RecordingForegroundService.ACTION_RECORDING_FINISHED -> {
                    val path = intent.getStringExtra(RecordingForegroundService.EXTRA_AUDIO_PATH)
                    if (path.isNullOrBlank()) {
                        renderState(OverlayState.ERROR, getString(R.string.error_missing_audio))
                    } else {
                        processRecording(File(path))
                    }
                }
                RecordingForegroundService.ACTION_RECORDING_ERROR -> {
                    val message = intent.getStringExtra(RecordingForegroundService.EXTRA_ERROR)
                        ?: getString(R.string.error_recording)
                    renderState(OverlayState.ERROR, message)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        windowManager = getSystemService(WindowManager::class.java)
        val plan = accessibilityConnectionPlan(
            receiverRegistered = receiverRegistered,
            overlayAttached = overlayRoot != null,
        )
        if (plan.registerReceiver) registerRecordingReceiver()
        if (plan.initializeOverlay) {
            showOverlay()
        }
        renderState(
            session.overlayState,
            session.statusMessage.ifBlank { getString(R.string.overlay_ready) },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        event.packageName?.toString()?.takeIf(String::isNotBlank)?.let { lastObservedPackage = it }
        if (!correctionTracker.hasActiveSession()) return
        val source = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> event.source
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.source?.takeIf { it.isEditable } ?: focusedEditableNode()
            }
            else -> null
        } ?: return
        val text = source.text?.toString() ?: return
        correctionTracker.onTextChanged(identityOf(source), text)
        scheduleCorrectionPersistence()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (activeInstance === this) activeInstance = null
        runCatching { stopService(Intent(this, RecordingForegroundService::class.java)) }
        closeRecordingGate()
        if (receiverRegistered) runCatching { unregisterReceiver(recordingReceiver) }
        overlayRoot?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayRoot = null
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayRoot != null) return
        val density = resources.displayMetrics.density
        val padding = (10 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            background = roundedBackground(Color.argb(245, 24, 25, 34), 22f * density)
            elevation = 12f * density
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { onMicrophoneClick() }
        }
        val mic = Button(this).apply {
            text = getString(R.string.overlay_start)
            contentDescription = getString(R.string.overlay_microphone_description)
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            backgroundTintList = ColorStateList.valueOf(Color.rgb(113, 80, 255))
            setOnClickListener { onMicrophoneClick() }
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
            maxWidth = (170 * density).toInt()
        }
        root.addView(mic, LinearLayout.LayoutParams(dp(118), WindowManager.LayoutParams.WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(dp(150), WindowManager.LayoutParams.WRAP_CONTENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(160)
        }
        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                overlayRoot = root
                microphoneButton = mic
                statusText = status
            }
    }

    private fun onMicrophoneClick() {
        when (session.overlayState) {
            OverlayState.IDLE, OverlayState.SUCCESS, OverlayState.ERROR -> startRecording()
            OverlayState.RECORDING -> stopRecording()
            OverlayState.PROCESSING -> Unit
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            renderState(OverlayState.ERROR, getString(R.string.error_microphone_permission))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_REQUEST_MICROPHONE, true),
            )
            return
        }
        if (BuildConfig.DEBUG && graph.settingsRepository.load().debugDeterministicMode) {
            session.deterministicDebugRecording = true
            renderState(OverlayState.RECORDING, getString(R.string.overlay_recording))
            return
        }
        runCatching {
            renderState(OverlayState.PROCESSING, getString(R.string.overlay_starting))
            startActivity(
                Intent(this, RecordingGateActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
        }.onFailure { error ->
            renderState(OverlayState.ERROR, error.message ?: getString(R.string.error_recording))
        }
    }

    private fun stopRecording() {
        if (session.deterministicDebugRecording) {
            session.deterministicDebugRecording = false
            renderState(OverlayState.PROCESSING, getString(R.string.overlay_processing))
            runCatching {
                File.createTempFile("voicevoice-debug-", ".wav", cacheDir).also {
                    WavFile.writeSilence(it)
                    processDeterministicDebugRecording(it)
                }
            }.onFailure { error ->
                renderState(OverlayState.ERROR, error.message ?: getString(R.string.error_recording))
            }
            return
        }
        renderState(OverlayState.PROCESSING, getString(R.string.overlay_processing))
        runCatching {
            startService(
                Intent(this, RecordingForegroundService::class.java)
                    .setAction(RecordingForegroundService.ACTION_STOP),
            )
        }.onFailure { error ->
            renderState(OverlayState.ERROR, error.message ?: getString(R.string.error_recording))
        }
        closeRecordingGate()
    }

    private fun closeRecordingGate() {
        sendBroadcast(
            Intent(RecordingGateActivity.ACTION_CLOSE).setPackage(packageName),
        )
    }

    private fun processRecording(audioFile: File) {
        renderState(OverlayState.PROCESSING, getString(R.string.overlay_processing))
        scope.launch {
            try {
                val result = graph.voicePipeline.transcribe(RecordedAudio(audioFile), this@VoiceVoiceAccessibilityService)
                mainHandler.post {
                    val message = if (result.insertedAutomatically) {
                        getString(R.string.overlay_copied_inserted)
                    } else {
                        getString(R.string.overlay_copied)
                    }
                    renderState(OverlayState.SUCCESS, message)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    renderState(OverlayState.ERROR, error.message ?: getString(R.string.error_processing))
                }
            } finally {
                audioFile.delete()
            }
        }
    }

    private fun processDeterministicDebugRecording(audioFile: File) {
        session.deterministicDebugJob?.cancel()
        session.deterministicDebugJob = graph.applicationScope.launch {
            try {
                delay(DEBUG_PROCESSING_DELAY_MILLIS)
                val gateway = awaitActiveInstance()
                    ?: throw IllegalStateException("Accessibility service did not reconnect")
                val result = graph.voicePipeline.transcribe(RecordedAudio(audioFile), gateway)
                val message = if (result.insertedAutomatically) {
                    gateway.getString(R.string.overlay_copied_inserted)
                } else {
                    gateway.getString(R.string.overlay_copied)
                }
                activeInstance?.renderState(OverlayState.SUCCESS, message)
                    ?: run {
                        session.overlayState = OverlayState.SUCCESS
                        session.statusMessage = message
                    }
            } catch (error: Exception) {
                val message = error.message ?: getString(R.string.error_processing)
                activeInstance?.renderState(OverlayState.ERROR, message)
                    ?: run {
                        session.overlayState = OverlayState.ERROR
                        session.statusMessage = message
                    }
            } finally {
                audioFile.delete()
                session.deterministicDebugJob = null
            }
        }
    }

    private suspend fun awaitActiveInstance(): VoiceVoiceAccessibilityService? {
        repeat(ACTIVE_INSTANCE_RETRIES) {
            activeInstance?.let { return it }
            delay(ACTIVE_INSTANCE_RETRY_MILLIS)
        }
        return null
    }

    private fun scheduleCorrectionPersistence() {
        session.correctionPersistenceJob?.cancel()
        session.correctionPersistenceJob = graph.applicationScope.launch {
            delay(CORRECTION_DEBOUNCE_MILLIS)
            awaitActiveInstance()?.persistPendingCorrection()
            session.correctionPersistenceJob = null
        }
    }

    override fun collectContext(): DataCollectionResult {
        val snapshot = graph.accessibilitySnapshotFactory.capture(this)
        if (snapshot.packageName.isNotBlank()) lastObservedPackage = snapshot.packageName
        return graph.dataCollectorRegistry.resolve(snapshot.packageName).collect(snapshot)
    }

    override fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    override fun insertIntoFocusedField(text: String): AutoInsertionReceipt? {
        val node = focusedEditableNode() ?: return null
        val current = node.text?.toString().orEmpty()
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart in 0..current.length) rawStart else current.length
        val end = if (rawEnd in start..current.length) rawEnd else start
        val prefix = current.substring(0, start)
        val suffix = current.substring(end)
        return setNodeText(node, prefix, text, suffix)
    }

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        prefix: String,
        inserted: String,
        suffix: String,
    ): AutoInsertionReceipt? {
        val fullText = prefix + inserted + suffix
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fullText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return null
        val selectionArguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, prefix.length + inserted.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, prefix.length + inserted.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArguments)
        return AutoInsertionReceipt(
            target = identityOf(node),
            prefix = prefix,
            insertedText = inserted,
            suffix = suffix,
            fullTextAfterInsertion = fullText,
        )
    }

    override fun registerAutomaticInsertion(receipt: AutoInsertionReceipt) {
        session.lastInsertionReceipt = receipt
        correctionTracker.begin(receipt)
    }

    override fun clearCorrectionTracking() {
        session.correctionPersistenceJob?.cancel()
        session.correctionPersistenceJob = null
        session.lastInsertionReceipt = null
        correctionTracker.clear()
    }

    override fun currentApplicationPackage(): String? = lastObservedPackage

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused?.isEditable == true && focused.isEnabled) return focused
        }
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.takeIf { it.isEditable && it.isEnabled }
    }

    private fun persistPendingCorrection() {
        val correction = correctionTracker.consumePendingCorrection() ?: return
        session.lastInsertionReceipt = session.lastInsertionReceipt?.copy(
            insertedText = correction.correctedText,
            fullTextAfterInsertion = correction.fullFieldText,
        )
        if (graph.settingsRepository.load().storeHistory) {
            graph.historyRepository.add(
                type = HistoryType.CORRECTION,
                text = correction.correctedText,
                sourceText = correction.originalText,
                appPackage = session.lastInsertionReceipt?.target?.packageName ?: lastObservedPackage,
            )
        }
        val message = getString(R.string.overlay_correction_saved)
        session.statusMessage = message
        statusText?.text = message
    }

    private fun identityOf(node: AccessibilityNodeInfo): TargetIdentity {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return TargetIdentity(
            packageName = node.packageName?.toString().orEmpty(),
            windowId = node.windowId,
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
        )
    }

    private fun renderState(state: OverlayState, message: String) {
        session.overlayState = state
        session.statusMessage = message
        microphoneButton?.apply {
            text = when (state) {
                OverlayState.IDLE, OverlayState.SUCCESS, OverlayState.ERROR -> getString(R.string.overlay_start)
                OverlayState.RECORDING -> getString(R.string.overlay_stop)
                OverlayState.PROCESSING -> getString(R.string.overlay_loading)
            }
            isEnabled = state != OverlayState.PROCESSING
        }
        statusText?.text = message.take(160)
    }

    private fun registerRecordingReceiver() {
        val filter = IntentFilter().apply {
            addAction(RecordingForegroundService.ACTION_RECORDING_STARTED)
            addAction(RecordingForegroundService.ACTION_RECORDING_FINISHED)
            addAction(RecordingForegroundService.ACTION_RECORDING_ERROR)
        }
        ContextCompat.registerReceiver(
            this,
            recordingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        @Volatile
        var activeInstance: VoiceVoiceAccessibilityService? = null

        const val CORRECTION_DEBOUNCE_MILLIS = 750L
        const val DEBUG_PROCESSING_DELAY_MILLIS = 20_000L
        const val ACTIVE_INSTANCE_RETRIES = 20
        const val ACTIVE_INSTANCE_RETRY_MILLIS = 250L
    }
}

internal data class AccessibilityConnectionPlan(
    val registerReceiver: Boolean,
    val initializeOverlay: Boolean,
)

internal fun accessibilityConnectionPlan(
    receiverRegistered: Boolean,
    overlayAttached: Boolean,
): AccessibilityConnectionPlan = AccessibilityConnectionPlan(
    registerReceiver = !receiverRegistered,
    initializeOverlay = !overlayAttached,
)
