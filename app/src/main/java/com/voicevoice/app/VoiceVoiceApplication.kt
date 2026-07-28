package com.voicevoice.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.voicevoice.app.collector.DataCollectorRegistry
import com.voicevoice.app.domain.RuntimePhase
import com.voicevoice.app.history.HistoryRepository
import com.voicevoice.app.provider.ProviderFactory
import com.voicevoice.app.recording.VoiceRecordingService
import com.voicevoice.app.runtime.AppRuntime
import com.voicevoice.app.settings.SettingsRepository

class VoiceVoiceApplication : Application(), Application.ActivityLifecycleCallbacks {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(this)
    }

    /**
     * Prepare the microphone foreground-service host while MainActivity is visible. Android 14+
     * rejects creation of a microphone foreground service after the application is already in the
     * background, so the Accessibility overlay only controls this pre-existing host.
     */
    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (VoiceRecordingService.isPrepared()) return

        runCatching { VoiceRecordingService.prepare(activity) }
            .onFailure { error ->
                AppRuntime.update(
                    RuntimePhase.ERROR,
                    error.message?.take(180)?.takeIf(String::isNotBlank)
                        ?: "Unable to prepare background recording",
                )
            }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val historyRepository = HistoryRepository(context)
    val dataCollectors = DataCollectorRegistry()
    val providerFactory = ProviderFactory()
}
