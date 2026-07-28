package com.voicevoice.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.voicevoice.app.model.HistoryEntry
import com.voicevoice.app.model.HistoryType

interface HistoryRepository {
    fun add(
        type: HistoryType,
        text: String,
        sourceText: String? = null,
        appPackage: String? = null,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): Long

    fun list(limit: Int = 100): List<HistoryEntry>
    fun latestResultText(): String?
    fun clear()
}

class SqliteHistoryRepository(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
), HistoryRepository {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                text TEXT NOT NULL,
                source_text TEXT,
                app_package TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX history_created_at_idx ON history(created_at DESC)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun add(
        type: HistoryType,
        text: String,
        sourceText: String?,
        appPackage: String?,
        createdAtMillis: Long,
    ): Long {
        if (text.isBlank()) return -1L
        val values = ContentValues().apply {
            put("type", type.name)
            put("text", text)
            put("source_text", sourceText)
            put("app_package", appPackage)
            put("created_at", createdAtMillis)
        }
        return writableDatabase.insertOrThrow("history", null, values)
    }

    override fun list(limit: Int): List<HistoryEntry> {
        val safeLimit = limit.coerceIn(1, 500)
        readableDatabase.query(
            "history",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            safeLimit.toString(),
        ).use { cursor ->
            val entries = ArrayList<HistoryEntry>(cursor.count)
            while (cursor.moveToNext()) {
                entries += HistoryEntry(
                    id = cursor.getLong(0),
                    type = runCatching { HistoryType.valueOf(cursor.getString(1)) }
                        .getOrDefault(HistoryType.TRANSCRIPTION),
                    text = cursor.getString(2),
                    sourceText = cursor.getString(3),
                    appPackage = cursor.getString(4),
                    createdAtMillis = cursor.getLong(5),
                )
            }
            return entries
        }
    }

    override fun latestResultText(): String? {
        readableDatabase.query(
            "history",
            arrayOf("text"),
            "type IN (?, ?)",
            arrayOf(HistoryType.TRANSCRIPTION.name, HistoryType.TRANSLATION.name),
            null,
            null,
            "created_at DESC, id DESC",
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override fun clear() {
        writableDatabase.delete("history", null, null)
    }

    private companion object {
        const val DATABASE_NAME = "voicevoice_history.db"
        const val DATABASE_VERSION = 1
        val COLUMNS = arrayOf("id", "type", "text", "source_text", "app_package", "created_at")
    }
}
