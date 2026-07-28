package com.voicevoice.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voicevoice.app.accessibility.VoiceAccessibilityService
import com.voicevoice.app.domain.HistoryEntry
import com.voicevoice.app.domain.RuntimeState
import com.voicevoice.app.runtime.AppRuntime
import com.voicevoice.app.settings.DebugModeController
import com.voicevoice.app.ui.theme.VoiceVoiceTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDebugIntent(intent)
        enableEdgeToEdge()
        setContent {
            VoiceVoiceTheme {
                VoiceVoiceScreen((application as VoiceVoiceApplication).container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugIntent(intent)
    }

    private fun applyDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra(EXTRA_MANUAL_TEST_MODE, false)) return
        DebugModeController.setEnabled(this, true)
        val container = (application as VoiceVoiceApplication).container
        if (intent.hasExtra(EXTRA_TRANSLATION_MODE)) {
            val current = container.settingsRepository.load()
            container.settingsRepository.saveConfiguration(
                voiceProviderId = current.voiceProviderId,
                voiceModel = current.voiceModel,
                llmProviderId = current.llmProviderId,
                llmModel = current.llmModel,
                languageHint = current.languageHint,
                translationEnabled = intent.getBooleanExtra(EXTRA_TRANSLATION_MODE, false),
                targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE)
                    ?: current.targetLanguage,
            )
        }
    }

    companion object {
        const val EXTRA_MANUAL_TEST_MODE = "manual_test_mode"
        const val EXTRA_TRANSLATION_MODE = "translation_mode"
        const val EXTRA_TARGET_LANGUAGE = "target_language"
    }
}

@Composable
private fun VoiceVoiceScreen(container: AppContainer) {
    val context = LocalContext.current
    val initial = remember { container.settingsRepository.load() }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeySaved by remember { mutableStateOf(initial.apiKey.isNotBlank()) }
    var voiceModel by remember { mutableStateOf(initial.voiceModel) }
    var llmModel by remember { mutableStateOf(initial.llmModel) }
    var languageHint by remember { mutableStateOf(initial.languageHint) }
    var translationEnabled by remember { mutableStateOf(initial.translationEnabled) }
    var targetLanguage by remember { mutableStateOf(initial.targetLanguage) }
    var runtimeState by remember { mutableStateOf(AppRuntime.state()) }
    var history by remember { mutableStateOf(container.historyRepository.list()) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val manualMode = remember { DebugModeController.isEnabled(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        microphoneGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(Unit) {
        val runtimeListener: (RuntimeState) -> Unit = { runtimeState = it }
        val historyListener: () -> Unit = { history = container.historyRepository.list() }
        AppRuntime.addListener(runtimeListener)
        container.historyRepository.addListener(historyListener)
        onDispose {
            AppRuntime.removeListener(runtimeListener)
            container.historyRepository.removeListener(historyListener)
        }
    }

    DisposableEffect(context) {
        val activity = context as ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityEnabled(context)
                microphoneGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("VoiceVoice", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Background transcription through a floating Accessibility microphone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (manualMode) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Manual test mode", fontWeight = FontWeight.Bold)
                        Text("Network providers are replaced by deterministic test providers in this debug session.")
                        Button(
                            onClick = { context.startActivity(Intent(context, ManualTestTargetActivity::class.java)) },
                        ) {
                            Text("Open editable test screen")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    StatusLine("Microphone permission", microphoneGranted)
                    StatusLine("Accessibility service", accessibilityEnabled)
                    Button(
                        onClick = {
                            val permissions = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant microphone permission")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            accessibilityEnabled = isAccessibilityEnabled(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Accessibility settings")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Providers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenRouter API key") },
                        placeholder = { Text(if (apiKeySaved) "Saved securely" else "Required for cloud providers") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (apiKeyInput.isNotBlank()) {
                                    container.settingsRepository.setApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                    apiKeySaved = true
                                    Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) { Text("Save key") }
                        OutlinedButton(
                            onClick = {
                                container.settingsRepository.clearApiKey()
                                apiKeyInput = ""
                                apiKeySaved = false
                            },
                        ) { Text("Clear key") }
                    }
                    OutlinedTextField(
                        value = voiceModel,
                        onValueChange = { voiceModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Voice model") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = llmModel,
                        onValueChange = { llmModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("LLM model") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = languageHint,
                        onValueChange = { languageHint = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Audio language hint (optional ISO-639-1)") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Translate result", fontWeight = FontWeight.SemiBold)
                            Text("Applied after transcription", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = translationEnabled, onCheckedChange = { translationEnabled = it })
                    }
                    if (translationEnabled) {
                        OutlinedTextField(
                            value = targetLanguage,
                            onValueChange = { targetLanguage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Target language") },
                            singleLine = true,
                        )
                    }
                    Button(
                        onClick = {
                            container.settingsRepository.saveConfiguration(
                                voiceProviderId = "openrouter",
                                voiceModel = voiceModel,
                                llmProviderId = "openrouter",
                                llmModel = llmModel,
                                languageHint = languageHint,
                                translationEnabled = translationEnabled,
                                targetLanguage = targetLanguage,
                            )
                            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save settings")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Current state", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(runtimeState.phase.name.lowercase().replaceFirstChar(Char::uppercase))
                    Text(runtimeState.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Use the floating microphone over any app. Results are always copied; focused editable fields also receive automatic insertion.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (history.isNotEmpty()) {
                    OutlinedButton(onClick = { container.historyRepository.clear() }) {
                        Text("Clear")
                    }
                }
            }
            if (history.isEmpty()) {
                Text("No transcriptions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                history.forEach { entry -> HistoryCard(entry) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(if (enabled) "Enabled" else "Required", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    val context = LocalContext.current
    val timestamp = remember(entry.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdAt))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.kind.name.lowercase().replaceFirstChar(Char::uppercase), fontWeight = FontWeight.Bold)
                Text(timestamp, style = MaterialTheme.typography.bodySmall)
            }
            Text(entry.resultText)
            if (entry.sourceText != entry.resultText) {
                HorizontalDivider()
                Text("Source: ${entry.sourceText}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                buildString {
                    append(if (entry.automaticallyInserted) "Automatically inserted" else "Clipboard only")
                    entry.packageName?.takeIf(String::isNotBlank)?.let { append(" • ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("VoiceVoice history", entry.resultText))
                },
            ) {
                Text("Copy")
            }
        }
    }
}

private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName &&
                info.resolveInfo.serviceInfo.name == VoiceAccessibilityService::class.java.name
        }
}
