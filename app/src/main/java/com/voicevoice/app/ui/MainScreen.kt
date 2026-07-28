package com.voicevoice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("voicevoice-list")
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HeroCard(
                    accessibilityEnabled = accessibilityEnabled,
                    onRefresh = onRefresh,
                )
            }
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
            item { LocalModelsCard(settings) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Your private activity timeline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (history.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear") }
                }
            }
            if (history.isEmpty()) {
                item { EmptyHistoryCard() }
            } else {
                items(history, key = { it.id }) { entry -> HistoryCard(entry) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HeroCard(
    accessibilityEnabled: Boolean,
    onRefresh: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(primary, secondary)))
                .padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "VOICEVOICE",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.82f),
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(
                        onClick = onRefresh,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    ) { Text("Refresh") }
                }
                Text(
                    "Speak anywhere.\nKeep your context.",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "A floating voice layer that transcribes, cleans up, translates, and inserts your words.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f),
                )
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                ) {
                    Text(
                        if (accessibilityEnabled) "●  Floating control active" else "○  Finish setup to activate",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
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
    val readyCount = listOf(microphoneGranted, notificationsGranted, accessibilityEnabled).count { it }
    VoiceVoiceCard {
        SectionTitle(
            eyebrow = "GET STARTED",
            title = "Setup",
            detail = "$readyCount of 3 ready",
        )
        LinearProgressIndicator(
            progress = { readyCount / 3f },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        StatusLine("Microphone", "Records only after you tap Start", microphoneGranted)
        StatusLine("Notifications", "Shows the recording service", notificationsGranted)
        StatusLine("Accessibility", "Floating control, context, and insertion", accessibilityEnabled)
        if (!microphoneGranted || !notificationsGranted) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Grant app permissions") }
        }
        if (!accessibilityEnabled) {
            OutlinedButton(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Enable Accessibility service") }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    detail: String,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                Text(
                    if (enabled) "✓" else "!",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (enabled) "Ready" else "Required",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
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

    VoiceVoiceCard {
        SectionTitle(
            eyebrow = "ENGINE",
            title = "Providers and behavior",
            detail = "OpenRouter · configurable",
        )
        Text(
            "Your key is encrypted with Android Keystore and never bundled into the application.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenRouter API key") },
            placeholder = {
                Text(if (settings.openRouterApiKey.isBlank()) "Required for production" else "Saved securely — blank keeps it")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = voiceModel,
            onValueChange = { voiceModel = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Speech-to-text model") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = llmModel,
            onValueChange = { llmModel = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Post-processing LLM") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = languageHint,
            onValueChange = { languageHint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Audio language hint") },
            supportingText = { Text("Optional ISO-639-1 code, for example en or ru") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = targetLanguage,
            onValueChange = { targetLanguage = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Translation target") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        ToggleLine("Context-aware cleanup", "Use the visible screen to fix names and terms", postProcess) {
            postProcess = it
        }
        ToggleLine("Automatic insertion", "Write into the selected editable field", autoInsert) {
            autoInsert = it
        }
        ToggleLine("Private local history", "Keep results and tracked corrections on device", storeHistory) {
            storeHistory = it
        }
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
            shape = RoundedCornerShape(16.dp),
        ) { Text("Save configuration") }
        if (settings.openRouterApiKey.isNotBlank()) {
            TextButton(
                onClick = { onSaveSettings(settings.copy(openRouterApiKey = "")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Remove saved API key") }
        }
    }
}

@Composable
private fun ToggleLine(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun LocalModelsCard(settings: Settings) {
    VoiceVoiceCard {
        SectionTitle(
            eyebrow = "ON DEVICE",
            title = "Local models",
            detail = if (settings.downloadedLocalModelIds.isEmpty()) "Nothing downloaded" else "Ready",
        )
        Text(
            "Models download only after an explicit user request. Every download is HTTPS-only and verified by SHA-256 before installation.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                "Installed IDs  ·  ${settings.downloadedLocalModelIds.joinToString().ifBlank { "None" }}",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No activity yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Transcriptions, translations, and corrections to automatically inserted text will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    val accent = when (entry.type) {
        HistoryType.TRANSCRIPTION -> MaterialTheme.colorScheme.primary
        HistoryType.TRANSLATION -> MaterialTheme.colorScheme.tertiary
        HistoryType.CORRECTION -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = accent.copy(alpha = 0.12f),
                ) {
                    Text(
                        historyLabel(entry.type),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(entry.createdAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            entry.sourceText?.takeIf(String::isNotBlank)?.let {
                Text(
                    "Previous: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.appPackage?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceVoiceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(
    eyebrow: String,
    title: String,
    detail: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                eyebrow,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun historyLabel(type: HistoryType): String = when (type) {
    HistoryType.TRANSCRIPTION -> "Transcription"
    HistoryType.TRANSLATION -> "Translation"
    HistoryType.CORRECTION -> "Correction"
}
