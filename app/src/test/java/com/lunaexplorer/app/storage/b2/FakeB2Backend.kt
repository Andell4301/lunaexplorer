package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.StorageError
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.TreeMap

/** An in-memory B2: flat keys, a stack of versions per key, hide markers, large files in parts. */
internal class FakeB2Backend(vararg bucketNames: String = arrayOf("media")) {
    class Stored(val fileId: String, val bytes: ByteArray, val contentType: String, val hidden: Boolean)

    /** Bucket name to key to versions, newest first. */
    val buckets = LinkedHashMap<String, TreeMap<String, ArrayDeque<Stored>>>().apply {
        bucketNames.forEach { put(it, TreeMap()) }
    }
    var auth = B2Auth("account")
    var listCalls = 0
    var bytesDownloaded = 0L
    val uploadedParts = ArrayList<Pair<Int, Int>>()
    val cancelledLargeFiles = ArrayList<String>()
    /** Thrown by the next bucket call, once. */
    var failNext: B2Failure? = null
    /** Thrown by every part upload from this part number on. */
    var failPartsFrom = Int.MAX_VALUE
    private var ids = 0
    private val largeFiles = HashMap<String, Triple<String, String, TreeMap<Int, ByteArray>>>()

    fun put(bucket: String, key: String, text: String) = push(bucket, key, text.toByteArray(), "text/plain", hidden = false)

    fun versionsOf(bucket: String, key: String): List<Stored> = buckets.getValue(bucket)[key].orEmpty().toList()

    fun text(bucket: String, key: String): String? = current(bucket, key)?.bytes?.decodeToString()

    private fun current(bucket: String, key: String): Stored? =
        buckets.getValue(bucket)[key]?.firstOrNull()?.takeUnless { it.hidden }

    private fun push(bucket: String, key: String, bytes: ByteArray, contentType: String, hidden: Boolean): Stored =
        Stored("id-${++ids}", bytes, contentType, hidden).also { buckets.getValue(bucket).getOrPut(key) { ArrayDeque() }.addFirst(it) }

    private fun item(key: String, stored: Stored) = B2Item(key, stored.fileId, stored.bytes.size.toLong(), 1_700_000_000_000, stored.contentType)

    private fun trip() { failNext?.let { failNext = null; throw it } }

    /** B2 refuses any name outside the prefix a key is confined to. */
    private fun within(name: String) {
        if (!name.startsWith(auth.namePrefix)) throw B2Failure(StorageError.PERMISSION, "The application key is not allowed to reach $name")
    }

    fun bucket(name: String): B2Bucket {
        if (name !in buckets) throw B2Failure(StorageError.NOT_FOUND, "No bucket named $name")
        return object : B2Bucket {
            override fun list(prefix: String, delimiter: String?, after: String?, limit: Int): B2Page {
                trip(); within(prefix); listCalls++
                val names = TreeMap<String, Stored?>()
                buckets.getValue(name).forEach { (key, _) ->
                    val stored = current(name, key) ?: return@forEach
                    if (!key.startsWith(prefix)) return@forEach
                    val rest = key.removePrefix(prefix)
                    val cut = delimiter?.let { rest.indexOf(it) } ?: -1
                    if (cut >= 0) names[prefix + rest.substring(0, cut + 1)] = null else names[key] = stored
                }
                val from = names.tailMap(after ?: "", true).entries.toList()
                val page = from.take(limit)
                return B2Page(
                    items = page.mapNotNull { (key, stored) -> stored?.let { item(key, it) } },
                    folders = page.filter { it.value == null }.map { it.key },
                    next = from.getOrNull(limit)?.key,
                )
            }

            override fun stat(key: String): B2Item? { trip(); within(key); return current(name, key)?.let { item(key, it) } }

            override fun versions(key: String): List<B2Version> {
                trip(); return versionsOf(name, key).map { B2Version(key, it.fileId, it.hidden) }
            }

            override fun open(item: B2Item, from: Long): InputStream {
                trip()
                val bytes = byId(item.fileId)
                bytesDownloaded += bytes.size - from
                return ByteArrayInputStream(bytes, from.toInt(), bytes.size - from.toInt())
            }

            override fun openReader(item: B2Item): B2Reader = object : B2Reader {
                override fun read(offset: Long, into: ByteArray, at: Int, length: Int): Int {
                    trip()
                    val bytes = byId(item.fileId)
                    if (offset >= bytes.size) return -1
                    val n = minOf(length, bytes.size - offset.toInt())
                    System.arraycopy(bytes, offset.toInt(), into, at, n)
                    bytesDownloaded += n
                    return n
                }
                override fun close() = Unit
            }

            override fun upload(key: String, source: File, modified: Long?, contentType: String): B2Item {
                trip(); return item(key, push(name, key, source.readBytes(), contentType, hidden = false))
            }

            override fun startLarge(key: String, modified: Long?, contentType: String): String {
                trip(); return "large-${++ids}".also { largeFiles[it] = Triple(key, contentType, TreeMap()) }
            }

            override fun uploadPart(largeFileId: String, number: Int, source: File): String {
                trip()
                if (number >= failPartsFrom) throw B2Failure(StorageError.IO, "Part $number was refused")
                val bytes = source.readBytes()
                largeFiles.getValue(largeFileId).third[number] = bytes
                uploadedParts += number to bytes.size
                return "sha1-of-part-$number"
            }

            override fun finishLarge(largeFileId: String, partSha1s: List<String>): B2Item {
                trip()
                val (key, contentType, parts) = largeFiles.remove(largeFileId) ?: throw B2Failure(StorageError.NOT_FOUND, "No such large file")
                check(partSha1s == parts.keys.map { "sha1-of-part-$it" }) { "Parts finished out of order: $partSha1s" }
                return item(key, push(name, key, parts.values.fold(ByteArray(0)) { all, part -> all + part }, contentType, hidden = false))
            }

            override fun cancelLarge(largeFileId: String) { largeFiles.remove(largeFileId); cancelledLargeFiles += largeFileId }

            override fun copy(from: B2Item, toKey: String): B2Item {
                trip(); return item(toKey, push(name, toKey, byId(from.fileId), from.contentType, hidden = false))
            }

            override fun delete(version: B2Version) {
                trip()
                val versions = buckets.getValue(name)[version.key] ?: return
                versions.removeAll { it.fileId == version.fileId }
                if (versions.isEmpty()) buckets.getValue(name).remove(version.key)
            }

            override fun hide(key: String) { trip(); push(name, key, ByteArray(0), "application/x-bz-hide-marker", hidden = true) }

            override fun alive(): Boolean = true

            private fun byId(fileId: String): ByteArray = buckets.getValue(name).values.flatten()
                .firstOrNull { it.fileId == fileId }?.bytes ?: throw B2Failure(StorageError.NOT_FOUND, "That version is gone")
        }
    }
}

internal class FakeB2Connector(val backend: FakeB2Backend = FakeB2Backend()) : B2Connector {
    val keysSeen = ArrayList<String>()
    var refuse: B2Failure? = null

    override fun connect(account: B2Account): B2Session {
        refuse?.let { throw it }
        keysSeen += account.applicationKey
        return object : B2Session {
            override val summary: B2Auth get() = backend.auth
            override fun buckets() = backend.buckets.keys.map { B2BucketInfo("bucket-$it", it) }
            override fun bucket(name: String) = backend.bucket(name)
            override fun alive() = true
            override fun close() = Unit
        }
    }
}
