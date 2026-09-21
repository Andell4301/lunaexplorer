package com.lunaexplorer.app.storage

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteStatement
import android.os.CancellationSignal
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class SqlObject(
    val name: String,
    val kind: String,
    val sql: String?,
    val rowCount: Long? = null,
    val tableName: String = name,
)

data class SqlColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKey: Int,
    val hidden: Int = 0,
)

enum class SqlFilterOperator(val label: String) {
    EQUAL("="), NOT_EQUAL("≠"), CONTAINS("Contains"), GREATER(">"), LESS("<"),
    IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL"),
}

data class SqlFilter(val column: String, val operator: SqlFilterOperator, val value: String = "")
data class SqlSort(val column: String, val descending: Boolean = false)
data class SqlInput(val value: String?, val type: Int)

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

    suspend fun open(path: String, forWriting: Boolean = false): Handle {
        var opened: Handle? = null
        try {
            return withContext(Dispatchers.IO) {
                openFile(path, forWriting).also { opened = it }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
            throw error
        }
    }

    private fun openFile(path: String, forWriting: Boolean): Handle {
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
        try {
            val writable = writing && !database.isReadOnly
            if (writable) database.setForeignKeyConstraintsEnabled(true)
            if (writing && database.isReadOnly) notes += "Could not open for writing; showing read-only."
            return Handle(database, path, writable, notes)
        } catch (error: Throwable) {
            runCatching { database.close() }
            throw error
        }
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
        query(handle,
            "SELECT name, type, sql, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
        ) { cursor ->
            val found = mutableListOf<SqlObject>()
            while (cursor.moveToNext()) {
                found += SqlObject(cursor.getString(0), cursor.getString(1),
                    if (cursor.isNull(2)) null else cursor.getString(2), tableName = cursor.getString(3))
            }
            found
        }
    }

    suspend fun columns(handle: Handle, table: String): List<SqlColumn> = withContext(Dispatchers.IO) {
        readColumns(handle, table, "table_xinfo").ifEmpty { readColumns(handle, table, "table_info") }
    }

    private suspend fun readColumns(handle: Handle, table: String, pragma: String): List<SqlColumn> =
        query(handle, "PRAGMA $pragma(${quote(table)})") { cursor ->
            val found = mutableListOf<SqlColumn>()
            val hidden = cursor.getColumnIndex("hidden")
            while (cursor.moveToNext()) {
                found += SqlColumn(cursor.getString(1), cursor.getString(2).orEmpty(), cursor.getInt(3) != 0,
                    if (cursor.isNull(4)) null else cursor.getString(4), cursor.getInt(5),
                    if (hidden >= 0) cursor.getInt(hidden) else 0)
            }
            found
        }

    suspend fun count(handle: Handle, table: String, filter: SqlFilter? = null): Long = withContext(Dispatchers.IO) {
        val (where, arguments) = where(filter)
        query(handle, "SELECT COUNT(*) FROM ${quote(table)}$where", arguments) { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private suspend fun identityColumn(handle: Handle, table: String): String? {
        val isTable = query(handle, "SELECT type FROM sqlite_master WHERE name = ?", arrayOf(table)) {
            it.moveToFirst() && it.getString(0) == "table"
        }
        if (!isTable) return null
        val realColumns = columns(handle, table).map { it.name.lowercase(Locale.ROOT) }.toSet()
        for (candidate in listOf("_rowid_", "rowid", "oid")) {
            if (candidate in realColumns) continue
            try {
                query(handle, "SELECT $candidate FROM ${quote(table)} LIMIT 0") { it.columnNames }
                return candidate
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
            }
        }
        return null
    }

    suspend fun page(
        handle: Handle,
        table: String,
        offset: Int,
        limit: Int = 100,
        filter: SqlFilter? = null,
        sort: SqlSort? = null,
    ): SqlPage = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit in 1 until Int.MAX_VALUE)
        val identity = identityColumn(handle, table)
        val projection = if (identity != null) "$identity AS _luna_id, *" else "*"
        val (where, arguments) = where(filter)
        val order = sort?.let { " ORDER BY ${quote(it.column)} ${if (it.descending) "DESC" else "ASC"}" }.orEmpty()
        val sql = "SELECT $projection FROM ${quote(table)}$where$order LIMIT ${limit + 1} OFFSET $offset"
        query(handle, sql, arguments) { cursor ->
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

    private fun where(filter: SqlFilter?): Pair<String, Array<String>?> {
        if (filter == null) return "" to null
        val column = quote(filter.column)
        return when (filter.operator) {
            SqlFilterOperator.IS_NULL -> " WHERE $column IS NULL" to null
            SqlFilterOperator.IS_NOT_NULL -> " WHERE $column IS NOT NULL" to null
            SqlFilterOperator.CONTAINS -> {
                val literal = filter.value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                " WHERE $column LIKE ? ESCAPE '\\'" to arrayOf("%$literal%")
            }
            else -> {
                val operator = when (filter.operator) {
                    SqlFilterOperator.EQUAL -> "="
                    SqlFilterOperator.NOT_EQUAL -> "<>"
                    SqlFilterOperator.GREATER -> ">"
                    SqlFilterOperator.LESS -> "<"
                    else -> error("Unreachable filter")
                }
                " WHERE $column $operator ?" to arrayOf(filter.value)
            }
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
        result {
            val trimmed = statementSql(sql)
            if (trimmed.isEmpty()) throw SQLiteException("Nothing to run")
            val verb = statementVerb(trimmed)
            // SQLite transactions belong to a thread; separate calls can use different IO workers.
            if (verb in setOf("begin", "commit", "end", "rollback", "savepoint", "release")) {
                throw SQLiteException("Transaction control is not supported here")
            }
            if (verb in setOf("select", "pragma", "explain", "with", "values")) {
                val querySql = if (verb in setOf("select", "with", "values")) "SELECT * FROM (\n$trimmed\n) LIMIT 501" else trimmed
                query(handle, querySql) { cursor ->
                    val rows = mutableListOf<SqlRow>()
                    var more = false
                    while (cursor.moveToNext()) {
                        if (rows.size == 500) { more = true; break }
                        rows += SqlRow(null, (0 until cursor.columnCount).map { valueAt(cursor, it) })
                    }
                    SqlPage(cursor.columnNames.toList(), rows, more)
                }
            } else {
                requireWritable(handle)
                currentCoroutineContext().ensureActive()
                val changed = handle.database.compileStatement(trimmed).use { it.executeUpdateDelete() }
                SqlPage(listOf("result"), listOf(SqlRow(null,
                    listOf(SqlValue("$changed row(s) changed", Cursor.FIELD_TYPE_STRING)))), more = false)
            }
        }
    }

    private fun statementSql(sql: String): String {
        var start = -1
        var end = 0
        var position = 0
        while (position < sql.length) {
            when {
                sql[position].isWhitespace() || sql[position] == '\uFEFF' -> position++
                sql.startsWith("--", position) -> {
                    position = sql.indexOf('\n', position + 2).takeIf { it >= 0 } ?: sql.length
                }
                sql.startsWith("/*", position) -> {
                    position = sql.indexOf("*/", position + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                }
                else -> {
                    val token = sql[position++]
                    if (start < 0 && token != ';') start = position - 1
                    if (token in "'\"`[") {
                        val closing = if (token == '[') ']' else token
                        while (position < sql.length) {
                            if (sql[position++] != closing) continue
                            if (token != '[' && position < sql.length && sql[position] == closing) position++ else break
                        }
                    }
                    if (token != ';') end = position
                }
            }
        }
        return if (start < 0 || end <= start) "" else sql.substring(start, end)
    }

    private fun statementVerb(sql: String): String {
        val first = sql.takeWhile { it.isLetter() }.lowercase(Locale.ROOT)
        if (first != "with") return first
        var position = first.length
        var depth = 0
        var afterBody = false
        while (position < sql.length) {
            when {
                sql.startsWith("--", position) ->
                    position = sql.indexOf('\n', position + 2).takeIf { it >= 0 } ?: sql.length
                sql.startsWith("/*", position) ->
                    position = sql.indexOf("*/", position + 2).takeIf { it >= 0 }?.plus(2) ?: sql.length
                sql[position] in "'\"`[" -> {
                    val opening = sql[position++]
                    val closing = if (opening == '[') ']' else opening
                    while (position < sql.length) {
                        if (sql[position++] != closing) continue
                        if (opening != '[' && position < sql.length && sql[position] == closing) position++ else break
                    }
                }
                sql[position] == '(' -> { depth++; position++ }
                sql[position] == ')' -> { depth--; afterBody = depth == 0; position++ }
                sql[position] == ',' && depth == 0 -> { afterBody = false; position++ }
                sql[position].isLetter() || sql[position] == '_' -> {
                    val start = position++
                    while (position < sql.length && (sql[position].isLetterOrDigit() || sql[position] in "_$")) position++
                    if (depth == 0 && afterBody) {
                        val word = sql.substring(start, position).lowercase(Locale.ROOT)
                        if (word in setOf("select", "insert", "update", "delete", "replace", "values")) return word
                        if (word == "as") afterBody = false
                    }
                }
                else -> position++
            }
        }
        return first
    }

    suspend fun updateCell(
        handle: Handle,
        table: String,
        column: String,
        identity: String,
        value: String?,
        type: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        result {
            requireWritable(handle)
            val identityAlias = identityColumn(handle, table)
                ?: throw SQLiteException("This table has no rowid, so a cell cannot be addressed")
            changeOne(handle,
                "UPDATE ${quote(table)} SET ${quote(column)} = ? WHERE $identityAlias = ?",
                listOf(SqlInput(value, type), SqlInput(identity, Cursor.FIELD_TYPE_INTEGER)),
            )
        }
    }

    suspend fun insertRow(handle: Handle, table: String, values: Map<String, SqlInput>): Result<Unit> =
        withContext(Dispatchers.IO) {
            result {
                val entries = values.entries.toList()
                val sql = if (entries.isEmpty()) "INSERT INTO ${quote(table)} DEFAULT VALUES" else {
                    val names = entries.joinToString(", ") { quote(it.key) }
                    val placeholders = entries.joinToString(", ") { "?" }
                    "INSERT INTO ${quote(table)} ($names) VALUES ($placeholders)"
                }
                changeOne(handle, sql, entries.map { it.value })
            }
        }

    suspend fun deleteRow(handle: Handle, table: String, identity: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            result {
                requireWritable(handle)
                val identityAlias = identityColumn(handle, table)
                    ?: throw SQLiteException("This table has no rowid, so a row cannot be addressed")
                changeOne(handle, "DELETE FROM ${quote(table)} WHERE $identityAlias = ?",
                    listOf(SqlInput(identity, Cursor.FIELD_TYPE_INTEGER)))
            }
        }

    private suspend fun changeOne(handle: Handle, sql: String, values: List<SqlInput>) {
        requireWritable(handle)
        currentCoroutineContext().ensureActive()
        // The viewer can close the handle while this transaction is running.
        handle.database.acquireReference()
        try {
            handle.database.compileStatement(sql).use { statement ->
                values.forEachIndexed { index, input -> bind(statement, index + 1, input) }
                handle.database.beginTransaction()
                try {
                    val changed = statement.executeUpdateDelete()
                    if (changed != 1) throw SQLiteException("That would have changed $changed rows, not one")
                    currentCoroutineContext().ensureActive()
                    handle.database.setTransactionSuccessful()
                } finally {
                    handle.database.endTransaction()
                }
            }
        } finally {
            handle.database.releaseReference()
        }
    }

    private fun bind(statement: SQLiteStatement, index: Int, input: SqlInput) {
        val value = input.value
        when {
            value == null || input.type == Cursor.FIELD_TYPE_NULL -> statement.bindNull(index)
            input.type == Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(index,
                value.toLongOrNull() ?: throw SQLiteException("Invalid integer"))
            input.type == Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(index,
                value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw SQLiteException("Invalid real number"))
            input.type == Cursor.FIELD_TYPE_BLOB -> {
                if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) {
                    throw SQLiteException("Invalid hexadecimal BLOB")
                }
                val bytes = ByteArray(value.length / 2) { position ->
                    ((value[position * 2].digitToInt(16) shl 4) or value[position * 2 + 1].digitToInt(16)).toByte()
                }
                statement.bindBlob(index, bytes)
            }
            else -> statement.bindString(index, value)
        }
    }

    private fun requireWritable(handle: Handle) {
        if (!handle.writable) throw SQLiteException("This database is open read-only")
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    private suspend fun <T> query(
        handle: Handle,
        sql: String,
        arguments: Array<String>? = null,
        read: (Cursor) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            continuation.context.ensureActive()
            val value = handle.database.rawQuery(sql, arguments, signal).use(read)
            continuation.resume(value)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""

    private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T =
        try { block(this) } finally { runCatching { close() } }
}
