package com.voicevoice.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.voicevoice.app.model.Settings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SettingsRepository {
    fun load(): Settings
    fun save(settings: Settings)
    fun update(transform: (Settings) -> Settings): Settings
}

class SecureSettingsRepository(context: Context) : SettingsRepository {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun load(): Settings = synchronized(lock) {
        Settings(
            openRouterApiKey = decryptOrEmpty(preferences.getString(KEY_OPENROUTER_KEY, null)),
            voiceProviderId = preferences.getString(KEY_VOICE_PROVIDER, Settings.OPENROUTER_PROVIDER)
                ?: Settings.OPENROUTER_PROVIDER,
            voiceModel = preferences.getString(KEY_VOICE_MODEL, Settings.DEFAULT_VOICE_MODEL)
                ?: Settings.DEFAULT_VOICE_MODEL,
            llmProviderId = preferences.getString(KEY_LLM_PROVIDER, Settings.OPENROUTER_PROVIDER)
                ?: Settings.OPENROUTER_PROVIDER,
            llmModel = preferences.getString(KEY_LLM_MODEL, Settings.DEFAULT_LLM_MODEL)
                ?: Settings.DEFAULT_LLM_MODEL,
            languageHint = preferences.getString(KEY_LANGUAGE_HINT, "").orEmpty(),
            postProcessEnabled = preferences.getBoolean(KEY_POST_PROCESS, true),
            autoInsertEnabled = preferences.getBoolean(KEY_AUTO_INSERT, true),
            storeHistory = preferences.getBoolean(KEY_STORE_HISTORY, true),
            downloadedLocalModelIds = preferences.getStringSet(KEY_LOCAL_MODELS, emptySet())?.toSet()
                ?: emptySet(),
        )
    }

    override fun save(settings: Settings) = synchronized(lock) {
        val encryptedKey = if (settings.openRouterApiKey.isBlank()) {
            ""
        } else {
            encrypt(settings.openRouterApiKey)
        }
        preferences.edit {
            putString(KEY_OPENROUTER_KEY, encryptedKey)
            putString(KEY_VOICE_PROVIDER, settings.voiceProviderId)
            putString(KEY_VOICE_MODEL, settings.voiceModel.trim())
            putString(KEY_LLM_PROVIDER, settings.llmProviderId)
            putString(KEY_LLM_MODEL, settings.llmModel.trim())
            putString(KEY_LANGUAGE_HINT, settings.languageHint.trim())
            putBoolean(KEY_POST_PROCESS, settings.postProcessEnabled)
            putBoolean(KEY_AUTO_INSERT, settings.autoInsertEnabled)
            putBoolean(KEY_STORE_HISTORY, settings.storeHistory)
            putStringSet(KEY_LOCAL_MODELS, settings.downloadedLocalModelIds.toSet())
        }
    }

    override fun update(transform: (Settings) -> Settings): Settings = synchronized(lock) {
        val updated = transform(load())
        save(updated)
        updated
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decryptOrEmpty(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val separator = value.indexOf(':')
            require(separator > 0)
            val iv = Base64.decode(value.substring(0, separator), Base64.NO_WRAP)
            val encrypted = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse {
            preferences.edit { remove(KEY_OPENROUTER_KEY) }
            ""
        }
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

    private companion object {
        const val PREFS = "voicevoice_settings_v1"
        const val KEY_ALIAS = "voicevoice_settings_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_OPENROUTER_KEY = "openrouter_key"
        const val KEY_VOICE_PROVIDER = "voice_provider"
        const val KEY_VOICE_MODEL = "voice_model"
        const val KEY_LLM_PROVIDER = "llm_provider"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_LANGUAGE_HINT = "language_hint"
        const val KEY_POST_PROCESS = "post_process"
        const val KEY_AUTO_INSERT = "auto_insert"
        const val KEY_STORE_HISTORY = "store_history"
        const val KEY_LOCAL_MODELS = "local_models"
    }
}
