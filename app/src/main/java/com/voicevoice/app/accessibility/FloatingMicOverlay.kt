package com.voicevoice.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.voicevoice.app.R
import com.voicevoice.app.domain.RuntimePhase
import com.voicevoice.app.domain.RuntimeState
import com.voicevoice.app.settings.DebugModeController
import kotlin.math.abs

class FloatingMicOverlay(
    private val service: AccessibilityService,
    private val onClick: () -> Unit,
) {
    private val manualTestMode = DebugModeController.isEnabled(service)
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val size = dp(if (manualTestMode) 96 else 72)
    private val root = FrameLayout(service)
    private val button = TextView(service)
    private val progress = ProgressBar(service)
    private val params = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = service.resources.displayMetrics.widthPixels - size - dp(12)
        y = service.resources.displayMetrics.heightPixels / 2
    }
    private var attached = false

    init {
        button.apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (manualTestMode) 12f else 10f
            typeface = Typeface.DEFAULT_BOLD
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            compoundDrawablePadding = dp(4)
            setPadding(dp(10), dp(12), dp(10), dp(10))
            elevation = dp(8).toFloat()
            setOnTouchListener(DragTouchListener())
        }
        progress.apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(
            button,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            progress,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER),
        )
        update(RuntimeState())
    }

    fun show() {
        if (attached) return
        windowManager.addView(root, params)
        attached = true
    }

    fun update(state: RuntimeState) {
        val processing = state.phase == RuntimePhase.PROCESSING
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        button.isEnabled = !processing
        button.alpha = 1f
        button.text = when (state.phase) {
            RuntimePhase.RECORDING -> "STOP"
            RuntimePhase.PROCESSING -> "WAIT"
            RuntimePhase.ERROR -> "RETRY"
            else -> "MIC"
        }
        if (processing) {
            button.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            button.setPadding(dp(10), dp(10), dp(10), dp(6))
            button.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            button.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_mic, 0, 0)
            button.setPadding(dp(10), dp(12), dp(10), dp(10))
            button.gravity = Gravity.CENTER
        }
        button.contentDescription = when (state.phase) {
            RuntimePhase.IDLE, RuntimePhase.COMPLETE -> "Start recording"
            RuntimePhase.RECORDING -> "Stop recording"
            RuntimePhase.PROCESSING -> "Processing recording"
            RuntimePhase.ERROR -> "Start recording after error"
        }
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when (state.phase) {
                    RuntimePhase.RECORDING -> Color.rgb(198, 40, 40)
                    RuntimePhase.PROCESSING -> Color.rgb(84, 110, 122)
                    RuntimePhase.ERROR -> Color.rgb(239, 108, 0)
                    else -> Color.rgb(49, 94, 251)
                },
            )
        }
    }

    fun remove() {
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!manualTestMode) {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        if (attached) windowManager.updateViewLayout(root, params)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - touchX) + abs(event.rawY - touchY)
                    if (manualTestMode || moved < dp(16)) onClick()
                    return true
                }
            }
            return false
        }
    }
}
