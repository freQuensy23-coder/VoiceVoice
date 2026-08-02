package com.voicevoice.app.provider

import com.voicevoice.app.model.RecordedAudio
import com.voicevoice.app.model.Settings
import com.voicevoice.app.model.VoiceVoiceException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class OpenRouterProviderTest {
    @Test
    fun voiceProviderBuildsDocumentedAudioRequestWithBoundedVocabulary() = runTest {
        val client = FakeJsonClient(JSONObject().put("text", "  hello VoiceVoice  "))
        val bytes = byteArrayOf(0, 1, 2, 3)
        val audio = audio(bytes, "wav")
        val settings = Settings(
            openRouterApiKey = "secret",
            voiceModel = "openai/whisper-large-v3",
            languageHint = "EN",
        )

        val result = OpenRouterVoiceProvider(client).transcribe(
            audio,
            listOf("VoiceVoice", "Alexey\nSmith", "VoiceVoice", " "),
            settings,
        )

        assertEquals("hello VoiceVoice", result)
        val call = client.calls.single()
        assertEquals("/audio/transcriptions", call.path)
        assertEquals("secret", call.apiKey)
        assertEquals("openai/whisper-large-v3", call.body.getString("model"))
        assertEquals(0, call.body.getInt("temperature"))
        assertEquals("en", call.body.getString("language"))
        val input = call.body.getJSONObject("input_audio")
        assertEquals("wav", input.getString("format"))
        assertEquals(Base64.getEncoder().encodeToString(bytes), input.getString("data"))
        val prompt = call.body
            .getJSONObject("provider")
            .getJSONObject("options")
            .getJSONObject("groq")
            .getString("prompt")
        assertEquals("Expected vocabulary: VoiceVoice, Alexey Smith", prompt)
    }

    @Test
    fun voiceProviderOmitsInvalidLanguageAndVocabularyAndRejectsEmptyResponses() = runTest {
        val client = FakeJsonClient(JSONObject().put("text", " "))
        val error = runCatching {
            OpenRouterVoiceProvider(client).transcribe(
                audio(byteArrayOf(9)),
                emptyList(),
                Settings(openRouterApiKey = "key", languageHint = "english"),
            )
        }.exceptionOrNull()

        assertTrue(error is VoiceVoiceException)
        assertFalse(client.calls.single().body.has("language"))
        assertFalse(client.calls.single().body.has("provider"))
    }

    @Test
    fun voiceProviderRejectsMissingKeyAndEmptyAudioBeforeNetwork() = runTest {
        val missingKeyClient = FakeJsonClient(JSONObject())
        val keyError = runCatching {
            OpenRouterVoiceProvider(missingKeyClient).transcribe(
                audio(byteArrayOf(1)),
                emptyList(),
                Settings(),
            )
        }.exceptionOrNull()

        assertTrue(keyError is VoiceVoiceException)
        assertTrue(missingKeyClient.calls.isEmpty())

        val emptyAudioClient = FakeJsonClient(JSONObject())
        val audioError = runCatching {
            OpenRouterVoiceProvider(emptyAudioClient).transcribe(
                audio(byteArrayOf()),
                emptyList(),
                Settings(openRouterApiKey = "key"),
            )
        }.exceptionOrNull()

        assertTrue(audioError is VoiceVoiceException)
        assertTrue(emptyAudioClient.calls.isEmpty())
    }

    @Test
    fun llmPostProcessingSendsTranscriptContextAndExtractsStringContent() = runTest {
        val response = completionResponse("  Corrected VoiceVoice text.  ")
        val client = FakeJsonClient(response)
        val provider = OpenRouterLlmProvider(
            client,
            Settings(openRouterApiKey = "key", llmModel = "openai/gpt-5.6-luna"),
        )

        val result = provider.postProcess("voice voice text", "Telegram with Alexey")

        assertEquals("Corrected VoiceVoice text.", result)
        val call = client.calls.single()
        assertEquals("/chat/completions", call.path)
        assertEquals("openai/gpt-5.6-luna", call.body.getString("model"))
        assertEquals(0, call.body.getInt("temperature"))
        val messages = call.body.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("Return only"))
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("voice voice text"))
        assertTrue(user.contains("Telegram with Alexey"))
    }

    @Test
    fun llmPostProcessingExtractsArrayTextResponse() = runTest {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "שלום "))
            .put(JSONObject().put("type", "image").put("image_url", "ignored"))
            .put(JSONObject().put("type", "text").put("text", "עולם"))
        val client = FakeJsonClient(completionResponse(content))
        val provider = OpenRouterLlmProvider(
            client,
            Settings(openRouterApiKey = "key"),
        )

        val result = provider.postProcess("hello world", "chat context")

        assertEquals("שלום עולם", result)
        val messages = client.calls.single().body.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("correct speech-to-text"))
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("hello world"))
        assertTrue(user.contains("chat context"))
    }

    @Test
    fun llmRejectsMissingMessageAndEmptyContent() = runTest {
        val noMessage = FakeJsonClient(JSONObject().put("choices", JSONArray()))
        val missingError = runCatching {
            OpenRouterLlmProvider(noMessage, Settings(openRouterApiKey = "key"))
                .postProcess("text", "context")
        }.exceptionOrNull()
        assertTrue(missingError is VoiceVoiceException)

        val empty = FakeJsonClient(completionResponse(""))
        val emptyError = runCatching {
            OpenRouterLlmProvider(empty, Settings(openRouterApiKey = "key"))
                .postProcess("text", "context")
        }.exceptionOrNull()
        assertTrue(emptyError is VoiceVoiceException)
    }

    private fun audio(bytes: ByteArray, format: String = "wav"): RecordedAudio {
        val file = File.createTempFile("voicevoice-provider-", ".$format")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return RecordedAudio(file, format)
    }

    private fun completionResponse(content: Any): JSONObject = JSONObject().put(
        "choices",
        JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", content)),
        ),
    )

    private data class JsonCall(
        val path: String,
        val apiKey: String,
        val body: JSONObject,
    )

    private class FakeJsonClient(
        var response: JSONObject,
    ) : JsonHttpClient {
        val calls = mutableListOf<JsonCall>()

        override suspend fun postJson(path: String, apiKey: String, body: JSONObject): JSONObject {
            calls += JsonCall(path, apiKey, JSONObject(body.toString()))
            return response
        }
    }
}
