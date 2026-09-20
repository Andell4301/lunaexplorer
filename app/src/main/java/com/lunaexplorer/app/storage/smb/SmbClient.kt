package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.app.storage.RemoteFailure
import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.core.StorageError
import java.io.Closeable
import java.io.OutputStream

// A failed share listing leaves the session usable for opening a known share.
interface SmbServer : Closeable {
    val summary: SmbSessionSummary

    /** Includes printer and pipe shares. */
    fun shares(): List<SmbShareInfo>
    // Only DISCONNECTED requires a new session; a missing or non-disk share does not.
    fun open(share: String): SmbShare
    fun alive(): Boolean
}

/** [special] is the server's STYPE_SPECIAL flag, set on administrative shares. */
data class SmbShareInfo(val name: String, val disk: Boolean, val special: Boolean)

/** Paths are relative to the share and use forward slashes; the empty path is the share root. */
interface SmbShare : Closeable {
    fun list(path: String): List<SmbItem>
    /** Null if the item does not exist. */
    fun stat(path: String): SmbItem?
    fun mkdir(path: String)
    /** Fails if the name exists. */
    fun createFile(path: String)
    fun rename(path: String, to: String, replace: Boolean)
    fun deleteFile(path: String)
    /** Never recurses. */
    fun deleteFolder(path: String)
    fun openReader(path: String): SmbReader
    /** Overwrites an existing file (see [createFile]) from offset zero. */
    fun write(path: String): OutputStream
    fun setModified(path: String, epochMillis: Long)
    fun freeBytes(): Long?
    /** Tree state only; the session can outlive a closed tree. */
    fun alive(): Boolean
}

/** Holds one open file handle, so a read does not cost an open/close round trip. */
interface SmbReader : RemoteReader

data class SmbItem(
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val readOnly: Boolean,
    val hidden: Boolean,
)

data class SmbSessionSummary(val dialect: String, val encrypted: Boolean, val signed: Boolean)

fun interface SmbConnector {
    /** Connects and authenticates without opening a share. Throws [SmbFailure] on failure. */
    fun connect(account: SmbAccount): SmbServer
}

class SmbFailure(reason: StorageError, message: String, cause: Throwable? = null) : RemoteFailure(reason, message, cause)
