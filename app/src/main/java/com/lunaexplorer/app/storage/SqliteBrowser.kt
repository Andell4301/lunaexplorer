package com.lunaexplorer.app.storage

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SqlObject(val name: String, val kind: String, val sql: String?, val rowCount: Long?)

data class SqlPage(
    val columns: List<String>,
    val rows: List<SqlRow>,
    val more: Boolean,
)

data class SqlRow(val identity: String?, val values: List<SqlValue>)

data class SqlValue(val text: String, val type: Int) {
    val isNull: Boolean get() = type == Cursor.FIELD_TYPE_NULL
    val isBlob: Boolean get() = type == Cursor.FIELD_TYPE_BLOB
}

class SqliteBrowser(context: Context) {
    private val context = context.applicationContext

    // Android's default handler deletes a database it considers corrupt.
    private val keepTheFile = DatabaseErrorHandler { }

    class Handle internal constructor(
        internal val database: SQLiteDatabase,
        val path: String,
        val writable: Boolean,
        val notes: List<String>,
    ) : AutoCloseable {
        override fun close() { runCatching { database.close() } }
    }

    suspend fun open(path: String, forWriting: Boolean = false): Handle = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) throw SQLiteException("$path is not a file")
        if (!looksLikeSqlite(file)) {
            throw SQLiteException("This is not a SQLite database — it has no SQLite header. " +
                "An encrypted database looks like this too.")
        }

        val notes = mutableListOf<String>()
        val ownData = runCatching {
            file.canonicalPath.startsWith(context.dataDir.canonicalPath)
        }.getOrDefault(false)
        // Never write to Luna's own databases; the running app owns queue and recycle-bin state.
        val writing = forWriting && file.canWrite() && !ownData
        if (forWriting && ownData) {
            notes += "This database belongs to Luna itself, so it is open read-only."
        }

        val wal = File("$path-wal")
        if (wal.isFile && wal.length() > 0 && !writing) {
            notes += "There is an unmerged write-ahead log, so the newest changes are not shown."
        }
        if (writing) {
            notes += "Open for editing. Android may rewrite this file's journal mode."
        }

        val database = openWith(path, writing) ?: openWith(path, false)
            ?: throw SQLiteException("This database could not be opened")
        if (writing && database.isReadOnly) notes += "Could not open for writing; showing read-only."

        Handle(database, path, writing && !database.isReadOnly, notes)
    }

    private fun openWith(path: String, writing: Boolean): SQLiteDatabase? = runCatching {
        if (writing) {
            // Registering localized collators on a writable connection can REINDEX the file, so
            // disable them unless the schema uses one.
            val flags = SQLiteDatabase.OPEN_READWRITE or
                if (usesLocalizedCollation(path)) 0 else SQLiteDatabase.NO_LOCALIZED_COLLATORS
            SQLiteDatabase.openDatabase(path, null, flags, keepTheFile)
        } else {
            // Read-only collator registration does not modify the database.
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, keepTheFile)
        }
    }.getOrNull()

    private fun usesLocalizedCollation(path: String): Boolean = runCatching {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, keepTheFile).use { db ->
            db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val sql = cursor.getString(0).uppercase()
                    if (sql.contains("LOCALIZED") || sql.contains("PHONEBOOK")) return true
                }
            }
        }
        false
    }.getOrDefault(false)

    /** The 16-byte SQLite header ends with NUL after "SQLite format 3". */
    private fun looksLikeSqlite(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(16)
            if (stream.read(header) != 16) return false
            header.copyOf(15).decodeToString() == "SQLite format 3" && header[15] == 0.toByte()
        }
    }.getOrDefault(false)

    suspend fun objects(handle: Handle): List<SqlObject> = withContext(Dispatchers.IO) {
        val found = mutableListOf<SqlObject>()
        handle.database.rawQuery(
            "SELECT name, type, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val kind = cursor.getString(1)
                found += SqlObject(name, kind, if (cursor.isNull(2)) null else cursor.getString(2),
                    if (kind == "table") countOf(handle, name) else null)
            }
        }
        found
    }

    private fun countOf(handle: Handle, table: String): Long? = runCatching {
        handle.database.rawQuery("SELECT COUNT(*) FROM ${quote(table)}", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }.getOrNull()

    /** A rowid alias not shadowed by a real column; null if WITHOUT ROWID or all are shadowed. */
    private fun identityColumn(handle: Handle, table: String): String? {
        val realColumns = runCatching {
            handle.database.rawQuery("SELECT * FROM ${quote(table)} LIMIT 0", null)
                .use { it.columnNames.map(String::lowercase).toSet() }
        }.getOrDefault(emptySet())
        return listOf("_rowid_", "rowid", "oid").firstOrNull { candidate ->
            candidate !in realColumns && runCatching {
                handle.database.rawQuery("SELECT $candidate FROM ${quote(table)} LIMIT 0", null).use { true }
            }.getOrDefault(false)
        }
    }

    suspend fun page(handle: Handle, table: String, offset: Int, limit: Int = 100): SqlPage =
        withContext(Dispatchers.IO) {
            val identity = identityColumn(handle, table)
            val projection = if (identity != null) "$identity AS _luna_id, *" else "*"
            val sql = "SELECT $projection FROM ${quote(table)} LIMIT ${limit + 1} OFFSET $offset"
            handle.database.rawQuery(sql, null).use { cursor ->
                val names = cursor.columnNames.toList()
                val visible = if (identity != null) names.drop(1) else names
                val rows = mutableListOf<SqlRow>()
                var more = false
                while (cursor.moveToNext()) {
                    if (rows.size == limit) { more = true; break }
                    val first = if (identity != null) 1 else 0
                    rows += SqlRow(
                        identity = if (identity != null) cursor.getString(0) else null,
                        values = (first until cursor.columnCount).map { valueAt(cursor, it) },
                    )
                }
                SqlPage(visible, rows, more)
            }
        }

    private fun valueAt(cursor: Cursor, column: Int): SqlValue = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> SqlValue("NULL", Cursor.FIELD_TYPE_NULL)
        Cursor.FIELD_TYPE_INTEGER -> SqlValue(cursor.getLong(column).toString(), Cursor.FIELD_TYPE_INTEGER)
        Cursor.FIELD_TYPE_FLOAT -> SqlValue(cursor.getDouble(column).toString(), Cursor.FIELD_TYPE_FLOAT)
        Cursor.FIELD_TYPE_BLOB -> SqlValue(
            "BLOB (${runCatching { cursor.getBlob(column).size }.getOrDefault(0)} bytes)",
            Cursor.FIELD_TYPE_BLOB,
        )
        else -> SqlValue(cursor.getString(column).orEmpty(), Cursor.FIELD_TYPE_STRING)
    }

    suspend fun execute(handle: Handle, sql: String): Result<SqlPage> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = sql.trim().trimEnd(';')
            if (trimmed.isEmpty()) throw SQLiteException("Nothing to run")
            val verb = trimmed.substringBefore(' ').lowercase()
            if (verb in setOf("select", "pragma", "explain", "with")) {
                handle.database.rawQuery(trimmed, null).use { cursor ->
                    val rows = mutableListOf<SqlRow>()
                    while (cursor.moveToNext() && rows.size < 500) {
                        rows += SqlRow(null, (0 until cursor.columnCount).map { valueAt(cursor, it) })
                    }
                    SqlPage(cursor.columnNames.toList(), rows, more = false)
                }
            } else {
                if (!handle.writable) throw SQLiteException("This database is open read-only")
                val changed = handle.database.compileStatement(trimmed).use { it.executeUpdateDelete() }
                SqlPage(listOf("result"), listOf(SqlRow(null,
                    listOf(SqlValue("$changed row(s) changed", Cursor.FIELD_TYPE_STRING)))), more = false)
            }
        }
    }

    suspend fun updateCell(
        handle: Handle,
        table: String,
        column: String,
        identity: String,
        value: String?,
        type: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!handle.writable) throw SQLiteException("This database is open read-only")
            val identityAlias = identityColumn(handle, table)
                ?: throw SQLiteException("This table has no rowid, so a cell cannot be addressed")
            val statement = handle.database.compileStatement(
                "UPDATE ${quote(table)} SET ${quote(column)} = ? WHERE $identityAlias = ?",
            )
            statement.use {
                when {
                    value == null -> it.bindNull(1)
                    type == Cursor.FIELD_TYPE_INTEGER -> value.toLongOrNull()
                        ?.let { number -> it.bindLong(1, number) } ?: it.bindString(1, value)
                    type == Cursor.FIELD_TYPE_FLOAT -> value.toDoubleOrNull()
                        ?.let { number -> it.bindDouble(1, number) } ?: it.bindString(1, value)
                    else -> it.bindString(1, value)
                }
                identity.toLongOrNull()?.let { number -> it.bindLong(2, number) } ?: it.bindString(2, identity)
                handle.database.beginTransaction()
                try {
                    val changed = it.executeUpdateDelete()
                    if (changed != 1) throw SQLiteException("That would have changed $changed rows, not one")
                    handle.database.setTransactionSuccessful()
                } finally {
                    handle.database.endTransaction()
                }
            }
        }
    }

    private fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

    private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T =
        try { block(this) } finally { runCatching { close() } }
}
