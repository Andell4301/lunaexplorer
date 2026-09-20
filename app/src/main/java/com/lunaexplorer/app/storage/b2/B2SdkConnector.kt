package com.lunaexplorer.app.storage.b2

import com.backblaze.b2.client.B2CancellationToken
import com.backblaze.b2.client.B2ClientConfig
import com.backblaze.b2.client.B2CopyingPartStorer
import com.backblaze.b2.client.B2LargeFileStorer
import com.backblaze.b2.client.B2PartStorer
import com.backblaze.b2.client.B2RetryPolicy
import com.backblaze.b2.client.B2StorageClientImpl
import com.backblaze.b2.client.B2StorageClientWebifier
import com.backblaze.b2.client.B2StorageClientWebifierImpl
import com.backblaze.b2.client.B2UploadingPartStorer
import com.backblaze.b2.client.contentSources.B2ContentSource
import com.backblaze.b2.client.contentSources.B2FileContentSource
import com.backblaze.b2.client.contentSources.B2Headers
import com.backblaze.b2.client.contentSources.B2HeadersImpl
import com.backblaze.b2.client.exceptions.B2BadRequestException
import com.backblaze.b2.client.exceptions.B2ConnectFailedException
import com.backblaze.b2.client.exceptions.B2Exception
import com.backblaze.b2.client.exceptions.B2ForbiddenException
import com.backblaze.b2.client.exceptions.B2InternalErrorException
import com.backblaze.b2.client.exceptions.B2LocalException
import com.backblaze.b2.client.exceptions.B2NetworkBaseException
import com.backblaze.b2.client.exceptions.B2NetworkTimeoutException
import com.backblaze.b2.client.exceptions.B2NotFoundException
import com.backblaze.b2.client.exceptions.B2RequestTimeoutException
import com.backblaze.b2.client.exceptions.B2ServiceUnavailableException
import com.backblaze.b2.client.exceptions.B2TooManyRequestsException
import com.backblaze.b2.client.exceptions.B2UnauthorizedException
import com.backblaze.b2.client.structures.B2AccountAuthorization
import com.backblaze.b2.client.structures.B2CopyFileRequest
import com.backblaze.b2.client.structures.B2DownloadByIdRequest
import com.backblaze.b2.client.structures.B2FileVersion
import com.backblaze.b2.client.structures.B2FinishLargeFileRequest
import com.backblaze.b2.client.structures.B2GetFileInfoRequest
import com.backblaze.b2.client.structures.B2ListBucketsRequest
import com.backblaze.b2.client.structures.B2ListFileNamesRequest
import com.backblaze.b2.client.structures.B2ListFileVersionsRequest
import com.backblaze.b2.client.structures.B2Part
import com.backblaze.b2.client.structures.B2StartLargeFileRequest
import com.backblaze.b2.client.structures.B2StoreLargeFileRequest
import com.backblaze.b2.client.structures.B2UploadFileRequest
import com.backblaze.b2.client.structures.B2UploadListener
import com.backblaze.b2.util.B2ByteRange
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.StorageError
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/** SDK exceptions are translated to [B2Failure] here; no SDK type leaves this file. */
class B2SdkConnector(
    private val masterUrl: String? = null,
    /** Seconds waited before each repeat of a request that may be repeated. */
    private val backoffSeconds: List<Int> = listOf(1, 2, 4),
) : B2Connector {
    override fun connect(account: B2Account): B2Session {
        val started = System.nanoTime()
        DebugLog.i(TAG) { "Authorizing ${account.name} · timeout ${account.options.timeoutSeconds} s" }
        val transport = B2HttpTransport(account.options.timeoutSeconds, masterUrl)
        // Built directly: B2StorageClientFactory looks the Apache transport up by reflection.
        val webifier = B2StorageClientWebifierImpl(transport, USER_AGENT, masterUrl ?: MASTER_URL, null)
        // uploadPart stores one part at a time, which the SDK takes for a gap in the numbering.
        val config = B2ClientConfig.builder(account.keyId, account.applicationKey, USER_AGENT)
            .setPartNumberGapsAllowed(true).build()
        val client = B2StorageClientImpl(webifier, config, Supplier<B2RetryPolicy> { Repeats(backoffSeconds) })
        val first = try {
            client.accountAuthorization
        } catch (error: Exception) {
            runCatching { client.close() }
            throw failure("authorize ${account.name}", error).also { report("authorize ${account.name}", it) }
        }
        return Authorized(client, webifier, transport, first, backoffSeconds).also {
            DebugLog.i(TAG) {
                val reach = it.summary.buckets?.joinToString { bucket -> bucket.name }?.let { names -> "only $names" } ?: "every bucket"
                "Authorized ${account.name} in ${DebugLog.millisSince(started)} ms · $reach" +
                    (if (it.summary.namePrefix.isEmpty()) "" else " under ${it.summary.namePrefix}") +
                    " · ${it.summary.capabilities.size} capabilities"
            }
        }
    }

    private class Authorized(
        private val client: B2StorageClientImpl,
        private val webifier: B2StorageClientWebifier,
        private val transport: B2HttpTransport,
        first: B2AccountAuthorization,
        private val backoffSeconds: List<Int>,
    ) : B2Session {
        @Volatile private var spent = false

        override val summary = B2Auth(
            accountId = first.accountId,
            // This API version restricts a key to at most one bucket. Its name is null when the
            // key may not read it.
            buckets = first.allowed.bucketId?.let { id -> listOfNotNull(first.allowed.bucketName?.let { B2BucketInfo(id, it) }) },
            namePrefix = first.allowed.namePrefix.orEmpty(),
            capabilities = first.allowed.capabilities.toSet(),
            recommendedPartSize = first.recommendedPartSize,
            minimumPartSize = first.absoluteMinimumPartSize,
        )

        // Asking for every bucket with a restricted key is answered 401.
        override fun buckets(): List<B2BucketInfo> = summary.buckets ?: calling("list buckets") {
            client.listBuckets(B2ListBucketsRequest.builder(summary.accountId).build()).buckets
                .map { B2BucketInfo(it.bucketId, it.bucketName) }
        }

        override fun bucket(name: String): B2Bucket {
            val found = summary.buckets?.firstOrNull { it.name == name } ?: if (summary.buckets != null) null else {
                calling("find bucket $name") {
                    client.listBuckets(B2ListBucketsRequest.builder(summary.accountId).setBucketName(name).build()).buckets
                        .firstOrNull { it.bucketName == name }?.let { B2BucketInfo(it.bucketId, it.bucketName) }
                }
            }
            if (found == null) {
                throw B2Failure(StorageError.NOT_FOUND, "The key cannot reach a bucket named $name").also { report("open $name", it) }
            }
            return Opened(found)
        }

        override fun alive(): Boolean = !spent

        override fun close() { runCatching { client.close() } }

        private fun <T> calling(what: String, block: () -> T): T = try {
            block()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failed = failure(what, error)
            if (failed.reason == StorageError.AUTH) spent = true
            report(what, failed)
            throw failed
        }

        private fun <T> repeating(operation: String, request: (B2AccountAuthorization) -> T): T {
            val repeats = Repeats(backoffSeconds)
            var attempts = 0
            while (true) {
                attempts++
                try {
                    return request(client.accountAuthorization)
                } catch (error: B2UnauthorizedException) {
                    if (error.requestCategory == B2UnauthorizedException.RequestCategory.ACCOUNT_AUTHORIZATION) throw error
                    // A key that lacks the capability keeps its authorization; only a spent token is worth a new one.
                    if (!repeats.gotRetryableImmediately(operation, attempts, 0, error)) throw error
                    client.invalidateAccountAuthorization()
                } catch (error: B2Exception) {
                    if (!error.mayClear()) throw error
                    val wait = repeats.gotRetryableAfterDelay(operation, attempts, 0, error) ?: throw error
                    try {
                        Thread.sleep(wait * 1000L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                }
            }
        }

        /** [block] is given a flag that is set once a part has failed or the caller has left; parts check it before sending. */
        private fun <T> onWorkers(count: Int, block: (ExecutorService, AtomicBoolean) -> T): T {
            val threads = ConcurrentHashMap.newKeySet<Thread>()
            val executor = Executors.newFixedThreadPool(count) { task ->
                Thread(task, "b2-parts").apply { isDaemon = true }.also { threads += it }
            }
            val stopped = AtomicBoolean()
            try {
                return block(executor, stopped)
            } finally {
                stopped.set(true)
                executor.shutdownNow()
                // An interrupt does not end a socket write, so a part still in flight is cut off.
                threads.forEach(transport::abort)
            }
        }

        private inner class Opened(private val bucket: B2BucketInfo) : B2Bucket {
            private fun where(key: String) = "${bucket.name}/$key"

            override fun list(prefix: String, delimiter: String?, after: String?, limit: Int): B2Page =
                calling("list ${where(prefix)}") {
                    val request = B2ListFileNamesRequest.builder(bucket.id)
                        .setPrefix(prefix).setDelimiter(delimiter).setStartFileName(after).setMaxFileCount(limit).build()
                    val page = repeating("b2_list_file_names") { webifier.listFileNames(it, request) }
                    B2Page(
                        items = page.files.filter { it.holdsBytes() }.map { it.item() },
                        folders = page.files.filter { it.isFolder }.map { it.fileName },
                        next = page.nextFileName,
                    )
                }

            // Asked by listing, not by HEAD on the name: a refused HEAD has no code to read, and Android resolves "." and ".." in a URL path.
            override fun stat(key: String): B2Item? = calling("stat ${where(key)}") {
                val request = B2ListFileNamesRequest.builder(bucket.id).setPrefix(key).setStartFileName(key).setMaxFileCount(1).build()
                repeating("b2_list_file_names") { webifier.listFileNames(it, request) }.files
                    .firstOrNull { it.fileName == key && it.holdsBytes() }?.item()
            }

            override fun versions(key: String): List<B2Version> = calling("list versions of ${where(key)}") {
                val found = ArrayList<B2Version>()
                var name: String? = key
                var id: String? = null
                // The prefix also matches longer names, which sort after every version of this one.
                while (name != null) {
                    val request = B2ListFileVersionsRequest.builder(bucket.id).setPrefix(key)
                        .apply { if (id == null) setStartFileName(name) else setStart(name, id) }
                        .setMaxFileCount(VERSIONS_PER_REQUEST).build()
                    val page = repeating("b2_list_file_versions") { webifier.listFileVersions(it, request) }
                    for (version in page.files) {
                        if (version.fileName != key) return@calling found
                        if (version.fileId == null || version.isStart || version.isFolder) continue
                        found += B2Version(key, version.fileId, hidden = version.isHide)
                    }
                    name = page.nextFileName
                    id = page.nextFileId
                }
                found
            }

            override fun open(item: B2Item, from: Long): InputStream = calling("read ${where(item.key)}") {
                // A range that starts at the end is answered 416, not with nothing.
                if (from >= item.size) return@calling ByteArrayInputStream(ByteArray(0))
                val reply = fetch(item, if (from > 0) "bytes=$from-" else null)
                try {
                    skipTo(from, reply, item)
                } catch (error: Exception) {
                    runCatching { reply.body.close() }
                    throw error
                }
                Download(reply.body, "read ${where(item.key)}")
            }

            override fun openReader(item: B2Item): B2Reader = object : B2Reader {
                override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (offset >= item.size) return -1
                    val wanted = minOf(length.toLong(), item.size - offset).toInt()
                    return calling("read ${where(item.key)} at $offset") {
                        val reply = fetch(item, "bytes=$offset-${offset + wanted - 1}")
                        try {
                            skipTo(offset, reply, item)
                            var filled = 0
                            while (filled < wanted) {
                                val got = reply.body.read(into, at + filled, wanted - filled)
                                if (got < 0) break
                                filled += got
                            }
                            // Reaching the end is what lets the connection be reused.
                            if (filled == wanted && reply.status == PARTIAL) reply.body.read()
                            if (filled == 0) -1 else filled
                        } finally {
                            runCatching { reply.body.close() }
                        }
                    }
                }

                override fun close() = Unit
            }

            private fun fetch(item: B2Item, range: String?): B2HttpTransport.Reply = repeating("b2_download_file_by_id") { auth ->
                val headers = B2HeadersImpl.builder()
                    .set(B2Headers.AUTHORIZATION, auth.authorizationToken)
                    .set(B2Headers.USER_AGENT, USER_AGENT)
                if (range != null) headers.set(B2Headers.RANGE, range)
                val url = webifier.getDownloadByIdUrl(auth, B2DownloadByIdRequest.builder(item.fileId).build())
                transport.get(url, headers.build())
            }

            /** A server may answer a range with the whole file, or with a range that starts earlier. */
            private fun skipTo(wanted: Long, reply: B2HttpTransport.Reply, item: B2Item) {
                val starts = if (reply.status == PARTIAL) {
                    reply.headers.getValueOrNull(B2Headers.CONTENT_RANGE)
                        ?.substringAfter("bytes", "")?.trim()?.substringBefore('-')?.toLongOrNull() ?: wanted
                } else 0L
                if (starts == wanted) return
                if (starts > wanted || wanted - starts > SKIPPED_AT_MOST) {
                    throw B2Failure(StorageError.UNSUPPORTED, "B2 did not honour the byte range asked of ${where(item.key)}")
                }
                DebugLog.w(TAG) { "read ${where(item.key)}: asked from $wanted, answered from $starts; skipping" }
                var left = wanted - starts
                val scratch = ByteArray(64 * 1024)
                while (left > 0) {
                    val got = reply.body.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                    if (got < 0) throw B2Failure(StorageError.IO, "${where(item.key)} ended before byte $wanted")
                    left -= got
                }
            }

            private inner class Download(private val body: InputStream, private val what: String) : InputStream() {
                override fun read(): Int = calling(what) { body.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int = calling(what) { body.read(b, off, len) }
                override fun close() = body.close()
            }

            override fun upload(key: String, source: File, modified: Long?, contentType: String): B2Item =
                calling("upload ${where(key)}") {
                    client.uploadSmallFile(
                        B2UploadFileRequest.builder(bucket.id, key, contentType, Staged(source, modified)).build()
                    ).item()
                }

            override fun startLarge(key: String, modified: Long?, contentType: String): String =
                calling("start large ${where(key)}") {
                    val request = B2StartLargeFileRequest.builder(bucket.id, key, contentType)
                    if (modified != null) request.setSrcLastModifiedMillisOrNull(modified)
                    client.startLargeFile(request.build()).fileId
                }

            override fun uploadPart(largeFileId: String, number: Int, source: File): String =
                calling("upload part $number to ${bucket.name}") {
                    onWorkers(1) { executor, stopped ->
                        client.storePartsForLargeFile(
                            B2StoreLargeFileRequest.builder(largeFileId).build(),
                            listOf<B2PartStorer>(Stopping(B2UploadingPartStorer(number, Staged(source, null, stopped)), stopped)), null, executor,
                        )
                    }.single().contentSha1
                }

            override fun finishLarge(largeFileId: String, partSha1s: List<String>): B2Item =
                calling("finish large file in ${bucket.name}") {
                    client.finishLargeFile(B2FinishLargeFileRequest.builder(largeFileId, partSha1s).build()).item()
                }

            override fun cancelLarge(largeFileId: String) = calling("cancel large file in ${bucket.name}") {
                client.cancelLargeFile(largeFileId)
            }

            override fun copy(from: B2Item, toKey: String): B2Item = calling("copy ${where(from.key)} to ${where(toKey)}") {
                if (from.size <= COPIED_WHOLE_AT_MOST) {
                    client.copySmallFile(
                        B2CopyFileRequest.builder(from.fileId, toKey).setDestinationBucketId(bucket.id).build()
                    ).item()
                } else {
                    copyInParts(from, toKey)
                }
            }

            private fun copyInParts(from: B2Item, toKey: String): B2Item {
                // A part copy carries no metadata across, so it is read from the source first.
                val source = client.getFileInfo(B2GetFileInfoRequest.builder(from.fileId).build())
                val large = client.startLargeFile(
                    B2StartLargeFileRequest.builder(bucket.id, toKey, source.contentType ?: from.contentType)
                        .setCustomFields(source.fileInfo.orEmpty()).build()
                ).fileId
                val partSize = maxOf(summary.recommendedPartSize, (from.size + PARTS_AT_MOST - 1) / PARTS_AT_MOST)
                    .coerceIn(summary.minimumPartSize, COPIED_WHOLE_AT_MOST)
                val ranges = (0 until from.size step partSize).mapIndexed { index, start ->
                    B2CopyingPartStorer(index + 1, from.fileId, B2ByteRange.between(start, minOf(start + partSize, from.size) - 1))
                }
                val sha1s = try {
                    onWorkers(COPY_WORKERS) { executor, stopped ->
                        val parts = ranges.map<B2PartStorer, B2PartStorer> { Stopping(it, stopped) }
                        client.storePartsForLargeFile(B2StoreLargeFileRequest.builder(large).build(), parts, null, executor)
                    }.sortedBy { it.partNumber }.map { it.contentSha1 }
                } catch (error: Exception) {
                    // Nothing was published under the new name, so dropping the parts loses nothing.
                    try {
                        client.cancelLargeFile(large)
                    } catch (left: Exception) {
                        DebugLog.w(TAG, left) { "copy to ${where(toKey)}: the unfinished large file $large could not be cancelled" }
                    }
                    throw error
                }
                // Not cancelled if this fails: the copy may have been finished all the same.
                return client.finishLargeFile(B2FinishLargeFileRequest.builder(large, sha1s).build()).item()
            }

            override fun delete(version: B2Version) = calling("delete ${where(version.key)}") {
                try {
                    client.deleteFileVersion(version.key, version.fileId)
                } catch (gone: B2BadRequestException) {
                    if (gone.code != FILE_NOT_PRESENT) throw gone
                    DebugLog.d(TAG) { "delete ${where(version.key)}: that version was already gone" }
                }
            }

            override fun hide(key: String) = calling("hide ${where(key)}") {
                client.hideFile(bucket.id, key)
                Unit
            }

            override fun alive(): Boolean = !spent
        }
    }

    /** The staged file's own timestamp says nothing about the item, so the SDK is not given it. */
    private class Staged(file: File, private val modified: Long?, private val stopped: AtomicBoolean? = null) : B2ContentSource {
        private val content = B2FileContentSource.build(file)
        override fun getContentLength(): Long = content.contentLength
        override fun getSha1OrNull(): String? = content.sha1OrNull
        override fun getSrcLastModifiedMillisOrNull(): Long? = modified
        // The SDK sends a part again after its wait even when the caller was cancelled during it.
        override fun createInputStream(): InputStream =
            if (stopped?.get() == true) throw IOException("The upload was abandoned") else content.createInputStream()
    }

    /** The SDK waits for every part before reporting a failed one. This ends the parts not yet begun once one has failed. */
    private class Stopping(private val part: B2PartStorer, private val stopped: AtomicBoolean) : B2PartStorer {
        override fun getPartNumber(): Int = part.partNumber
        override fun getPartSizeOrThrow(): Long = part.partSizeOrThrow
        override fun storePart(storer: B2LargeFileStorer, listener: B2UploadListener?, token: B2CancellationToken?): B2Part {
            if (stopped.get()) throw B2LocalException("stopped", "Not sent: another part had already failed")
            return try { part.storePart(storer, listener, token) } catch (error: Exception) { stopped.set(true); throw error }
        }
    }

    // Retry uncertain requests only when repeating them is harmless; other mutations require an explicit server refusal.
    private class Repeats(private val backoffSeconds: List<Int>) : B2RetryPolicy {
        private var renewedToken = false
        private var renewedUploadUrl = false

        override fun gotRetryableAfterDelay(operation: String, attemptsSoFar: Int, tookMillis: Long, e: B2Exception): Int? {
            // The calls run under runInterruptible; the SDK's sleep would outlast a cancellation.
            if (Thread.currentThread().isInterrupted || attemptsSoFar > backoffSeconds.size) return null
            if (operation !in REPEATABLE && !e.refusedUnacted()) {
                DebugLog.w(TAG) { "$operation: not repeated after ${e.code}, its outcome is unknown" }
                return null
            }
            val wait = e.retryAfterSecondsOrNull?.coerceIn(0, WAIT_AT_MOST_SECONDS) ?: backoffSeconds[attemptsSoFar - 1]
            DebugLog.i(TAG) { "$operation: ${e.status} ${e.code}, attempt ${attemptsSoFar + 1} in $wait s" }
            return wait
        }

        override fun gotRetryableImmediately(operation: String, attemptsSoFar: Int, tookMillis: Long, e: B2Exception): Boolean {
            if (Thread.currentThread().isInterrupted) return false
            // Any 401 from an upload URL means that URL is spent. Elsewhere only a spent token is
            // worth a new authorization; a key that lacks the capability stays refused. A 401 with
            // no code (a HEAD, or the JDK dropping the body) could be either, so it gets the one try.
            // Each is renewed once per request, however many waits came before it.
            val uploading = (e as? B2UnauthorizedException)?.requestCategory == B2UnauthorizedException.RequestCategory.UPLOADING
            if (uploading) {
                if (renewedUploadUrl) return false
                renewedUploadUrl = true
            } else {
                if (renewedToken || (e.code !in SPENT_TOKEN && e.code != B2HttpTransport.NO_CODE)) return false
                renewedToken = true
            }
            DebugLog.i(TAG) { "$operation: ${e.status} ${e.code}, authorizing again" }
            return true
        }
    }

    private companion object {
        const val TAG = "B2"
        const val USER_AGENT = "LunaExplorer"
        const val MASTER_URL = "https://api.backblazeb2.com/"
        const val PARTIAL = 206
        const val FILE_NOT_PRESENT = "file_not_present"
        const val COPIED_WHOLE_AT_MOST = 5_000_000_000L
        const val PARTS_AT_MOST = 10_000L
        const val COPY_WORKERS = 4
        const val VERSIONS_PER_REQUEST = 1000
        const val WAIT_AT_MOST_SECONDS = 60
        /** A server that ignores ranges is not followed further than this into a file. */
        const val SKIPPED_AT_MOST = 16L * 1024 * 1024
        val SPENT_TOKEN = setOf("expired_auth_token", "bad_auth_token")
        val REPEATABLE = setOf(
            "b2_authorize_account", "getAccountId", "get_part_sizes", "b2_list_buckets", "b2_list_file_names",
            "b2_list_file_versions", "b2_list_parts", "b2_list_unfinished_large_files", "b2_get_file_info",
            "get_file_info_by_name", "b2_download_file_by_id", "b2_download_file_by_name", "b2_get_upload_url",
            "b2_get_upload_part_url", "getDownloadByIdUrl", "getDownloadByNameUrl",
            // Storing part N again replaces part N.
            "b2_upload_part", "b2_copy_part",
        )

        fun B2FileVersion.holdsBytes() = fileId != null && !isFolder && !isStart && !isHide

        fun B2FileVersion.item() = B2Item(
            key = fileName,
            fileId = fileId,
            size = contentLength,
            modified = fileInfo?.get("src_last_modified_millis")?.toLongOrNull() ?: uploadTimestamp,
            contentType = contentType ?: "application/octet-stream",
        )

        fun B2Exception.mayClear() = this is B2TooManyRequestsException || this is B2ServiceUnavailableException ||
            this is B2InternalErrorException || this is B2RequestTimeoutException || this is B2NetworkBaseException

        /** The server said no before doing anything, or was never reached. */
        fun B2Exception.refusedUnacted() = this is B2ConnectFailedException || this is B2TooManyRequestsException ||
            this is B2ServiceUnavailableException

        fun report(what: String, failed: B2Failure) {
            val cause = failed.cause
            if (cause == null || (cause is B2Exception && cause !is B2NetworkBaseException)) {
                DebugLog.d(TAG) { "$what: ${failed.reason} · ${failed.message}" }
            } else {
                DebugLog.w(TAG, cause) { "$what: ${failed.reason} · ${failed.message}" }
            }
        }

        fun failure(what: String, error: Exception): B2Failure {
            if (error is B2Failure) return error
            val from = generateSequence<Throwable>(error) { it.cause }.take(8).firstOrNull { it is B2Exception } as B2Exception?
            if (from == null) {
                // The SDK reports an interrupted wait for a part without keeping the flag.
                if (error is InterruptedException) Thread.currentThread().interrupt()
                return B2Failure(StorageError.IO, "Could not $what: ${error.message ?: error.javaClass.simpleName}", error)
            }
            if (from.code == "trouble" && from.message == "interrupted exception") Thread.currentThread().interrupt()
            val said = from.message?.takeIf { it.isNotBlank() } ?: from.code
            return when (from) {
                is B2UnauthorizedException -> when {
                    from.requestCategory == B2UnauthorizedException.RequestCategory.ACCOUNT_AUTHORIZATION ->
                        B2Failure(StorageError.AUTH, "B2 rejected the key ID or the application key", from)
                    from.code in SPENT_TOKEN ->
                        B2Failure(StorageError.AUTH, "The authorization ran out and a new one was refused too", from)
                    else -> B2Failure(StorageError.PERMISSION, "The application key is not allowed to $what", from)
                }
                is B2ForbiddenException -> when (from.code) {
                    "storage_cap_exceeded", "cap_exceeded" -> B2Failure(StorageError.NO_SPACE, "The account's storage cap is reached", from)
                    "transaction_cap_exceeded", "download_cap_exceeded" ->
                        B2Failure(StorageError.RATE_LIMITED, "The account's daily cap is reached: ${from.code}", from)
                    else -> B2Failure(StorageError.PERMISSION, "B2 refused to $what: $said", from)
                }
                is B2NotFoundException -> B2Failure(StorageError.NOT_FOUND, "Not found on B2: $what", from)
                is B2BadRequestException -> when {
                    from.code == FILE_NOT_PRESENT || from.code == "bad_bucket_id" ->
                        B2Failure(StorageError.NOT_FOUND, "Not found on B2: $what", from)
                    from.code == "invalid_file_name" || from.code == "invalid_bucket_name" ||
                        (from.code == "bad_request" && said.contains("file name", ignoreCase = true)) ->
                        B2Failure(StorageError.INVALID_NAME, "B2 does not accept that name: $said", from)
                    from.code == "cap_exceeded" -> B2Failure(StorageError.NO_SPACE, "The account's cap is reached", from)
                    from.code == "source_too_large" -> B2Failure(StorageError.UNSUPPORTED, "Too large to copy in one request", from)
                    else -> B2Failure(StorageError.IO, "B2 refused to $what: $said", from)
                }
                is B2RequestTimeoutException, is B2NetworkTimeoutException ->
                    B2Failure(StorageError.TIMEOUT, "B2 did not answer in time", from)
                is B2TooManyRequestsException -> B2Failure(StorageError.RATE_LIMITED, "B2 is limiting requests", from)
                is B2ServiceUnavailableException -> B2Failure(
                    if (from.retryAfterSecondsOrNull != null) StorageError.RATE_LIMITED else StorageError.IO,
                    "B2 is unavailable", from)
                is B2ConnectFailedException -> when (from.code) {
                    B2HttpTransport.CONNECT_TIMED_OUT -> B2Failure(StorageError.TIMEOUT, "B2 did not answer in time", from)
                    B2HttpTransport.INSECURE -> B2Failure(StorageError.IO, "A secure connection to B2 could not be made", from)
                    else -> B2Failure(StorageError.OFFLINE, "B2 could not be reached", from)
                }
                is B2NetworkBaseException -> B2Failure(StorageError.IO, "The connection to B2 was lost", from)
                else -> B2Failure(StorageError.IO, "Could not $what: $said", from)
            }
        }
    }
}
