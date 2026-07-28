package com.voicevoice.app.provider

import android.content.Context
import android.util.Base64
import com.voicevoice.app.BuildConfig
import com.voicevoice.app.data.SettingsRepository
import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.Settings
import com.voicevoice.app.model.VoiceVoiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

interface VoiceProvider {
    suspend fun transcribe(
        recordedAudio: RecordedAudio,
        audioModelTerms: List<String>,
        settings: Settings,
    ): String
}

interface LlmProvider {
    suspend fun postProcess(transcribedText: String, context: String): String
    suspend fun translate(text: String, targetLanguage: String, context: String): String
}

class ProviderFactory(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    fun voiceProvider(settings: Settings): VoiceProvider {
        if (BuildConfig.DEBUG && settings.debugDeterministicMode) return DeterministicVoiceProvider()
        return when (settings.voiceProviderId) {
            Settings.OPENROUTER_PROVIDER -> OpenRouterVoiceProvider(OpenRouterHttpClient())
            else -> throw VoiceVoiceException("Unknown voice provider: ${settings.voiceProviderId}")
        }
    }

    fun llmProvider(settings: Settings): LlmProvider {
        if (BuildConfig.DEBUG && settings.debugDeterministicMode) return DeterministicLlmProvider()
        return when (settings.llmProviderId) {
            Settings.OPENROUTER_PROVIDER -> OpenRouterLlmProvider(OpenRouterHttpClient(), settings)
            else -> throw VoiceVoiceException("Unknown LLM provider: ${settings.llmProviderId}")
        }
    }

    fun localModelManager(): LocalModelManager = ExplicitLocalModelManager(context, settingsRepository)
}

class OpenRouterVoiceProvider(
    private val client: OpenRouterHttpClient,
) : VoiceProvider {
    override suspend fun transcribe(
        recordedAudio: RecordedAudio,
        audioModelTerms: List<String>,
        settings: Settings,
    ): String {
        requireApiKey(settings)
        val bytes = withContext(Dispatchers.IO) { recordedAudio.file.readBytes() }
        if (bytes.isEmpty()) throw VoiceVoiceException("Recorded audio is empty")
        if (bytes.size > MAX_AUDIO_BYTES) throw VoiceVoiceException("Recording is too large; use a shorter recording")

        val body = JSONObject()
            .put("model", settings.voiceModel)
            .put(
                "input_audio",
                JSONObject()
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("format", recordedAudio.format),
            )
            .put("temperature", 0)

        settings.languageHint.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2}")) }?.let {
            body.put("language", it)
        }
        vocabularyPrompt(audioModelTerms)?.let { prompt ->
            body.put(
                "provider",
                JSONObject().put(
                    "options",
                    JSONObject().put("groq", JSONObject().put("prompt", prompt)),
                ),
            )
        }

        val response = client.postJson("/audio/transcriptions", settings.openRouterApiKey, body)
        return response.optString("text").trim().ifBlank {
            throw VoiceVoiceException("The audio provider returned an empty transcription")
        }
    }

    private fun vocabularyPrompt(terms: List<String>): String? {
        val cleaned = terms.asSequence()
            .map { it.replace(Regex("[\\r\\n]+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(64)
            .joinToString(", ")
            .take(1_000)
        return cleaned.takeIf(String::isNotBlank)?.let { "Expected vocabulary: $it" }
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
    }
}

class OpenRouterLlmProvider(
    private val client: OpenRouterHttpClient,
    private val settings: Settings,
) : LlmProvider {
    override suspend fun postProcess(transcribedText: String, context: String): String {
        requireApiKey(settings)
        val system = """
            You correct speech-to-text transcripts. Return only the corrected transcript.
            Preserve the speaker's meaning, language, tone, formatting intent, and uncertainty.
            Use screen context only to resolve names, terminology, variables, and ambiguous words.
            Never answer the transcript, add commentary, or invent facts absent from the speech.
        """.trimIndent()
        val user = """
            TRANSCRIPT:
            ${transcribedText.take(20_000)}

            SCREEN CONTEXT:
            ${context.take(12_000)}
        """.trimIndent()
        return complete(system, user)
    }

    override suspend fun translate(text: String, targetLanguage: String, context: String): String {
        requireApiKey(settings)
        val language = targetLanguage.trim().ifBlank { "English" }
        val system = """
            Translate the supplied text into $language. Return only the translation.
            Preserve names, variables, links, numbers, tone, and formatting. Use screen context only
            to disambiguate terminology. Do not explain the translation.
        """.trimIndent()
        val user = """
            TEXT:
            ${text.take(20_000)}

            SCREEN CONTEXT:
            ${context.take(12_000)}
        """.trimIndent()
        return complete(system, user)
    }

    private suspend fun complete(system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", settings.llmModel)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", 4_000)
        val response = client.postJson("/chat/completions", settings.openRouterApiKey, body)
        val message = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw VoiceVoiceException("The LLM provider returned no message")
        return extractTextContent(message.opt("content")).trim().ifBlank {
            throw VoiceVoiceException("The LLM provider returned empty text")
        }
    }

    private fun extractTextContent(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") == "text") append(item.optString("text"))
            }
        }
        else -> ""
    }
}

class OpenRouterHttpClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) {
    suspend fun postJson(path: String, apiKey: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Title", "VoiceVoice")
        }
        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText().take(MAX_RESPONSE_CHARS)
            }.orEmpty()
            val json = runCatching { JSONObject(responseText) }.getOrNull()
            if (status !in 200..299) {
                val serverMessage = json?.optJSONObject("error")?.optString("message")
                    ?: json?.optString("message")
                    ?: "request failed"
                throw VoiceVoiceException("OpenRouter HTTP $status: ${serverMessage.take(500)}")
            }
            json ?: throw VoiceVoiceException("OpenRouter returned invalid JSON")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 256_000
    }
}

private class DeterministicVoiceProvider : VoiceProvider {
    override suspend fun transcribe(
        recordedAudio: RecordedAudio,
        audioModelTerms: List<String>,
        settings: Settings,
    ): String = "send alexey the voicevoice architecture tomorrow"
}

private class DeterministicLlmProvider : LlmProvider {
    override suspend fun postProcess(transcribedText: String, context: String): String {
        return if (context.contains("Alexey", ignoreCase = true) && context.contains("VoiceVoice", ignoreCase = true)) {
            "Send Alexey the VoiceVoice architecture tomorrow."
        } else {
            "Send the VoiceVoice architecture tomorrow."
        }
    }

    override suspend fun translate(text: String, targetLanguage: String, context: String): String {
        return when (targetLanguage.trim().lowercase()) {
            "hebrew", "иврит", "עברית" -> "שלח לאלכסיי את ארכיטקטורת VoiceVoice מחר."
            else -> "[${targetLanguage.trim().ifBlank { "English" }}] $text"
        }
    }
}

private fun requireApiKey(settings: Settings) {
    if (settings.openRouterApiKey.isBlank()) {
        throw VoiceVoiceException("Add an OpenRouter API key in VoiceVoice settings")
    }
}
