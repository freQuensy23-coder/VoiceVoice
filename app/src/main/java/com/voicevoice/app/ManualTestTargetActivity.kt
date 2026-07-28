package com.voicevoice.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.voicevoice.app.domain.RuntimeState
import com.voicevoice.app.runtime.AppRuntime
import com.voicevoice.app.settings.DebugModeController

class ManualTestTargetActivity : ComponentActivity() {
    private lateinit var clipboardPreview: TextView
    private lateinit var runtimePreview: TextView
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        updateClipboardPreview()
    }
    private val runtimeListener: (RuntimeState) -> Unit = { state ->
        updateRuntimePreview(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !DebugModeController.isEnabled(this)) {
            finish()
            return
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val editText = EditText(this).apply {
            id = R.id.manual_test_input
            hint = "Selected editable field"
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        runtimePreview = TextView(this).apply {
            textSize = 18f
        }
        clipboardPreview = TextView(this)
        val historyButton = Button(this).apply {
            text = "Open VoiceVoice history"
            setOnClickListener {
                startActivity(Intent(this@ManualTestTargetActivity, MainActivity::class.java))
            }
        }
        val clearButton = Button(this).apply {
            text = "Clear editable field"
            setOnClickListener {
                editText.setText("")
                editText.requestFocus()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@ManualTestTargetActivity).apply {
                text = "VoiceVoice manual field"
                textSize = 24f
            })
            addView(TextView(this@ManualTestTargetActivity).apply {
                text = "Keep this field selected. The fixed right-side control is labelled MIC, STOP, or WAIT."
            })
            addView(runtimePreview)
            addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = padding },
            )
            addView(clipboardPreview)
            addView(clearButton)
            addView(historyButton)
        }
        setContentView(layout)
        editText.requestFocus()
        updateRuntimePreview(AppRuntime.state())
        updateClipboardPreview()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(ClipboardManager::class.java)
            .addPrimaryClipChangedListener(clipboardListener)
        AppRuntime.addListener(runtimeListener)
        updateClipboardPreview()
    }

    override fun onStop() {
        AppRuntime.removeListener(runtimeListener)
        getSystemService(ClipboardManager::class.java)
            .removePrimaryClipChangedListener(clipboardListener)
        super.onStop()
    }

    private fun updateRuntimePreview(state: RuntimeState) {
        if (!::runtimePreview.isInitialized) return
        runtimePreview.text = "Voice state: ${state.phase.name}\n${state.message}"
    }

    private fun updateClipboardPreview() {
        if (!::clipboardPreview.isInitialized) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val value = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        clipboardPreview.text = "Clipboard: ${value.ifEmpty { "empty" }}"
    }
}
