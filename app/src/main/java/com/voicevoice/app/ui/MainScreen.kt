package com.voicevoice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicevoice.app.model.HistoryEntry
import com.voicevoice.app.model.HistoryType
import com.voicevoice.app.model.Settings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceVoiceScreen(
    refreshVersion: Int,
    settings: Settings,
    history: List<HistoryEntry>,
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    accessibilityEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onSaveSettings: (Settings) -> Unit,
    onClearHistory: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VoiceVoice", fontWeight = FontWeight.Bold)
                        Text("Accessibility voice transcription", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = { TextButton(onClick = onRefresh) { Text("Refresh") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("voicevoice-list")
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SetupCard(
                    microphoneGranted = microphoneGranted,
                    notificationsGranted = notificationsGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
            }
            item {
                SettingsCard(
                    refreshVersion = refreshVersion,
                    settings = settings,
                    onSaveSettings = onSaveSettings,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Local models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "No model is downloaded automatically. A local provider must call the explicit, checksum-verified download API after a user request.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Installed model IDs: ${settings.downloadedLocalModelIds.joinToString().ifBlank { "none" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (history.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear") }
                }
            }
            if (history.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Transcriptions, translations, and tracked corrections will appear here.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { entry -> HistoryCard(entry) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SetupCard(
    microphoneGranted: Boolean,
    notificationsGranted: Boolean,
    accessibilityEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            StatusLine("Microphone", microphoneGranted)
            StatusLine("Notifications", notificationsGranted)
            StatusLine("Accessibility service", accessibilityEnabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!microphoneGranted || !notificationsGranted) {
                    Button(onClick = onRequestPermissions) { Text("Grant permissions") }
                }
                if (!accessibilityEnabled) {
                    OutlinedButton(onClick = onOpenAccessibilitySettings) { Text("Enable accessibility") }
                }
            }
            if (accessibilityEnabled) {
                Text(
                    "The floating Start button is active over other applications.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            if (enabled) "Ready" else "Required",
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingsCard(
    refreshVersion: Int,
    settings: Settings,
    onSaveSettings: (Settings) -> Unit,
) {
    var apiKeyInput by remember(refreshVersion) { mutableStateOf("") }
    var voiceModel by remember(refreshVersion) { mutableStateOf(settings.voiceModel) }
    var llmModel by remember(refreshVersion) { mutableStateOf(settings.llmModel) }
    var languageHint by remember(refreshVersion) { mutableStateOf(settings.languageHint) }
    var targetLanguage by remember(refreshVersion) { mutableStateOf(settings.targetLanguage) }
    var postProcess by remember(refreshVersion) { mutableStateOf(settings.postProcessEnabled) }
    var autoInsert by remember(refreshVersion) { mutableStateOf(settings.autoInsertEnabled) }
    var storeHistory by remember(refreshVersion) { mutableStateOf(settings.storeHistory) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Providers and behavior", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenRouter API key") },
                placeholder = {
                    Text(if (settings.openRouterApiKey.isBlank()) "Required for production" else "Saved — leave blank to keep")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
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
                label = { Text("Post-processing LLM") },
                singleLine = true,
            )
            OutlinedTextField(
                value = languageHint,
                onValueChange = { languageHint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Audio language hint (ISO-639-1, optional)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = targetLanguage,
                onValueChange = { targetLanguage = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Translation target") },
                singleLine = true,
            )
            ToggleLine("Post-process transcription", postProcess) { postProcess = it }
            ToggleLine("Automatically insert into focused field", autoInsert) { autoInsert = it }
            ToggleLine("Store local history", storeHistory) { storeHistory = it }
            HorizontalDivider()
            Button(
                onClick = {
                    onSaveSettings(
                        settings.copy(
                            openRouterApiKey = apiKeyInput.takeIf(String::isNotBlank) ?: settings.openRouterApiKey,
                            voiceModel = voiceModel.trim().ifBlank { Settings.DEFAULT_VOICE_MODEL },
                            llmModel = llmModel.trim().ifBlank { Settings.DEFAULT_LLM_MODEL },
                            languageHint = languageHint.trim(),
                            targetLanguage = targetLanguage.trim().ifBlank { "English" },
                            postProcessEnabled = postProcess,
                            autoInsertEnabled = autoInsert,
                            storeHistory = storeHistory,
                        ),
                    )
                    apiKeyInput = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save settings")
            }
            if (settings.openRouterApiKey.isNotBlank()) {
                OutlinedButton(
                    onClick = { onSaveSettings(settings.copy(openRouterApiKey = "")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove saved API key")
                }
            }
        }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(historyLabel(entry.type), fontWeight = FontWeight.Bold)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(entry.createdAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(entry.text)
            entry.sourceText?.takeIf(String::isNotBlank)?.let {
                Text("From: $it", style = MaterialTheme.typography.bodySmall)
            }
            entry.appPackage?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun historyLabel(type: HistoryType): String = when (type) {
    HistoryType.TRANSCRIPTION -> "Transcription"
    HistoryType.TRANSLATION -> "Translation"
    HistoryType.CORRECTION -> "Correction"
}
