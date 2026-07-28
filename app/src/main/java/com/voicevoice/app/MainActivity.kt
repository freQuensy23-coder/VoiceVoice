package com.voicevoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import com.voicevoice.app.accessibility.VoiceVoiceAccessibilityService
import com.voicevoice.app.ui.VoiceVoiceScreen
import com.voicevoice.app.ui.theme.VoiceVoiceTheme

class MainActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshVersion.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as VoiceVoiceApplication).graph
        setContent {
            VoiceVoiceTheme {
                val version by refreshVersion
                VoiceVoiceScreen(
                    refreshVersion = version,
                    settings = graph.settingsRepository.load(),
                    history = graph.historyRepository.list(),
                    microphoneGranted = hasMicrophonePermission(),
                    notificationsGranted = hasNotificationPermission(),
                    accessibilityEnabled = isVoiceVoiceAccessibilityEnabled(),
                    onRequestPermissions = ::requestRuntimePermissions,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onSaveSettings = { settings ->
                        graph.settingsRepository.save(settings)
                        refreshVersion.intValue++
                    },
                    onClearHistory = {
                        graph.historyRepository.clear()
                        refreshVersion.intValue++
                    },
                    onRefresh = { refreshVersion.intValue++ },
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false)) {
            requestRuntimePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVersion.intValue++
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (!hasMicrophonePermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isVoiceVoiceAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                    info.resolveInfo.serviceInfo.name == VoiceVoiceAccessibilityService::class.java.name
            }
    }

    companion object {
        const val EXTRA_REQUEST_MICROPHONE = "request_microphone"
    }
}
