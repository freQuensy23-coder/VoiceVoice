package com.voicevoice.app.debug

import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicevoice.app.VoiceVoiceApplication
import com.voicevoice.app.model.HistoryEntry
import com.voicevoice.app.model.HistoryType
import com.voicevoice.app.ui.theme.VoiceVoiceTheme
import kotlinx.coroutines.delay

class ManualTestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val graph = (application as VoiceVoiceApplication).graph
        val autoInsert = intent.getBooleanExtra(EXTRA_AUTO_INSERT, true)
        graph.enableDeterministicManualTestMode(DeterministicProviderResolver())
        graph.settingsRepository.update {
            it.copy(
                openRouterApiKey = "debug-only-not-a-real-key",
                autoInsertEnabled = autoInsert,
                postProcessEnabled = true,
                storeHistory = true,
            )
        }
        setContent {
            VoiceVoiceTheme(dynamicColor = false) {
                ManualTestHost(
                    historyLoader = { graph.historyRepository.list() },
                    clearHistory = { graph.historyRepository.clear() },
                    readClipboard = {
                        getSystemService(ClipboardManager::class.java)
                            .primaryClip
                            ?.getItemAt(0)
                            ?.coerceToText(this)
                            ?.toString()
                            .orEmpty()
                    },
                    autoInsertEnabled = autoInsert,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_AUTO_INSERT = "auto_insert"
    }
}

@Composable
private fun ManualTestHost(
    historyLoader: () -> List<HistoryEntry>,
    clearHistory: () -> Unit,
    readClipboard: () -> String,
    autoInsertEnabled: Boolean,
) {
    var fieldText by remember { mutableStateOf("") }
    var clipboardText by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val history = remember(refresh) { historyLoader() }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        delay(350)
        keyboardController?.hide()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VoiceVoice manual test target", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Alexey is discussing VoiceVoice and OpenRouter in Telegram. The requested deadline is tomorrow.")
        Text(
            if (autoInsertEnabled) "Delivery mode: automatic insertion" else "Delivery mode: clipboard only",
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = fieldText,
            onValueChange = { fieldText = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text("Selected editable field") },
            minLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh++ }) { Text("Refresh history") }
            Button(onClick = { fieldText = fieldText.replace("tomorrow.", "today.") }) {
                Text("Correct transcript")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { fieldText += "!" }) { Text("Correct current text") }
            Button(
                onClick = {
                    clearHistory()
                    refresh++
                },
            ) { Text("Clear history") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    clipboardText = readClipboard()
                },
            ) { Text("Read clipboard") }
            Button(
                onClick = {
                    clipboardText = readClipboard()
                    fieldText = clipboardText
                },
            ) { Text("Paste clipboard manually") }
        }
        Text("Field value: $fieldText", fontWeight = FontWeight.SemiBold)
        Text("Clipboard value: $clipboardText", fontWeight = FontWeight.SemiBold)
        Text("History count: ${history.size}", fontWeight = FontWeight.SemiBold)
        Text(
            "History types: transcriptions=${history.count { it.type == HistoryType.TRANSCRIPTION }}, " +
                "corrections=${history.count { it.type == HistoryType.CORRECTION }}",
            fontWeight = FontWeight.SemiBold,
        )
        history.forEach { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(entry.type.name, fontWeight = FontWeight.Bold)
                    Text(entry.text)
                    entry.sourceText?.let { Text("Source: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
