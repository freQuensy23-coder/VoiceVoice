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
    private val directory = File(context.filesDir, "local-models").apply { mkdirs() }

    override suspend fun requestDownload(
        descriptor: LocalModelDescriptor,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(descriptor.downloadUrl.startsWith("https://")) { "Local model URL must use HTTPS" }
        require(descriptor.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "A SHA-256 digest is required" }
        val target = File(directory, descriptor.fileName)
        val temporary = File(directory, descriptor.fileName + ".download")
        temporary.delete()
        val connection = URL(descriptor.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw VoiceVoiceException("Model download failed with HTTP $status")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            val actual = temporary.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(descriptor.sha256, ignoreCase = true)) {
                temporary.delete()
                throw VoiceVoiceException("Downloaded model checksum does not match")
            }
            if (target.exists()) target.delete()
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
        val target = File(directory, descriptor.fileName)
        return target.takeIf(File::isFile)
    }
}
