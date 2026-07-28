package com.voicevoice.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import com.voicevoice.app.domain.HistoryEntry
import com.voicevoice.app.domain.HistoryKind
import java.util.concurrent.CopyOnWriteArraySet

class HistoryRepository(context: Context) {
    private val database = HistoryDatabase(context.applicationContext)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insertResult(
        kind: HistoryKind,
        sourceText: String,
        resultText: String,
        packageName: String?,
        automaticallyInserted: Boolean,
    ): Long {
        val id = database.writableDatabase.insertOrThrow(
            TABLE_HISTORY,
            null,
            ContentValues().apply {
                put(COLUMN_KIND, kind.name)
                put(COLUMN_SOURCE_TEXT, sourceText)
                put(COLUMN_RESULT_TEXT, resultText)
                put(COLUMN_PACKAGE_NAME, packageName)
                put(COLUMN_AUTOMATICALLY_INSERTED, if (automaticallyInserted) 1 else 0)
                putNull(COLUMN_PARENT_ID)
                put(COLUMN_CREATED_AT, System.currentTimeMillis())
            },
        )
        notifyChanged()
        return id
    }

    fun insertCorrection(
        parentId: Long,
        originalText: String,
        correctedText: String,
        packageName: String?,
    ): Long {
        val id = database.writableDatabase.insertOrThrow(
            TABLE_HISTORY,
            null,
            ContentValues().apply {
                put(COLUMN_KIND, HistoryKind.CORRECTION.name)
                put(COLUMN_SOURCE_TEXT, originalText)
                put(COLUMN_RESULT_TEXT, correctedText)
                put(COLUMN_PACKAGE_NAME, packageName)
                put(COLUMN_AUTOMATICALLY_INSERTED, 1)
                put(COLUMN_PARENT_ID, parentId)
                put(COLUMN_CREATED_AT, System.currentTimeMillis())
            },
        )
        notifyChanged()
        return id
    }

    fun list(limit: Int = 200): List<HistoryEntry> {
        val safeLimit = limit.coerceIn(1, 500).toString()
        return database.readableDatabase.query(
            TABLE_HISTORY,
            HISTORY_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC, $COLUMN_ID DESC",
            safeLimit,
        ).use { cursor ->
            val result = ArrayList<HistoryEntry>(cursor.count)
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val kindIndex = cursor.getColumnIndexOrThrow(COLUMN_KIND)
            val sourceIndex = cursor.getColumnIndexOrThrow(COLUMN_SOURCE_TEXT)
            val resultIndex = cursor.getColumnIndexOrThrow(COLUMN_RESULT_TEXT)
            val packageIndex = cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME)
            val insertedIndex = cursor.getColumnIndexOrThrow(COLUMN_AUTOMATICALLY_INSERTED)
            val parentIndex = cursor.getColumnIndexOrThrow(COLUMN_PARENT_ID)
            val createdIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
            while (cursor.moveToNext()) {
                result += HistoryEntry(
                    id = cursor.getLong(idIndex),
                    kind = runCatching { HistoryKind.valueOf(cursor.getString(kindIndex)) }
                        .getOrDefault(HistoryKind.TRANSCRIPTION),
                    sourceText = cursor.getString(sourceIndex),
                    resultText = cursor.getString(resultIndex),
                    packageName = if (cursor.isNull(packageIndex)) null else cursor.getString(packageIndex),
                    automaticallyInserted = cursor.getInt(insertedIndex) == 1,
                    parentId = if (cursor.isNull(parentIndex)) null else cursor.getLong(parentIndex),
                    createdAt = cursor.getLong(createdIndex),
                )
            }
            result
        }
    }

    fun clear() {
        database.writableDatabase.delete(TABLE_HISTORY, null, null)
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        mainHandler.post {
            listeners.forEach { listener -> runCatching(listener) }
        }
    }
}

private class HistoryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_KIND TEXT NOT NULL,
                $COLUMN_SOURCE_TEXT TEXT NOT NULL,
                $COLUMN_RESULT_TEXT TEXT NOT NULL,
                $COLUMN_PACKAGE_NAME TEXT,
                $COLUMN_AUTOMATICALLY_INSERTED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PARENT_ID INTEGER,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX history_created_at_idx ON $TABLE_HISTORY($COLUMN_CREATED_AT DESC)",
        )
        database.execSQL(
            "CREATE INDEX history_parent_idx ON $TABLE_HISTORY($COLUMN_PARENT_ID)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

private const val DATABASE_NAME = "voicevoice_history.db"
private const val DATABASE_VERSION = 1
private const val TABLE_HISTORY = "history"
private const val COLUMN_ID = "id"
private const val COLUMN_KIND = "kind"
private const val COLUMN_SOURCE_TEXT = "source_text"
private const val COLUMN_RESULT_TEXT = "result_text"
private const val COLUMN_PACKAGE_NAME = "package_name"
private const val COLUMN_AUTOMATICALLY_INSERTED = "automatically_inserted"
private const val COLUMN_PARENT_ID = "parent_id"
private const val COLUMN_CREATED_AT = "created_at"
private val HISTORY_COLUMNS = arrayOf(
    COLUMN_ID,
    COLUMN_KIND,
    COLUMN_SOURCE_TEXT,
    COLUMN_RESULT_TEXT,
    COLUMN_PACKAGE_NAME,
    COLUMN_AUTOMATICALLY_INSERTED,
    COLUMN_PARENT_ID,
    COLUMN_CREATED_AT,
)
