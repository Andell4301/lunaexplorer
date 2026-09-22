package com.lunaexplorer.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.util.UUID
import java.io.File
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteOpenHelper
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.ConflictRow
import com.lunaexplorer.app.model.QueueItem
import com.lunaexplorer.app.model.ProcedureRun
import com.lunaexplorer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LunaDatabase(private val context: Context, databaseName: String = "luna.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    private val json = SessionCodec.json
    private fun ref(value: NodeRef): String = json.encodeToString(value)
    private fun nodeRef(text: String): NodeRef = json.decodeFromString(text)
    private fun refs(text: String): List<NodeRef> = json.decodeFromString(text)
    private fun conflictRows(text: String): List<ConflictRow> = json.decodeFromString(text)
    private val lock = Mutex()
    private var lastPublishedAt = 0L
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = _queue.asStateFlow()
    private val _procedureRuns = MutableStateFlow<List<ProcedureRun>>(emptyList())
    val procedureRuns = _procedureRuns.asStateFlow()
    private val _trash = MutableStateFlow<List<TrashedItem>>(emptyList())
    val trash = _trash.asStateFlow()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', bytes INTEGER NOT NULL DEFAULT 0, current_name TEXT NOT NULL DEFAULT '', conflicts TEXT NOT NULL DEFAULT '[]', cancelled INTEGER NOT NULL DEFAULT 0, procedure_id TEXT)")
        db.execSQL("CREATE INDEX operation_order ON operations(status, created)")
        db.execSQL("CREATE INDEX procedure_order ON operations(procedure_id, created)")
        db.execSQL("CREATE TABLE operation_events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL REFERENCES operations(id), message TEXT NOT NULL, artifacts TEXT NOT NULL DEFAULT '[]', source TEXT, destination TEXT)")
        db.execSQL("CREATE INDEX event_order ON operation_events(operation_id, sequence)")
        db.execSQL(TRASH_TABLE)
        db.execSQL(OPEN_DEFAULTS_TABLE)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != 1 || newVersion != 2) {
            throw SQLiteException("Unsupported Luna database schema: $oldVersion (expected $newVersion)")
        }
        db.execSQL("ALTER TABLE operations ADD COLUMN procedure_id TEXT")
        db.execSQL("CREATE INDEX procedure_order ON operations(procedure_id, created)")
    }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    suspend fun openDefault(keys: List<String>): OpenDefault? = io {
        keys.firstNotNullOfOrNull { key ->
            readableDatabase.rawQuery(
                "SELECT target, mime, label FROM open_defaults WHERE \"key\"=?", arrayOf(key),
            ).use { if (it.moveToFirst()) OpenDefault(key, it.getString(0), it.getString(1), it.getString(2)) else null }
        }
    }

    suspend fun rememberOpenDefault(key: String, target: String, mime: String, label: String) = io {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO open_defaults(\"key\", target, mime, label) VALUES(?,?,?,?)",
            arrayOf(key, target, mime, label),
        )
    }

    suspend fun forgetOpenDefault(key: String) = io {
        writableDatabase.execSQL("DELETE FROM open_defaults WHERE \"key\"=?", arrayOf(key))
    }

    suspend fun openDefaults(): List<OpenDefault> = io {
        readableDatabase.rawQuery("SELECT \"key\", target, mime, label FROM open_defaults ORDER BY \"key\"", null).use {
            buildList { while (it.moveToNext()) add(OpenDefault(it.getString(0), it.getString(1), it.getString(2), it.getString(3))) }
        }
    }

    suspend fun forgetAllOpenDefaults() = io { writableDatabase.execSQL("DELETE FROM open_defaults") }

    // VACUUM INTO includes the WAL and requires SQLite 3.27+; never fall back to copying a live database file.
    suspend fun snapshot(path: String, name: String): File? = io {
        val databases = context.getDatabasePath("luna.db").parentFile ?: return@io null
        if (File(path).canonicalFile.parentFile != databases.canonicalFile) return@io null
        if (!path.endsWith(".db")) return@io null
        val folder = File(context.cacheDir, "snapshot-${UUID.randomUUID()}")
        try {
            check(folder.mkdirs()) { "Could not create the snapshot folder" }
            val target = File(folder, name)
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { /* never delete the file */ })
                .use { db -> db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath)) }
            target
        } catch (error: Exception) {
            folder.deleteRecursively()
            if (error is CancellationException) throw error
            throw StorageException(StorageError.IO, "Could not make a safe copy of Luna's live database", error)
        }
    }
    suspend fun loadSession(): BrowserState? = io {
        readableDatabase.rawQuery("SELECT payload FROM session WHERE id=1", null).use {
            if (it.moveToFirst()) SessionCodec.decode(it.getString(0)) else null
        }
    }
    suspend fun saveSession(state: BrowserState) = io {
        val row = writableDatabase.insertWithOnConflict("session", null, ContentValues().apply {
            put("id", 1); put("payload", SessionCodec.encode(state))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(row != -1L) { "Session write failed" }
    }
    suspend fun refreshQueue() = io { publish() }
    private fun publish(force: Boolean = true) {
        val now = System.nanoTime()
        if (!force && now - lastPublishedAt < 150_000_000L) return
        lastPublishedAt = now
        _queue.value = readableDatabase.rawQuery("SELECT id,title,status,detail,bytes,current_name,conflicts FROM operations WHERE status IN ('RUNNING','QUEUED','CONFLICT','INTERRUPTED') OR id IN (SELECT id FROM operations WHERE status NOT IN ('RUNNING','QUEUED','CONFLICT','INTERRUPTED') ORDER BY created DESC,rowid DESC LIMIT 100) ORDER BY CASE status WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'CONFLICT' THEN 2 WHEN 'INTERRUPTED' THEN 3 ELSE 4 END,created DESC,rowid DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val operationId = cursor.getString(0)
                    val events = readableDatabase.rawQuery("SELECT message,artifacts FROM operation_events WHERE operation_id=? ORDER BY sequence DESC LIMIT 100", arrayOf(operationId)).use { e ->
                        buildList { while (e.moveToNext()) add(e.getString(0) to refs(e.getString(1))) }.asReversed()
                    }
                    val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM operation_events WHERE operation_id=?", arrayOf(operationId)).use { c -> c.moveToFirst(); c.getLong(0) }
                    val messages = (if (count > 100) listOf("Showing the latest 100 of $count events. Share the full report to view all results.") else emptyList()) + events.map { it.first }.filter { it.isNotBlank() }
                    add(QueueItem(operationId, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5),
                        messages, events.flatMap { it.second }.distinct(), conflicts = conflictRows(cursor.getString(6))))
                }
            }
        }
        _procedureRuns.value = readableDatabase.rawQuery(
            "SELECT id,procedure_id,title,status,detail,created FROM operations WHERE procedure_id IS NOT NULL AND " +
                "(status IN ('RUNNING','QUEUED') OR rowid IN (SELECT MAX(rowid) FROM operations WHERE procedure_id IS NOT NULL GROUP BY procedure_id) " +
                "OR id IN (SELECT id FROM operations WHERE procedure_id IS NOT NULL ORDER BY created DESC,rowid DESC LIMIT 100)) " +
                "ORDER BY created DESC,rowid DESC", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ProcedureRun(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5)))
            }
        }
    }
    suspend fun enqueue(request: OperationRequest, title: String) = io {
        insertOperation(request, title)
        publish()
    }
    private fun insertOperation(request: OperationRequest, title: String) {
        writableDatabase.insertOrThrow("operations", null, ContentValues().apply {
            put("id", request.id); put("created", System.currentTimeMillis()); put("payload", encodeRequest(request))
            put("title", title); put("status", "QUEUED")
            put("procedure_id", request.procedureId)
        })
    }
    suspend fun enqueueProcedure(request: OperationRequest, title: String, scheduled: Boolean = false): Boolean = io {
        require(request.type == OperationType.PROCEDURE && request.procedureId != null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT 1 FROM operations WHERE id=?", arrayOf(request.id)).use { it.moveToFirst() }
            if (exists) return@io false
            val active = db.rawQuery("SELECT 1 FROM operations WHERE procedure_id=? AND status IN ('QUEUED','RUNNING') LIMIT 1",
                arrayOf(request.procedureId)).use { it.moveToFirst() }
            if (active) return@io false
            val previous = db.rawQuery("SELECT status FROM operations WHERE procedure_id=? ORDER BY created DESC,rowid DESC LIMIT 1",
                arrayOf(request.procedureId)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (scheduled && previous != null && previous != "SUCCEEDED") return@io false
            insertOperation(request, title)
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction(); publish() }
    }
    suspend fun request(id: String): OperationRequest? = io {
        readableDatabase.rawQuery("SELECT payload FROM operations WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) decodeRequest(it.getString(0)) else null }
    }
    suspend fun saveConflicts(id: String, conflicts: List<ItemOutcome>) = io {
        val rows = conflicts.map { ConflictRow(it.source, it.sourceDirectory, it.conflictName.orEmpty()) }
        writableDatabase.update("operations", ContentValues().apply { put("conflicts", json.encodeToString(rows)) }, "id=?", arrayOf(id))
        Unit
    }
    // A replacement name retries only the first conflict with ASK; otherwise all waiting conflicts use the selected policy.
    suspend fun resolveConflict(id: String, policy: ConflictPolicy, name: String? = null): Boolean = io {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val original = db.rawQuery("SELECT payload,title,conflicts FROM operations WHERE id=? AND status='CONFLICT' AND cancelled=0", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) null else Triple(decodeRequest(c.getString(0)), c.getString(1), conflictRows(c.getString(2)))
            }
            if (original == null) return@io false
            val rows = original.third
            if (original.first.type != OperationType.CREATE_FOLDER && rows.none { it.ref != null }) return@io false
            val retried = if (name == null) rows else rows.take(1)
            val remaining = if (name == null) emptyList() else rows.drop(1)
            val replacement = original.first.copy(
                id = UUID.randomUUID().toString(),
                sources = retried.mapNotNull { it.ref },
                name = name ?: original.first.name,
                conflictPolicy = if (name == null) policy else ConflictPolicy.ASK,
            )
            db.insertOrThrow("operations", null, ContentValues().apply {
                put("id", replacement.id); put("created", System.currentTimeMillis()); put("payload", encodeRequest(replacement))
                put("title", "Resolve: ${original.second}"); put("status", "QUEUED")
            })
            val detail = if (name == null) "Conflicted items queued with the selected policy"
                else "Retried as $name" + if (remaining.isEmpty()) "" else "; ${remaining.size} still waiting"
            if (remaining.isEmpty()) {
                db.execSQL("UPDATE operations SET status='RESOLVED',conflicts='[]',detail=? WHERE id=?", arrayOf(detail, id))
            } else {
                db.execSQL("UPDATE operations SET conflicts=?,detail=? WHERE id=?",
                    arrayOf(json.encodeToString(remaining), detail, id))
            }
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction(); publish() }
    }
    suspend fun hasQueued(): Boolean = io {
        readableDatabase.rawQuery("SELECT 1 FROM operations WHERE status='QUEUED' LIMIT 1", null).use { it.moveToFirst() }
    }
    /** Call only while holding the queue's execution mutex: every RUNNING row is then stale. */
    suspend fun recoverInterrupted() = io {
        val interrupted = readableDatabase.rawQuery("SELECT payload FROM operations WHERE status='RUNNING'", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decodeRequest(cursor.getString(0))) }
        }
        writableDatabase.execSQL("UPDATE operations SET status='INTERRUPTED',detail='Execution was interrupted. Inspect the recorded results and staging objects before starting a new operation. Sources may remain alongside completed copies.' WHERE status='RUNNING'")
        publish()
        interrupted
    }
    suspend fun claimNext(): OperationRequest? = io {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val next = db.rawQuery("SELECT payload FROM operations WHERE status='QUEUED' AND cancelled=0 ORDER BY created, rowid LIMIT 1", null).use {
                if (it.moveToFirst()) decodeRequest(it.getString(0)) else null
            }
            if (next != null) db.execSQL("UPDATE operations SET status='RUNNING' WHERE id=?", arrayOf(next.id))
            db.setTransactionSuccessful()
            publish()
            next
        } finally { db.endTransaction() }
    }
    suspend fun cancel(id: String) = io {
        writableDatabase.execSQL("UPDATE operations SET cancelled=1,status=CASE WHEN status IN ('QUEUED','CONFLICT') THEN 'CANCELLED' ELSE status END WHERE id=?", arrayOf(id))
        publish()
    }
    suspend fun isCancelled(id: String): Boolean = io {
        readableDatabase.rawQuery("SELECT cancelled FROM operations WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) != 0 }
    }
    suspend fun deferQueued(detail: String) = io {
        writableDatabase.update("operations", ContentValues().apply { put("detail", detail) }, "status='QUEUED'", null)
        publish()
    }
    suspend fun progress(id: String, bytes: Long, name: String, detail: String) = io {
        writableDatabase.update("operations", ContentValues().apply { put("bytes", bytes); put("current_name", name); put("detail", detail) }, "id=?", arrayOf(id))
        publish(force = false)
    }
    suspend fun journal(id: String, detail: String, artifacts: List<NodeRef>, result: String? = null, source: NodeRef? = null, destination: NodeRef? = null) = io {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("operation_events", null, ContentValues().apply {
                put("operation_id", id); put("message", result ?: detail)
                put("artifacts", json.encodeToString(artifacts.distinct()))
                put("source", source?.let { ref(it) }); put("destination", destination?.let { ref(it) })
            })
            db.update("operations", ContentValues().apply { put("detail", detail) }, "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish(force = false)
    }
    suspend fun finish(id: String, status: String, detail: String) = io {
        writableDatabase.execSQL("UPDATE operations SET status=CASE WHEN cancelled=1 AND ?='CONFLICT' THEN 'CANCELLED' ELSE ? END,detail=? WHERE id=?", arrayOf(status, status, detail, id))
        publish()
    }
    suspend fun exportReport(id: String, file: File) = io {
        file.bufferedWriter().use { writer ->
            readableDatabase.rawQuery("SELECT title,status,detail FROM operations WHERE id=?", arrayOf(id)).use { c ->
                check(c.moveToFirst()) { "Operation no longer exists" }
                writer.appendLine("Luna Explorer — operation report").appendLine(c.getString(0))
                    .appendLine("Status: ${c.getString(1)}").appendLine(c.getString(2)).appendLine()
            }
            readableDatabase.rawQuery("SELECT message,artifacts,source,destination FROM operation_events WHERE operation_id=? ORDER BY sequence", arrayOf(id)).use { c ->
                while (c.moveToNext()) {
                    writer.appendLine(c.getString(0))
                    refs(c.getString(1)).forEach { writer.appendLine("  ${it.provider}: ${it.key}") }
                    if (!c.isNull(2)) writer.appendLine("  Source: ${c.getString(2)}")
                    if (!c.isNull(3)) writer.appendLine("  Destination: ${c.getString(3)}")
                }
            }
        }
    }
    /** Rows stay STAGED, and out of [trash], until [reconcileTrash] activates them. */
    suspend fun stageTrash(operationId: String, records: List<TrashedItem>) = io {
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { record ->
                db.insertWithOnConflict("trash", null, ContentValues().apply {
                    put("id", record.id); put("operation_id", operationId); put("state", "STAGED")
                    put("name", record.name); put("ref", ref(record.ref))
                    put("source_ref", ref(record.ref))
                    put("original_parent", ref(record.originalParent))
                    put("original_name", record.originalName); put("original_path", record.originalPath)
                    put("directory", if (record.directory) 1 else 0); put("size", record.size)
                    put("deleted_at", record.deletedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publishTrash()
    }

    suspend fun reconcileTrash(operationId: String, outcomes: List<ItemOutcome>) = io {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val staged = db.rawQuery("SELECT id, source_ref FROM trash WHERE operation_id=? AND state='STAGED'",
                arrayOf(operationId)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            if (staged.isNotEmpty()) {
                val landed = outcomes.filter { it.status == ItemStatus.SUCCESS && it.source != null && it.destination != null }
                    .associate { ref(it.source!!) to it.destination!! }
                staged.forEach { (id, sourceRef) ->
                    val destination = landed[sourceRef]
                    if (destination == null) {
                        db.delete("trash", "id=?", arrayOf(id))
                    } else {
                        db.update("trash", ContentValues().apply {
                            put("state", "ACTIVE"); put("ref", ref(destination))
                        }, "id=?", arrayOf(id))
                    }
                }
            }
            outcomes.filter { it.status == ItemStatus.SUCCESS }.mapNotNull { it.source }.forEach { source ->
                db.delete("trash", "state='ACTIVE' AND ref=?", arrayOf(ref(source)))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publishTrash()
    }

    suspend fun refreshTrash() = io { publishTrash() }

    suspend fun forgetTrash(ids: List<String>) = io {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("trash", "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publishTrash()
    }

    private fun publishTrash() {
        _trash.value = readableDatabase.rawQuery(
            "SELECT id,name,ref,original_parent,original_name,original_path,directory,size,deleted_at " +
                "FROM trash WHERE state='ACTIVE' ORDER BY deleted_at DESC", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(TrashedItem(
                        id = cursor.getString(0), name = cursor.getString(1),
                        ref = nodeRef(cursor.getString(2)),
                        originalParent = nodeRef(cursor.getString(3)),
                        originalName = cursor.getString(4),
                        originalPath = if (cursor.isNull(5)) null else cursor.getString(5),
                        directory = cursor.getInt(6) != 0,
                        size = if (cursor.isNull(7)) null else cursor.getLong(7),
                        deletedAt = cursor.getLong(8),
                    ))
                }
            }
        }
    }

    private fun encodeRequest(r: OperationRequest): String = json.encodeToString(r)
    private fun decodeRequest(raw: String): OperationRequest = json.decodeFromString(raw)

    private companion object {
        /** Keys are `ext:<extension>` or `mime:<type>/<subtype>`. */
        const val OPEN_DEFAULTS_TABLE = "CREATE TABLE open_defaults (" +
            "\"key\" TEXT PRIMARY KEY, target TEXT NOT NULL, mime TEXT NOT NULL DEFAULT '', label TEXT NOT NULL DEFAULT '')"

        const val TRASH_TABLE = "CREATE TABLE trash (" +
            "id TEXT PRIMARY KEY, operation_id TEXT NOT NULL, state TEXT NOT NULL, name TEXT NOT NULL, " +
            "ref TEXT NOT NULL, source_ref TEXT NOT NULL, original_parent TEXT NOT NULL, " +
            "original_name TEXT NOT NULL, original_path TEXT, directory INTEGER NOT NULL, " +
            "size INTEGER, deleted_at INTEGER NOT NULL)"
    }
}

data class OpenDefault(val key: String, val target: String, val mime: String, val label: String)
