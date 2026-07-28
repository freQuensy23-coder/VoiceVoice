package com.voicevoice.app.provider

import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenRouterClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1/",
) {
    fun postJson(path: String, payload: JSONObject, apiKey: String): JSONObject {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }
        val connection = URL(baseUrl + path.trimStart('/')).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/freQuensy23-coder/VoiceVoice")
            connection.setRequestProperty("X-Title", "VoiceVoice")

            connection.outputStream.buffered().use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let(::readBounded).orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { body.take(500) }.ifBlank { "HTTP $status" }
                error("OpenRouter request failed: $message")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream): String {
        BufferedInputStream(stream).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_RESPONSE_BYTES) { "OpenRouter response is too large" }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 120_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
