package com.voicevoice.app.runtime

import android.os.Handler
import android.os.Looper
import com.voicevoice.app.domain.RecordedAudio
import com.voicevoice.app.domain.RuntimePhase
import com.voicevoice.app.domain.RuntimeState
import java.util.concurrent.CopyOnWriteArraySet

object AppRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(RuntimeState) -> Unit>()

    @Volatile
    private var current = RuntimeState()

    fun state(): RuntimeState = current

    fun update(phase: RuntimePhase, message: String) {
        val next = RuntimeState(phase = phase, message = message)
        current = next
        mainHandler.post {
            listeners.forEach { listener -> runCatching { listener(next) } }
        }
    }

    fun addListener(listener: (RuntimeState) -> Unit) {
        listeners += listener
        mainHandler.post { listener(current) }
    }

    fun removeListener(listener: (RuntimeState) -> Unit) {
        listeners -= listener
    }
}

object RecordingBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var listener: ((Result<RecordedAudio>) -> Unit)? = null
    private var pending: Result<RecordedAudio>? = null

    fun setListener(value: ((Result<RecordedAudio>) -> Unit)?) {
        val deliver = synchronized(lock) {
            listener = value
            if (value == null) null else pending.also { pending = null }
        }
        if (value != null && deliver != null) {
            mainHandler.post { value(deliver) }
        }
    }

    fun publish(result: Result<RecordedAudio>) {
        val target = synchronized(lock) {
            listener.also { current ->
                if (current == null) pending = result
            }
        }
        if (target != null) mainHandler.post { target(result) }
    }
}
