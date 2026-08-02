package com.voicevoice.app.provider

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDescriptorValidatorTest {
    private val valid = LocalModelDescriptor(
        id = "whisper-small.en-v1",
        downloadUrl = "https://models.example.com/whisper-small.bin",
        sha256 = "a".repeat(64),
        fileName = "whisper-small.bin",
    )

    @Test
    fun acceptsSafeHttpsDescriptorWithSha256() {
        LocalModelDescriptorValidator.validate(valid)
    }

    @Test
    fun rejectsUnsafeIdentityFilenameOrDigest() {
        assertInvalid(valid.copy(id = "../outside"), "ID")
        assertInvalid(valid.copy(fileName = "../outside.bin"), "filename")
        assertInvalid(valid.copy(fileName = "model..bin"), "filename")
        assertInvalid(valid.copy(sha256 = "abc"), "SHA-256")
    }

    @Test
    fun rejectsCleartextCredentialedOrHostlessUrls() {
        assertInvalid(valid.copy(downloadUrl = "http://models.example.com/model.bin"), "HTTPS")
        assertInvalid(
            valid.copy(downloadUrl = "https://user:password@models.example.com/model.bin"),
            "credentials",
        )
        assertInvalid(valid.copy(downloadUrl = "https:/model.bin"), "host")
    }

    private fun assertInvalid(descriptor: LocalModelDescriptor, expectedMessage: String) {
        val error = runCatching {
            LocalModelDescriptorValidator.validate(descriptor)
        }.exceptionOrNull()
        assertTrue("Expected validation failure containing $expectedMessage", error != null)
        assertTrue(
            "Unexpected message: ${error?.message}",
            error?.message.orEmpty().contains(expectedMessage, ignoreCase = true),
        )
    }
}
