package com.voicevoice.app

import android.app.Application

class VoiceVoiceApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
