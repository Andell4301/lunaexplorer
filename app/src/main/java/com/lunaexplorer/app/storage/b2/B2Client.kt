package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.app.storage.RemoteFailure
import com.lunaexplorer.app.storage.RemoteReader
import com.lunaexplorer.core.StorageError
import java.io.Closeable
import java.io.File
import java.io.InputStream

// HTTP and JSON types stay behind this interface; callers receive B2Failure.
interface B2Session : Closeable {
    val summary: B2Auth

    fun buckets(): List<B2BucketInfo>
    /** NOT_FOUND when the key cannot reach that bucket. */
    fun bucket(name: String): B2Bucket
    /** False once the authorization is known to be spent; the caller authorizes again. */
    fun alive(): Boolean
}

/** What b2_authorize_account said. A restricted key reports its limits here. */
data class B2Auth(
    val accountId: String,
    /** Null for a key that can reach every bucket. */
    val buckets: List<B2BucketInfo>? = null,
    /** Object names the key may touch all start with this. */
    val namePrefix: String = "",
    /** Empty means unknown, which is treated as everything. */
    val capabilities: Set<String> = emptySet(),
    val recommendedPartSize: Long = 100_000_000,
    val minimumPartSize: Long = 5_000_000,
) {
    fun can(capability: String) = capabilities.isEmpty() || capability in capabilities
}

data class B2BucketInfo(val id: String, val name: String)

// B2 keys are whole object names; delimiters produce common prefixes separately from objects.
interface B2Bucket {
    /** One page of current versions. [after] is the previous page's [B2Page.next]. */
    fun list(prefix: String, delimiter: String?, after: String?, limit: Int): B2Page
    /** The current version of exactly [key], or null. */
    fun stat(key: String): B2Item?
    /** Every stored version of exactly [key], newest first, hide markers included. */
    fun versions(key: String): List<B2Version>
    /** The whole object from [from] on, as one response body. */
    fun open(item: B2Item, from: Long = 0): InputStream
    fun openReader(item: B2Item): B2Reader
    /** Uploads the whole of [source] as one object of at most 5 GB. */
    fun upload(key: String, source: File, modified: Long?, contentType: String): B2Item
    /** Returns the large file's id, which the part calls and [finishLarge] take. */
    fun startLarge(key: String, modified: Long?, contentType: String): String
    /** Uploads the whole of [source] as part [number], counted from 1. Returns the part's SHA-1. */
    fun uploadPart(largeFileId: String, number: Int, source: File): String
    fun finishLarge(largeFileId: String, partSha1s: List<String>): B2Item
    /** An unfinished large file is stored, and billed, until cancelled. */
    fun cancelLarge(largeFileId: String)
    /** Server-side copy within the account; no bytes reach the device. */
    fun copy(from: B2Item, toKey: String): B2Item
    fun delete(version: B2Version)
    /** Writes a hide marker: [key] stops being listed and its versions stay stored. */
    fun hide(key: String)
    fun alive(): Boolean
}

/** [folders] are full prefixes, each ending in the delimiter. */
data class B2Page(val items: List<B2Item>, val folders: List<String>, val next: String?)

data class B2Item(
    val key: String,
    val fileId: String,
    val size: Long,
    val modified: Long,
    val contentType: String = "application/octet-stream",
)

/** [hidden] marks a hide marker rather than stored bytes. */
data class B2Version(val key: String, val fileId: String, val hidden: Boolean = false)

/** HTTP has no open handle to keep, so a reader holds the object's address, not a socket. */
interface B2Reader : RemoteReader

fun interface B2Connector {
    /** Authorizes the account without touching a bucket. Throws [B2Failure] on failure. */
    fun connect(account: B2Account): B2Session
}

class B2Failure(reason: StorageError, message: String, cause: Throwable? = null) : RemoteFailure(reason, message, cause)
