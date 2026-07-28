package com.voicevoice.app

import android.content.Context
import com.voicevoice.app.accessibility.AccessibilitySessionState
import com.voicevoice.app.accessibility.AccessibilitySnapshotFactory
import com.voicevoice.app.accessibility.DataCollectorRegistry
import com.voicevoice.app.data.HistoryRepository
import com.voicevoice.app.data.SecureSettingsRepository
import com.voicevoice.app.data.SettingsRepository
import com.voicevoice.app.data.SqliteHistoryRepository
import com.voicevoice.app.pipeline.VoicePipeline
import com.voicevoice.app.provider.ProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val accessibilitySession = AccessibilitySessionState()
    val settingsRepository: SettingsRepository by lazy { SecureSettingsRepository(appContext) }
    val historyRepository: HistoryRepository by lazy { SqliteHistoryRepository(appContext) }
    val dataCollectorRegistry: DataCollectorRegistry by lazy { DataCollectorRegistry() }
    val accessibilitySnapshotFactory: AccessibilitySnapshotFactory by lazy { AccessibilitySnapshotFactory() }
    val providerFactory: ProviderFactory by lazy { ProviderFactory(appContext, settingsRepository) }
    val voicePipeline: VoicePipeline by lazy {
        VoicePipeline(settingsRepository, historyRepository, providerFactory)
    }
}
