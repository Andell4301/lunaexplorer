package com.lunaexplorer.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.lunaexplorer.app.model.StoredProcedure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

class ProcedureStore(
    context: Context,
    databaseName: String = "procedures.db",
    private val now: () -> Instant = Instant::now,
) :
    SQLiteOpenHelper(context, databaseName, null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _procedures = MutableStateFlow<List<StoredProcedure>>(emptyList())
    val procedures = _procedures.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE procedures (id TEXT PRIMARY KEY, payload TEXT NOT NULL, next_run INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported procedure database schema: $oldVersion (expected $newVersion)")
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private fun read(): List<StoredProcedure> =
        readableDatabase.rawQuery("SELECT payload FROM procedures ORDER BY rowid", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(json.decodeFromString<StoredProcedure>(cursor.getString(0)))
            }
        }

    suspend fun refresh() = io { _procedures.value = read() }

    suspend fun list(): List<StoredProcedure> = io { read().also { _procedures.value = it } }

    suspend fun hasScheduled(): Boolean = io { deadlines().isNotEmpty() }

    suspend fun get(id: String): StoredProcedure? = io {
        readableDatabase.rawQuery("SELECT payload FROM procedures WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) json.decodeFromString<StoredProcedure>(it.getString(0)) else null
        }
    }

    suspend fun save(procedure: StoredProcedure) = io {
        procedure.validate()
        val values = ContentValues().apply { put("payload", json.encodeToString(procedure)) }
        val db = writableDatabase
        val held = read().firstOrNull { it.id == procedure.id }
        if (held == null || held.schedule != procedure.schedule) {
            values.put("next_run", procedure.schedule?.nextRunAfter(now())?.toEpochMilli())
        }
        if (db.update("procedures", values, "id=?", arrayOf(procedure.id)) == 0) {
            values.put("id", procedure.id)
            db.insertOrThrow("procedures", null, values)
        }
        _procedures.value = read()
    }

    suspend fun remove(id: String) = io {
        writableDatabase.delete("procedures", "id=?", arrayOf(id))
        _procedures.value = read()
    }

    suspend fun replace(procedures: List<StoredProcedure>) = io {
        procedures.forEach { it.validate() }
        require(procedures.map { it.id }.distinct().size == procedures.size) { "Duplicate procedure IDs" }
        val db = writableDatabase
        val held = read().associateBy { it.id }
        val due = deadlines()
        db.beginTransaction()
        try {
            db.delete("procedures", null, null)
            procedures.forEach { procedure ->
                db.insertOrThrow("procedures", null, ContentValues().apply {
                    put("id", procedure.id)
                    put("payload", json.encodeToString(procedure))
                    put("next_run", if (held[procedure.id]?.schedule == procedure.schedule) due[procedure.id]
                        else procedure.schedule?.nextRunAfter(now())?.toEpochMilli())
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _procedures.value = read()
    }

    private fun deadlines(): Map<String, Long> =
        readableDatabase.rawQuery("SELECT id,next_run FROM procedures WHERE next_run IS NOT NULL", null).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getLong(1)) }
        }

    suspend fun dispatchDue(
        now: Instant = this.now(),
        enqueue: suspend (StoredProcedure, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val due = deadlines()
            read().forEach { procedure ->
                val schedule = procedure.schedule?.takeIf { it.enabled } ?: return@forEach
                val at = due[procedure.id]?.takeIf { it <= now.toEpochMilli() } ?: return@forEach
                // The queue deduplicates this occurrence if execution stopped before its deadline advanced.
                enqueue(procedure, at)
                writableDatabase.update("procedures", ContentValues().apply {
                    put("next_run", schedule.nextRunAfter(now)?.toEpochMilli())
                }, "id=?", arrayOf(procedure.id))
            }
        }
    }
}
