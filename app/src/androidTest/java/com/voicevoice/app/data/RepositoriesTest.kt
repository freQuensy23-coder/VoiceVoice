package com.voicevoice.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicevoice.app.model.HistoryType
import com.voicevoice.app.model.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoriesTest {
    private lateinit var context: Context
    private lateinit var history: SqliteHistoryRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("voicevoice_history.db")
        context.getSharedPreferences("voicevoice_settings_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        history = SqliteHistoryRepository(context)
    }

    @After
    fun tearDown() {
        history.close()
        context.deleteDatabase("voicevoice_history.db")
        context.getSharedPreferences("voicevoice_settings_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun historyPersistsOrdersLimitsAndClearsEveryResultType() {
        assertEquals(-1L, history.add(HistoryType.TRANSCRIPTION, " "))
        history.add(
            HistoryType.TRANSCRIPTION,
            "Transcript",
            sourceText = "raw transcript",
            appPackage = "org.telegram.messenger",
            createdAtMillis = 100,
        )
        history.add(
            HistoryType.CORRECTION,
            "Corrected",
            sourceText = "Transcript",
            appPackage = "org.telegram.messenger",
            createdAtMillis = 200,
        )
        val entries = history.list()

        assertEquals(
            listOf(HistoryType.CORRECTION, HistoryType.TRANSCRIPTION),
            entries.map { it.type },
        )
        assertEquals("Transcript", entries.first().sourceText)
        assertEquals("org.telegram.messenger", entries.first().appPackage)
        assertEquals(listOf(HistoryType.CORRECTION), history.list(limit = 1).map { it.type })

        history.clear()
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun settingsRoundTripEveryFieldAndEncryptApiKeyAtRest() {
        val repository = SecureSettingsRepository(context)
        val settings = Settings(
            openRouterApiKey = "private-api-key",
            voiceProviderId = "voice-provider",
            voiceModel = " voice-model ",
            llmProviderId = "llm-provider",
            llmModel = " llm-model ",
            languageHint = " RU ",
            postProcessEnabled = false,
            autoInsertEnabled = false,
            storeHistory = false,
            downloadedLocalModelIds = setOf("whisper-small", "local-llm"),
        )

        repository.save(settings)
        val loaded = repository.load()

        assertEquals("private-api-key", loaded.openRouterApiKey)
        assertEquals("voice-provider", loaded.voiceProviderId)
        assertEquals("voice-model", loaded.voiceModel)
        assertEquals("llm-provider", loaded.llmProviderId)
        assertEquals("llm-model", loaded.llmModel)
        assertEquals("RU", loaded.languageHint)
        assertFalse(loaded.postProcessEnabled)
        assertFalse(loaded.autoInsertEnabled)
        assertFalse(loaded.storeHistory)
        assertEquals(settings.downloadedLocalModelIds, loaded.downloadedLocalModelIds)

        val stored = context.getSharedPreferences("voicevoice_settings_v1", Context.MODE_PRIVATE)
            .getString("openrouter_key", "")
            .orEmpty()
        assertTrue(stored.isNotBlank())
        assertFalse(stored.contains("private-api-key"))
        assertTrue(stored.contains(":"))
    }

    @Test
    fun settingsUpdateIsAtomicAndBlankKeyRemovesEncryptedValue() {
        val repository = SecureSettingsRepository(context)
        repository.save(Settings(openRouterApiKey = "key"))

        val updated = repository.update {
            it.copy(openRouterApiKey = "", languageHint = "de")
        }

        assertEquals("de", updated.languageHint)
        assertEquals("", repository.load().openRouterApiKey)
        assertEquals(
            "",
            context.getSharedPreferences("voicevoice_settings_v1", Context.MODE_PRIVATE)
                .getString("openrouter_key", null),
        )
    }
}
