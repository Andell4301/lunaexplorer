package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.StorageError
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

class B2SdkConnectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val b2 = FakeB2()
    private val account = B2Account(id = "b2", name = "Backups", keyId = "004keyid", applicationKey = APPLICATION_KEY)
    private var previous: DebugLog? = null
    private lateinit var log: DebugLog
    private val sessions = ArrayList<B2Session>()

    @Before fun start() {
        previous = DebugLog.installed
        log = DebugLog(temporary.newFolder("log")).apply { setRecording(true) }
        DebugLog.installed = log
    }

    @After fun stop() {
        sessions.forEach { it.close() }
        b2.stop()
        log.close()
        DebugLog.installed = previous
    }

    private fun connect(using: B2Account = account): B2Session =
        B2SdkConnector(b2.url, backoffSeconds = listOf(0, 0, 0)).connect(using).also { sessions += it }

    private fun photos(): B2Bucket = connect().bucket("photos")

    private fun staged(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private fun failureOf(block: () -> Unit): B2Failure = try {
        block()
        fail("Expected a B2Failure")
        throw AssertionError()
    } catch (failure: B2Failure) {
        failure
    }

    @Test fun `a key held to one bucket reports it without asking for the list`() {
        b2.restrictedTo = "photos"

        val session = connect()

        assertEquals(listOf(B2BucketInfo("id-photos", "photos")), session.buckets())
        assertEquals(listOf(B2BucketInfo("id-photos", "photos")), session.summary.buckets)
        assertEquals(StorageError.NOT_FOUND, failureOf { session.bucket("documents") }.reason)
        assertEquals(0, b2.calls("b2_list_buckets"))
    }

    @Test fun `a key for the whole account lists its buckets`() {
        val session = connect()

        assertNull(session.summary.buckets)
        assertEquals(listOf("documents", "photos"), session.buckets().map { it.name }.sorted())
        assertEquals(StorageError.NOT_FOUND, failureOf { session.bucket("absent") }.reason)
    }

    @Test fun `the wrong application key fails the connection as a sign-in problem`() {
        val failure = failureOf { connect(account.copy(applicationKey = "not-the-key")) }

        assertEquals(StorageError.AUTH, failure.reason)
    }

    @Test fun `a page holds files and folders apart and its cursor fetches the next one`() {
        b2.store("photos", "2024/a.jpg", "aaaa".toByteArray(), info = mapOf("src_last_modified_millis" to "1700000000000"))
        b2.store("photos", "2024/b.jpg", "bb".toByteArray())
        b2.store("photos", "2024/trips/rome/1.jpg", "r".toByteArray())
        b2.store("photos", "2024/unfinished.bin", ByteArray(0), action = "start")
        b2.store("photos", "2024/z.jpg", "z".toByteArray())
        val bucket = photos()

        val first = bucket.list("2024/", "/", null, 2)
        val second = bucket.list("2024/", "/", first.next, 10)

        assertEquals(listOf("2024/a.jpg", "2024/b.jpg"), first.items.map { it.key })
        assertEquals(4L, first.items[0].size)
        assertEquals(1_700_000_000_000, first.items[0].modified)
        assertEquals("Without a source time the upload time stands in", b2.timestampOf("2024/b.jpg"), first.items[1].modified)
        assertEquals("2024/trips/", first.next)
        assertEquals(listOf("2024/trips/"), second.folders)
        assertEquals("The unfinished upload is left out", listOf("2024/z.jpg"), second.items.map { it.key })
        assertNull(second.next)
        assertEquals(listOf(2, 10), b2.bodies("b2_list_file_names").map { it["maxFileCount"]!!.jsonPrimitive.int })
    }

    @Test fun `stat answers for exactly the name asked`() {
        b2.store("photos", "notes.txt.bak", "backup".toByteArray())
        val bucket = photos()

        assertNull(bucket.stat("notes.txt"))

        val id = b2.store("photos", "notes.txt", "hello".toByteArray(), type = "text/plain")
        val found = bucket.stat("notes.txt")!!
        assertEquals(B2Item("notes.txt", id, 5, b2.timestampOf("notes.txt"), "text/plain"), found)

        b2.store("photos", "a/../b+c %.txt", "odd".toByteArray())
        assertEquals("The name never travels in a URL, where dots, plus and percent would be rewritten",
            "a/../b+c %.txt", bucket.stat("a/../b+c %.txt")?.key)
    }

    @Test fun `versions are those of exactly that name, newest first, hide markers included`() {
        val oldest = b2.store("photos", "notes.txt", "one".toByteArray())
        val newer = b2.store("photos", "notes.txt", "two".toByteArray())
        b2.store("photos", "notes.txt.bak", "other".toByteArray())
        val bucket = photos()
        bucket.hide("notes.txt")

        val versions = bucket.versions("notes.txt")

        assertEquals(listOf(true, false, false), versions.map { it.hidden })
        assertEquals(listOf(newer, oldest), versions.drop(1).map { it.fileId })
        assertTrue(versions.all { it.key == "notes.txt" })
        assertNull("Hidden from listings", bucket.stat("notes.txt"))
    }

    @Test fun `a read from an offset gets the rest of the file in one request`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val item = B2Item("clip.bin", id, BYTES.size.toLong(), 0)

        val read = photos().open(item, 100).use { it.readBytes() }

        assertArrayEquals(BYTES.copyOfRange(100, BYTES.size), read)
        assertEquals(listOf("bytes=100-"), b2.headers("b2_download_file_by_id", "Range"))
        assertEquals("No transparent compression", listOf("identity"), b2.headers("b2_download_file_by_id", "Accept-Encoding"))

        assertEquals("From the end there is nothing to ask for", 0, photos().open(item, BYTES.size.toLong()).use { it.readBytes() }.size)
        assertEquals(1, b2.calls("b2_download_file_by_id"))
    }

    @Test fun `a reader asks for closed ranges and answers past the end without a request`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val reader = photos().openReader(B2Item("clip.bin", id, BYTES.size.toLong(), 0))
        val into = ByteArray(64)

        assertEquals(50, reader.read(10, into, 4, 50))
        assertArrayEquals(BYTES.copyOfRange(10, 60), into.copyOfRange(4, 54))
        assertEquals("Short at the end", 24, reader.read(BYTES.size - 24L, into, 0, 64))
        assertEquals(-1, reader.read(BYTES.size.toLong(), into, 0, 64))
        assertEquals(listOf("bytes=10-59", "bytes=${BYTES.size - 24}-${BYTES.size - 1}"), b2.headers("b2_download_file_by_id", "Range"))
    }

    @Test fun `a server that ignores the range still yields the bytes asked for`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val item = B2Item("clip.bin", id, BYTES.size.toLong(), 0)
        val bucket = photos()
        b2.ignoreRanges = true
        val into = ByteArray(16)

        assertEquals(16, bucket.openReader(item).read(200, into, 0, 16))
        assertArrayEquals(BYTES.copyOfRange(200, 216), into)
        assertArrayEquals(BYTES.copyOfRange(300, BYTES.size), bucket.open(item, 300).use { it.readBytes() })
    }

    @Test fun `an upload reaches the server with its length, its SHA-1 and its source time`() {
        val body = "the staged bytes".toByteArray()

        val item = photos().upload("docs/plan one+two.txt", staged(body), 1_650_000_000_000, "text/plain")

        val sent = b2.uploads.single()
        assertArrayEquals(body, sent.body)
        assertEquals("docs/plan one+two.txt", sent.name)
        assertEquals("The SHA-1 follows the bytes", "hex_digits_at_end", sent.headers["X-bz-content-sha1"])
        assertEquals(sha1(body), sent.trailer)
        assertEquals((body.size + 40).toString(), sent.headers["Content-length"])
        assertEquals("text/plain", sent.headers["Content-type"])
        assertEquals("1650000000000", sent.headers["X-bz-info-src_last_modified_millis"])
        assertEquals(B2Item("docs/plan one+two.txt", item.fileId, body.size.toLong(), 1_650_000_000_000, "text/plain"), item)
    }

    @Test fun `an upload with no source time does not borrow the staged file's`() {
        photos().upload("a.bin", staged(byteArrayOf(1, 2, 3)), null, "application/octet-stream")

        assertNull(b2.uploads.single().headers["X-bz-info-src_last_modified_millis"])
    }

    @Test fun `a large file's parts arrive in order and the finish names their SHA-1s`() {
        val parts = listOf("first part ".toByteArray(), "second part ".toByteArray(), "end".toByteArray())
        val bucket = photos()

        val large = bucket.startLarge("video.mkv", 1_600_000_000_000, "video/x-matroska")
        val sha1s = parts.mapIndexed { index, bytes -> bucket.uploadPart(large, index + 1, staged(bytes)) }
        val item = bucket.finishLarge(large, sha1s)

        assertEquals(parts.map(::sha1), sha1s)
        assertEquals(listOf(1, 2, 3), b2.parts.map { it.number })
        assertTrue(parts.indices.all { b2.parts[it].body.contentEquals(parts[it]) })
        val finish = b2.bodies("b2_finish_large_file").single()
        assertEquals(sha1s, finish["partSha1Array"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(B2Item("video.mkv", large, parts.sumOf { it.size }.toLong(), 1_600_000_000_000, "video/x-matroska"), item)
    }

    @Test fun `a cancelled large file is cancelled on the server`() {
        val bucket = photos()
        val large = bucket.startLarge("video.mkv", null, "video/x-matroska")

        bucket.cancelLarge(large)

        assertEquals(large, b2.bodies("b2_cancel_large_file").single()["fileId"]!!.jsonPrimitive.content)
    }

    @Test fun `a part is sent again after the connection drops`() {
        val bucket = photos()
        val large = bucket.startLarge("video.mkv", null, "video/x-matroska")
        b2.failNext("part", Fault.Drop)

        val sha1 = bucket.uploadPart(large, 1, staged("part".toByteArray()))

        assertEquals(sha1("part".toByteArray()), sha1)
        assertEquals(2, b2.calls("part"))
    }

    @Test fun `an upload is not sent again after the connection drops`() {
        b2.failNext("upload", Fault.Drop)

        val failure = failureOf { photos().upload("a.bin", staged(byteArrayOf(1)), null, "application/octet-stream") }

        assertEquals(StorageError.IO, failure.reason)
        assertEquals(1, b2.calls("upload"))
    }

    @Test fun `a change the server sits on times out and is not sent again`() {
        val bucket = connect(account.copy(options = B2Options(timeoutSeconds = 1))).bucket("photos")
        b2.failNext("b2_hide_file", Fault.Stall)

        val failure = failureOf { bucket.hide("a.txt") }

        assertEquals(StorageError.TIMEOUT, failure.reason)
        assertEquals(1, b2.calls("b2_hide_file"))
    }

    @Test fun `a change refused for the moment is sent again`() {
        b2.store("photos", "a.txt", "a".toByteArray())
        b2.failNext("b2_hide_file", Fault.Refuse(503, "service_unavailable"))

        photos().hide("a.txt")

        assertEquals(2, b2.calls("b2_hide_file"))
    }

    @Test fun `an expired token is renewed once and the request goes through`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val bucket = photos()
        b2.expireTokens()

        assertEquals(listOf("clip.bin"), bucket.list("", "/", null, 10).items.map { it.key })
        b2.expireTokens()
        assertArrayEquals(BYTES, bucket.open(B2Item("clip.bin", id, BYTES.size.toLong(), 0)).use { it.readBytes() })

        assertEquals("One at connect and one for each expiry", 3, b2.calls("b2_authorize_account"))
        assertTrue(bucket.alive())
    }

    @Test fun `a token that expires behind an earlier retry is still renewed`() {
        b2.store("photos", "clip.bin", BYTES)
        val bucket = photos()
        b2.expireTokens()
        b2.failNext("b2_list_file_names", Fault.Refuse(503, "service_unavailable"))

        assertEquals(listOf("clip.bin"), bucket.list("", "/", null, 10).items.map { it.key })
        assertTrue(bucket.alive())
    }

    @Test fun `a token that stays refused ends the session`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val session = connect()
        val bucket = session.bucket("photos")
        b2.refuseEveryToken = true

        val failure = failureOf { bucket.open(B2Item("clip.bin", id, BYTES.size.toLong(), 0)).close() }

        assertEquals(StorageError.AUTH, failure.reason)
        assertEquals("Authorized again once, not in a loop", 2, b2.calls("b2_authorize_account"))
        assertFalse(bucket.alive())
        assertFalse(session.alive())
    }

    @Test fun `a key without the capability is refused as a permission problem and the session lives on`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val session = connect()
        val bucket = session.bucket("photos")
        b2.failNext("b2_download_file_by_id", Fault.Refuse(401, "unauthorized"))

        val failure = failureOf { bucket.open(B2Item("clip.bin", id, BYTES.size.toLong(), 0)).close() }

        assertEquals(StorageError.PERMISSION, failure.reason)
        assertEquals(1, b2.calls("b2_download_file_by_id"))
        assertTrue(session.alive())
        bucket.list("", "/", null, 10)
        assertEquals("A refusal for want of a capability costs no new authorization", 1, b2.calls("b2_authorize_account"))
    }

    @Test fun `caps, missing files and rate limits keep their meaning`() {
        val bucket = photos()
        val missing = B2Item("gone.bin", "no-such-id", 10, 0)

        b2.failNext("b2_get_upload_url", Fault.Refuse(403, "storage_cap_exceeded"))
        assertEquals(StorageError.NO_SPACE, failureOf { bucket.upload("a", staged(byteArrayOf(1)), null, "b2/x-auto") }.reason)
        bucket.upload("a", staged(byteArrayOf(1)), null, "b2/x-auto")
        b2.failNext("upload", Fault.Refuse(403, "cap_exceeded"))
        assertEquals("The upload itself names the cap differently", StorageError.NO_SPACE,
            failureOf { bucket.upload("b", staged(byteArrayOf(1)), null, "b2/x-auto") }.reason)
        b2.failNext("b2_list_file_names", Fault.Refuse(403, "transaction_cap_exceeded"))
        assertEquals(StorageError.RATE_LIMITED, failureOf { bucket.list("", "/", null, 10) }.reason)
        assertEquals(StorageError.NOT_FOUND, failureOf { bucket.open(missing).close() }.reason)
        assertEquals(StorageError.NOT_FOUND, failureOf { bucket.copy(missing, "copy.bin") }.reason)

        val before = b2.calls("b2_list_file_names")
        repeat(4) { b2.failNext("b2_list_file_names", Fault.Refuse(429, "too_many_requests")) }
        assertEquals(StorageError.RATE_LIMITED, failureOf { bucket.list("", "/", null, 10) }.reason)
        assertEquals("Tried, then three more times", 4, b2.calls("b2_list_file_names") - before)
    }

    @Test fun `a server that cannot be reached is offline`() {
        val closed = ServerSocket(0).use { it.localPort }

        val failure = failureOf { B2SdkConnector("http://127.0.0.1:$closed", listOf(0, 0, 0)).connect(account) }

        assertEquals(StorageError.OFFLINE, failure.reason)
    }

    @Test fun `a plain HTTP address from the server is not sent the token`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        b2.downloadUrl = "http://198.51.100.7:9"

        val failure = failureOf { photos().open(B2Item("clip.bin", id, BYTES.size.toLong(), 0)).close() }

        assertEquals(StorageError.IO, failure.reason)
        assertTrue(failure.message, failure.message!!.contains("Refused"))
    }

    @Test fun `deleting a version that is already gone counts as deleted`() {
        val id = b2.store("photos", "a.txt", "a".toByteArray())
        val bucket = photos()

        bucket.delete(B2Version("a.txt", id))
        bucket.delete(B2Version("a.txt", id))

        assertNull(bucket.stat("a.txt"))
        assertEquals(2, b2.calls("b2_delete_file_version"))
    }

    @Test fun `a copy is made on the server under the new name`() {
        val id = b2.store("photos", "a.txt", "content".toByteArray(), type = "text/plain")
        val bucket = photos()

        val copy = bucket.copy(bucket.stat("a.txt")!!, "b/a.txt")

        val request = b2.bodies("b2_copy_file").single()
        assertEquals(id, request["sourceFileId"]!!.jsonPrimitive.content)
        assertEquals("id-photos", request["destinationBucketId"]!!.jsonPrimitive.content)
        assertEquals(bucket.stat("b/a.txt"), copy)
        assertEquals(7L, copy.size)
    }

    @Test fun `a copy over 5 GB goes by parts, and a failed part cancels it`() {
        val id = b2.store("photos", "huge.bin", "stand-in".toByteArray(), info = mapOf("src_last_modified_millis" to "1500000000000"))
        val huge = B2Item("huge.bin", id, 5_200_000_000, 0)
        val bucket = photos()

        val copy = bucket.copy(huge, "moved/huge.bin")

        val ranges = b2.bodies("b2_copy_part").sortedBy { it["partNumber"]!!.jsonPrimitive.int }.map { it["range"]!!.jsonPrimitive.content }
        assertEquals(52, ranges.size)
        assertEquals("bytes=0-99999999", ranges.first())
        assertEquals("bytes=5100000000-5199999999", ranges.last())
        assertEquals("moved/huge.bin", copy.key)
        assertEquals("The source's own time is carried over", 1_500_000_000_000, copy.modified)
        assertEquals(0, b2.calls("b2_cancel_large_file"))

        val copied = b2.calls("b2_copy_part")
        b2.failNext("b2_copy_part", Fault.Refuse(400, "bad_request"))
        failureOf { bucket.copy(huge, "moved/again.bin") }
        assertEquals(1, b2.calls("b2_cancel_large_file"))
        assertTrue("The parts not yet begun are not tried once one has failed", b2.calls("b2_copy_part") - copied < 20)
        assertEquals(1, b2.calls("b2_finish_large_file"))
    }

    @Test fun `neither the log nor a failure carries the key, a token or an upload URL`() {
        val id = b2.store("photos", "clip.bin", BYTES)
        val bucket = photos()
        val said = ArrayList<String>()
        fun note(block: () -> Unit) {
            val failure = failureOf(block)
            said += generateSequence<Throwable>(failure) { it.cause }.take(8).map { it.toString() }
        }

        bucket.upload("a.bin", staged(byteArrayOf(1)), null, "b2/x-auto")
        b2.failNext("upload", Fault.Drop)
        note { bucket.upload("b.bin", staged(byteArrayOf(1)), null, "b2/x-auto") }
        b2.failNext("upload", Fault.Refuse(500, "internal_error"))
        note { bucket.upload("c.bin", staged(byteArrayOf(1)), null, "b2/x-auto") }
        b2.failNext("b2_hide_file", Fault.Drop)
        note { bucket.hide("a.bin") }
        b2.refuseEveryToken = true
        note { bucket.open(B2Item("clip.bin", id, BYTES.size.toLong(), 0)).close() }
        note { connect(account.copy(applicationKey = "$APPLICATION_KEY-wrong")) }

        val text = log.snapshot().text + said.joinToString("\n")
        assertTrue("Something was logged", text.contains("upload photos/b.bin"))
        (b2.secrets + APPLICATION_KEY).forEach { secret -> assertFalse("Leaked $secret in:\n$text", text.contains(secret)) }
    }

    private sealed interface Fault {
        data class Refuse(val status: Int, val code: String) : Fault
        /** Takes the request, then closes the connection without answering. */
        data object Drop : Fault
        data object Stall : Fault
    }

    private class Stored(
        val bucket: String, val name: String, val id: String, val action: String, val bytes: ByteArray,
        val type: String?, val info: Map<String, String>, val timestamp: Long,
    )

    private class Received(val name: String, val headers: Map<String, String>, val body: ByteArray, val trailer: String, val number: Int)

    /** Speaks version 2 of the native API, the one this SDK release calls. */
    private class FakeB2 {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val pool = Executors.newCachedThreadPool()
        val url get() = "http://127.0.0.1:${server.address.port}"
        var downloadUrl: String? = null
        var restrictedTo: String? = null
        @Volatile var ignoreRanges = false
        @Volatile var refuseEveryToken = false
        val secrets: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val uploads: MutableList<Received> = Collections.synchronizedList(ArrayList())
        val parts: MutableList<Received> = Collections.synchronizedList(ArrayList())
        private val buckets = listOf("photos", "documents")
        private val files = ArrayList<Stored>()
        private val large = HashMap<String, Stored>()
        private val seen = Collections.synchronizedList(ArrayList<Pair<String, HttpExchange>>())
        private val received = Collections.synchronizedList(ArrayList<Pair<String, JsonObject>>())
        private val faults = HashMap<String, ArrayDeque<Fault>>()
        private var token = ""
        private var issued = 0
        private var ids = 0
        private var clock = 1_720_000_000_000

        init {
            server.executor = pool
            server.createContext("/") { exchange ->
                try { handle(exchange) } catch (error: Exception) { error.printStackTrace(); exchange.close() }
            }
            server.start()
        }

        fun stop() { server.stop(0); pool.shutdownNow() }

        @Synchronized fun store(
            bucket: String, name: String, bytes: ByteArray, type: String? = "application/octet-stream",
            info: Map<String, String> = emptyMap(), action: String = "upload",
        ): String {
            val stored = Stored(bucket, name, "id-${++ids}", action, bytes, type, info, clock++)
            files += stored
            return stored.id
        }

        @Synchronized fun timestampOf(name: String): Long = files.last { it.name == name }.timestamp
        @Synchronized fun failNext(api: String, fault: Fault) { faults.getOrPut(api) { ArrayDeque() }.addLast(fault) }
        @Synchronized fun expireTokens() { token = "" }
        fun calls(api: String): Int = seen.count { it.first == api }
        fun bodies(api: String): List<JsonObject> = received.filter { it.first == api }.map { it.second }
        fun headers(api: String, header: String): List<String?> = seen.filter { it.first == api }.map { it.second.requestHeaders.getFirst(header) }

        private fun secret(kind: String): String = "4_${kind}_${++issued}_zz9secret".also { secrets += it }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val api = when {
                path.startsWith("/upload/") -> "upload"
                path.startsWith("/part/") -> "part"
                else -> path.substringAfterLast('/')
            }
            seen += api to exchange
            val body = exchange.requestBody.readBytes()
            when (val fault = synchronized(this) { faults[api]?.removeFirstOrNull() }) {
                is Fault.Refuse -> return refuse(exchange, fault.status, fault.code)
                Fault.Drop -> return exchange.close()
                Fault.Stall -> { Thread.sleep(2_500); return exchange.close() }
                null -> Unit
            }
            val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            when (api) {
                "b2_authorize_account" -> return authorize(exchange, authorization)
                "upload", "part" -> if (authorization !in secrets) return refuse(exchange, 401, "bad_auth_token")
                else -> if (refuseEveryToken || authorization != synchronized(this) { token }) {
                    return refuse(exchange, 401, "expired_auth_token")
                }
            }
            when (api) {
                "upload" -> return upload(exchange, body)
                "part" -> return part(exchange, path.substringAfter("/part/").substringBefore('/'), body)
                "b2_download_file_by_id" -> return download(exchange)
            }
            val request = Json.parseToJsonElement(body.decodeToString()).jsonObject
            received += api to request
            fun text(name: String): String? = request[name]?.jsonPrimitive?.takeIf { it.isString }?.content
            synchronized(this) {
                when (api) {
                    "b2_list_buckets" -> reply(exchange, buildJsonObject {
                        putJsonArray("buckets") {
                            buckets.filter { text("bucketName") == null || text("bucketName") == it }.forEach { name ->
                                add(buildJsonObject {
                                    put("accountId", "acct"); put("bucketId", "id-$name"); put("bucketName", name)
                                    put("bucketType", "allPrivate"); put("revision", 1)
                                    putJsonObject("fileLockConfiguration") { put("isClientAuthorizedToRead", false); put("value", null as String?) }
                                    putJsonObject("defaultServerSideEncryption") { put("isClientAuthorizedToRead", false); put("value", null as String?) }
                                })
                            }
                        }
                    })
                    "b2_list_file_names" -> {
                        val prefix = text("prefix").orEmpty()
                        val delimiter = text("delimiter")
                        val bucket = text("bucketId")!!.removePrefix("id-")
                        val current = files.filter { it.bucket == bucket }.groupBy { it.name }.values
                            .map { versions -> versions.last() }.filter { it.action != "hide" && it.name.startsWith(prefix) }
                        val entries = current.map { stored ->
                            val rest = stored.name.removePrefix(prefix)
                            val cut = delimiter?.let(rest::indexOf) ?: -1
                            if (cut < 0) stored.name to stored else prefix + rest.substring(0, cut + delimiter!!.length) to null
                        }.distinctBy { it.first }.sortedBy { it.first }.filter { it.first >= text("startFileName").orEmpty() }
                        val count = request["maxFileCount"]!!.jsonPrimitive.int
                        reply(exchange, buildJsonObject {
                            putJsonArray("files") {
                                entries.take(count).forEach { (name, stored) ->
                                    add(if (stored != null) version(stored) else buildJsonObject {
                                        put("fileName", name); put("action", "folder"); put("uploadTimestamp", 0); put("contentLength", 0)
                                    })
                                }
                            }
                            put("nextFileName", entries.getOrNull(count)?.first)
                        })
                    }
                    "b2_list_file_versions" -> {
                        val bucket = text("bucketId")!!.removePrefix("id-")
                        val all = files.filter { it.bucket == bucket && it.name.startsWith(text("prefix").orEmpty()) }
                            .sortedWith(compareBy<Stored> { it.name }.thenByDescending { it.timestamp })
                        val from = all.indexOfFirst { stored ->
                            text("startFileId")?.let { stored.name == text("startFileName") && stored.id == it }
                                ?: (stored.name >= text("startFileName").orEmpty())
                        }.takeIf { it >= 0 } ?: all.size
                        val count = request["maxFileCount"]!!.jsonPrimitive.int
                        val next = all.getOrNull(from + count)
                        reply(exchange, buildJsonObject {
                            putJsonArray("files") { all.drop(from).take(count).forEach { add(version(it)) } }
                            put("nextFileName", next?.name); put("nextFileId", next?.id)
                        })
                    }
                    "b2_get_upload_url" -> reply(exchange, buildJsonObject {
                        put("bucketId", text("bucketId")); put("authorizationToken", secret("upload"))
                        put("uploadUrl", "$url/upload/${text("bucketId")}/${secret("pod")}")
                    })
                    "b2_get_upload_part_url" -> reply(exchange, buildJsonObject {
                        put("fileId", text("fileId")); put("authorizationToken", secret("part"))
                        put("uploadUrl", "$url/part/${text("fileId")}/${secret("pod")}")
                    })
                    "b2_start_large_file" -> {
                        val info = request["fileInfo"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                        val started = Stored(text("bucketId")!!.removePrefix("id-"), text("fileName")!!, "large-${large.size + 1}",
                            "start", ByteArray(0), text("contentType"), info, clock++)
                        large[started.id] = started
                        reply(exchange, version(started))
                    }
                    "b2_copy_part" -> reply(exchange, buildJsonObject {
                        put("fileId", text("largeFileId")); put("partNumber", request["partNumber"]!!.jsonPrimitive.int)
                        put("contentLength", 1); put("contentSha1", sha1(text("range")!!.toByteArray()))
                    })
                    "b2_finish_large_file" -> {
                        val started = large.getValue(text("fileId")!!)
                        val bytes = parts.filter { it.name == started.id }.sortedBy { it.number }.fold(ByteArray(0)) { all, part -> all + part.body }
                        val finished = Stored(started.bucket, started.name, started.id, "upload", bytes, started.type, started.info, clock++)
                        files += finished
                        reply(exchange, version(finished, sha1 = "none"))
                    }
                    "b2_cancel_large_file" -> {
                        val started = large.remove(text("fileId")!!)!!
                        reply(exchange, buildJsonObject { put("fileId", started.id); put("bucketId", "id-${started.bucket}"); put("fileName", started.name) })
                    }
                    "b2_get_file_info" -> files.firstOrNull { it.id == text("fileId") }?.let { reply(exchange, version(it)) }
                        ?: refuse(exchange, 404, "not_found")
                    "b2_copy_file" -> {
                        val source = files.firstOrNull { it.id == text("sourceFileId") } ?: return refuse(exchange, 404, "not_found")
                        val copy = Stored(text("destinationBucketId")!!.removePrefix("id-"), text("fileName")!!, "id-${++ids}",
                            "upload", source.bytes, source.type, source.info, clock++)
                        files += copy
                        reply(exchange, version(copy))
                    }
                    "b2_delete_file_version" -> {
                        val gone = files.removeAll { it.id == text("fileId") && it.name == text("fileName") }
                        if (!gone) return refuse(exchange, 400, "file_not_present")
                        reply(exchange, buildJsonObject { put("fileId", text("fileId")); put("fileName", text("fileName")) })
                    }
                    "b2_hide_file" -> {
                        val marker = Stored(text("bucketId")!!.removePrefix("id-"), text("fileName")!!, "id-${++ids}",
                            "hide", ByteArray(0), null, emptyMap(), clock++)
                        files += marker
                        reply(exchange, version(marker))
                    }
                    else -> refuse(exchange, 400, "bad_request")
                }
            }
        }

        private fun authorize(exchange: HttpExchange, authorization: String) {
            val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("004keyid:$APPLICATION_KEY".toByteArray())
            if (authorization != expected) return refuse(exchange, 401, "bad_auth_token")
            val fresh = secret("account")
            synchronized(this) { token = fresh }
            reply(exchange, buildJsonObject {
                put("accountId", "acct"); put("authorizationToken", fresh); put("apiUrl", url)
                put("downloadUrl", downloadUrl ?: url); put("recommendedPartSize", 100_000_000); put("absoluteMinimumPartSize", 5_000_000)
                put("s3ApiUrl", "https://s3.example.invalid")
                putJsonObject("allowed") {
                    putJsonArray("capabilities") { listOf("listBuckets", "listFiles", "readFiles", "writeFiles", "deleteFiles").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    put("bucketId", restrictedTo?.let { "id-$it" }); put("bucketName", restrictedTo); put("namePrefix", null as String?)
                }
            })
        }

        private fun take(exchange: HttpExchange, raw: ByteArray, name: String, number: Int): Received? {
            val body = raw.copyOfRange(0, raw.size - 40)
            val trailer = raw.copyOfRange(raw.size - 40, raw.size).decodeToString()
            if (trailer != sha1(body)) { refuse(exchange, 400, "bad_request"); return null }
            val headers = exchange.requestHeaders.mapValues { it.value.first() }
            return Received(name, headers, body, trailer, number)
        }

        private fun upload(exchange: HttpExchange, raw: ByteArray) {
            val name = URLDecoder.decode(exchange.requestHeaders.getFirst("X-Bz-File-Name"), "UTF-8")
            val sent = take(exchange, raw, name, 0) ?: return
            uploads += sent
            val info = sent.headers.filterKeys { it.startsWith("X-bz-info-") }.mapKeys { it.key.removePrefix("X-bz-info-") }
            val bucket = exchange.requestURI.path.substringAfter("/upload/id-").substringBefore('/')
            val id = store(bucket, name, sent.body, sent.headers["Content-type"], info)
            reply(exchange, version(synchronized(this) { files.first { it.id == id } }))
        }

        private fun part(exchange: HttpExchange, largeId: String, raw: ByteArray) {
            val number = exchange.requestHeaders.getFirst("X-Bz-Part-Number").toInt()
            val sent = take(exchange, raw, largeId, number) ?: return
            parts += sent
            reply(exchange, buildJsonObject {
                put("fileId", largeId); put("partNumber", number); put("contentLength", sent.body.size); put("contentSha1", sent.trailer)
            })
        }

        private fun download(exchange: HttpExchange) {
            val id = exchange.requestURI.query.substringAfter("fileId=")
            val stored = synchronized(this) { files.firstOrNull { it.id == id } } ?: return refuse(exchange, 404, "not_found")
            val range = exchange.requestHeaders.getFirst("Range")?.takeUnless { ignoreRanges }?.removePrefix("bytes=")
            val from = range?.substringBefore('-')?.toInt() ?: 0
            val to = range?.substringAfter('-')?.toIntOrNull() ?: (stored.bytes.size - 1)
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $from-$to/${stored.bytes.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, (to - from + 1).toLong())
            exchange.responseBody.use { it.write(stored.bytes, from, to - from + 1) }
        }

        private fun version(stored: Stored, sha1: String? = null): JsonObject = buildJsonObject {
            put("accountId", "acct"); put("bucketId", "id-${stored.bucket}")
            put("fileId", stored.id); put("fileName", stored.name); put("action", stored.action)
            put("contentLength", stored.bytes.size); put("contentType", stored.type)
            put("contentSha1", sha1 ?: sha1(stored.bytes)); put("uploadTimestamp", stored.timestamp)
            putJsonObject("fileInfo") { stored.info.forEach { (key, value) -> put(key, value) } }
            put("somethingNew", "fields this SDK release has not heard of are ignored")
        }

        private fun refuse(exchange: HttpExchange, status: Int, code: String) =
            reply(exchange, buildJsonObject { put("status", status); put("code", code); put("message", "The fake says $code") }, status)

        private fun reply(exchange: HttpExchange, json: JsonObject, status: Int = 200) {
            val bytes = json.toString().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private companion object {
        const val APPLICATION_KEY = "K004applicationKeySecretValue"
        val BYTES = ByteArray(1_000) { (it * 7).toByte() }

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
