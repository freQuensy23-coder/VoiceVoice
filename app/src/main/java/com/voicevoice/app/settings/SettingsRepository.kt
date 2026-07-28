package com.voicevoice.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.voicevoice.app.BuildConfig
import com.voicevoice.app.domain.AppSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureValues = SecureValueStore(appContext)

    fun load(): AppSettings = AppSettings(
        apiKey = secureValues.get(API_KEY).orEmpty(),
        voiceProviderId = preferences.getString(VOICE_PROVIDER, DEFAULT_PROVIDER).orEmpty(),
        voiceModel = preferences.getString(VOICE_MODEL, DEFAULT_VOICE_MODEL).orEmpty(),
        llmProviderId = preferences.getString(LLM_PROVIDER, DEFAULT_PROVIDER).orEmpty(),
        llmModel = preferences.getString(LLM_MODEL, DEFAULT_LLM_MODEL).orEmpty(),
        languageHint = preferences.getString(LANGUAGE_HINT, "").orEmpty(),
        translationEnabled = preferences.getBoolean(TRANSLATION_ENABLED, false),
        targetLanguage = preferences.getString(TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE).orEmpty(),
    )

    fun saveConfiguration(
        voiceProviderId: String,
        voiceModel: String,
        llmProviderId: String,
        llmModel: String,
        languageHint: String,
        translationEnabled: Boolean,
        targetLanguage: String,
    ) {
        preferences.edit()
            .putString(VOICE_PROVIDER, voiceProviderId.trim().ifEmpty { DEFAULT_PROVIDER })
            .putString(VOICE_MODEL, voiceModel.trim().ifEmpty { DEFAULT_VOICE_MODEL })
            .putString(LLM_PROVIDER, llmProviderId.trim().ifEmpty { DEFAULT_PROVIDER })
            .putString(LLM_MODEL, llmModel.trim().ifEmpty { DEFAULT_LLM_MODEL })
            .putString(LANGUAGE_HINT, languageHint.trim())
            .putBoolean(TRANSLATION_ENABLED, translationEnabled)
            .putString(TARGET_LANGUAGE, targetLanguage.trim().ifEmpty { DEFAULT_TARGET_LANGUAGE })
            .apply()
    }

    fun setApiKey(value: String) {
        val normalized = value.trim()
        if (normalized.isNotEmpty()) {
            secureValues.put(API_KEY, normalized)
        }
    }

    fun clearApiKey() {
        secureValues.remove(API_KEY)
    }

    companion object {
        const val DEFAULT_PROVIDER = "openrouter"
        const val DEFAULT_VOICE_MODEL = "openai/whisper-large-v3"
        const val DEFAULT_LLM_MODEL = "google/gemini-3.6-flash"
        const val DEFAULT_TARGET_LANGUAGE = "English"

        private const val PREFERENCES_NAME = "voicevoice_settings"
        private const val API_KEY = "openrouter_api_key"
        private const val VOICE_PROVIDER = "voice_provider"
        private const val VOICE_MODEL = "voice_model"
        private const val LLM_PROVIDER = "llm_provider"
        private const val LLM_MODEL = "llm_model"
        private const val LANGUAGE_HINT = "language_hint"
        private const val TRANSLATION_ENABLED = "translation_enabled"
        private const val TARGET_LANGUAGE = "target_language"
    }
}

object DebugModeController {
    private const val PREFERENCES_NAME = "voicevoice_debug"
    private const val ENABLED = "manual_test_mode"

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    fun isEnabled(context: Context): Boolean = BuildConfig.DEBUG &&
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
}

private class SecureValueStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        preferences.edit().putString(name, encoded).apply()
    }

    fun get(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return runCatching {
            val separator = encoded.indexOf(':')
            require(separator > 0 && separator < encoded.lastIndex)
            val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(name).apply()
            null
        }
    }

    fun remove(name: String) {
        preferences.edit().remove(name).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "voicevoice_secure_values"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "voicevoice.settings.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
