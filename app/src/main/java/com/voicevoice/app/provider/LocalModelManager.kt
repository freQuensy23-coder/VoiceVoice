package com.voicevoice.app.provider

import android.content.Context
import com.voicevoice.app.data.SettingsRepository
import com.voicevoice.app.model.VoiceVoiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Local model downloads happen only through the explicit requestDownload call. */
interface LocalModelManager {
    suspend fun requestDownload(descriptor: LocalModelDescriptor, onProgress: (Int) -> Unit = {}): File
    fun installedModel(descriptor: LocalModelDescriptor): File?
}

data class LocalModelDescriptor(
    val id: String,
    val downloadUrl: String,
    val sha256: String,
    val fileName: String,
)

class ExplicitLocalModelManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
) : LocalModelManager {
    private val directory = File(context.filesDir, "local-models").apply {
        if (!exists() && !mkdirs()) throw VoiceVoiceException("Could not create the local model directory")
    }.canonicalFile

    override suspend fun requestDownload(
        descriptor: LocalModelDescriptor,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        validateDescriptor(descriptor)
        val target = resolveModelFile(descriptor.fileName)
        val temporary = resolveModelFile(descriptor.fileName + ".download")
        if (temporary.exists() && !temporary.delete()) {
            throw VoiceVoiceException("Could not remove an incomplete model download")
        }

        val connection = openHttpsConnection(URL(descriptor.downloadUrl))
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw VoiceVoiceException("Model download failed with HTTP $status")
            val total = connection.contentLengthLong
            if (total > 0 && total > directory.usableSpace) {
                throw VoiceVoiceException("There is not enough free storage for this model")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 99))
                    }
                    output.fd.sync()
                }
            }

            val actual = sha256(temporary)
            if (!actual.equals(descriptor.sha256, ignoreCase = true)) {
                throw VoiceVoiceException("Downloaded model checksum does not match")
            }
            if (target.exists() && !target.delete()) {
                throw VoiceVoiceException("Could not replace the installed local model")
            }
            if (!temporary.renameTo(target)) throw VoiceVoiceException("Could not install downloaded model")
            settingsRepository.update {
                it.copy(downloadedLocalModelIds = it.downloadedLocalModelIds + descriptor.id)
            }
            onProgress(100)
            target
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun installedModel(descriptor: LocalModelDescriptor): File? {
        if (runCatching { validateDescriptor(descriptor) }.isFailure) return null
        if (descriptor.id !in settingsRepository.load().downloadedLocalModelIds) return null
        return runCatching { resolveModelFile(descriptor.fileName) }
            .getOrNull()
            ?.takeIf(File::isFile)
    }

    private fun validateDescriptor(descriptor: LocalModelDescriptor) {
        require(descriptor.id.matches(SAFE_ID)) { "Local model ID is invalid" }
        require(descriptor.fileName.matches(SAFE_FILE_NAME)) { "Local model filename is invalid" }
        require(!descriptor.fileName.contains("..")) { "Local model filename is invalid" }
        require(descriptor.sha256.matches(SHA_256)) { "A SHA-256 digest is required" }
        val url = URL(descriptor.downloadUrl)
        require(url.protocol.equals("https", ignoreCase = true)) { "Local model URL must use HTTPS" }
        require(url.userInfo == null) { "Local model URL must not contain credentials" }
    }

    private fun resolveModelFile(fileName: String): File {
        val file = File(directory, fileName).canonicalFile
        require(file.parentFile == directory) { "Local model path escapes the model directory" }
        return file
    }

    private fun openHttpsConnection(initialUrl: URL): HttpURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!current.protocol.equals("https", ignoreCase = true)) {
                throw VoiceVoiceException("Model download redirected to a non-HTTPS URL")
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status !in REDIRECT_STATUS_CODES) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw VoiceVoiceException("Model download redirect is missing a location")
            if (redirectCount == MAX_REDIRECTS) throw VoiceVoiceException("Model download has too many redirects")
            current = URL(current, location)
        }
        throw VoiceVoiceException("Model download has too many redirects")
    }

    private fun sha256(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
        val SHA_256 = Regex("[a-fA-F0-9]{64}")
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
    }
}
