package com.lunaexplorer.app.storage.b2

import com.backblaze.b2.client.contentHandlers.B2ContentSink
import com.backblaze.b2.client.contentSources.B2Headers
import com.backblaze.b2.client.contentSources.B2HeadersImpl
import com.backblaze.b2.client.exceptions.B2ConnectFailedException
import com.backblaze.b2.client.exceptions.B2Exception
import com.backblaze.b2.client.exceptions.B2LocalException
import com.backblaze.b2.client.exceptions.B2NetworkException
import com.backblaze.b2.client.exceptions.B2NetworkTimeoutException
import com.backblaze.b2.client.structures.B2ErrorStructure
import com.backblaze.b2.client.webApiClients.B2WebApiClient
import com.backblaze.b2.json.B2Json
import com.backblaze.b2.json.B2JsonException
import com.backblaze.b2.json.B2JsonOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

// The SDK transport needs Apache HttpClient; this Android transport keeps credential-bearing URLs out of errors.
internal class B2HttpTransport(timeoutSeconds: Int, masterUrl: String? = null) : B2WebApiClient {
    private val timeoutMillis = timeoutSeconds.coerceIn(1, 3600) * 1000
    /** Plain HTTP is accepted only for a loopback master, which is how tests reach a fake server. */
    private val plainOrigin: String? = masterUrl?.let(::parsed)
        ?.takeIf { it.protocol == "http" && it.host in LOOPBACK }?.let(::origin)
    private val live = ConcurrentHashMap<HttpURLConnection, Thread>()
    @Volatile private var closed = false

    /** A 2xx response. Closing [body] before its end drops the connection instead of draining it. */
    class Reply(val status: Int, val headers: B2Headers, val body: InputStream)

    override fun <T> postJsonReturnJson(url: String, headers: B2Headers?, request: Any, responseClass: Class<T>): T {
        val bytes = try {
            B2Json.get().toJsonUtf8Bytes(request)
        } catch (error: B2JsonException) {
            throw B2LocalException("parsing_failed", "The request could not be written as JSON: ${error.message}", error)
        }
        return post(url, headers, ByteArrayInputStream(bytes), bytes.size.toLong(), "application/json", responseClass)
    }

    override fun <T> postDataReturnJson(
        url: String, headers: B2Headers?, inputStream: InputStream, contentLength: Long, responseClass: Class<T>,
    ): T = post(url, headers, inputStream, contentLength, "application/octet-stream", responseClass)

    override fun getContent(url: String, headers: B2Headers?, handler: B2ContentSink) {
        val reply = get(url, headers)
        try {
            handler.readContent(reply.headers, reply.body)
            // Reaching the end is what lets the connection be reused.
            reply.body.read()
        } catch (error: IOException) {
            throw broken(error, url)
        } finally {
            reply.body.close()
        }
    }

    override fun head(url: String, headers: B2Headers?): B2Headers {
        val connection = exchange("HEAD", url, headers, null, 0, null)
        return try { headersOf(connection) } finally { finish(connection) }
    }

    fun get(url: String, headers: B2Headers?): Reply {
        val connection = exchange("GET", url, headers, null, 0, null)
        return try {
            Reply(connection.responseCode, headersOf(connection), Body(connection, connection.inputStream, url))
        } catch (error: IOException) {
            drop(connection)
            throw broken(error, url)
        }
    }

    /** Breaks the requests [thread] is blocked in. An interrupt does not end a socket read or write. */
    fun abort(thread: Thread) {
        live.forEach { (connection, owner) -> if (owner === thread) drop(connection) }
    }

    override fun close() {
        closed = true
        live.keys.toList().forEach(::drop)
    }

    private fun <T> post(
        url: String, headers: B2Headers?, body: InputStream, length: Long, type: String, responseClass: Class<T>,
    ): T {
        val connection = exchange("POST", url, headers, body, length, type)
        val text = try {
            connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (error: IOException) {
            drop(connection)
            throw broken(error, url)
        }
        finish(connection)
        return try {
            B2Json.get().fromJson(text, responseClass, B2JsonOptions.DEFAULT_AND_ALLOW_EXTRA_FIELDS)
        } catch (error: B2JsonException) {
            throw B2LocalException("parsing_failed", "The answer from B2 could not be read: ${error.message}", error)
        }
    }

    /** Returns a connection holding a 2xx response, still registered in [live]. */
    private fun exchange(
        method: String, url: String, headers: B2Headers?, body: InputStream?, length: Long, type: String?,
    ): HttpURLConnection {
        if (closed) throw B2LocalException("closed", "The connection to B2 was closed")
        val target = checked(url)
        val connection = try {
            target.openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw neverSent(error, url)
        }
        live[connection] = Thread.currentThread()
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            // A followed redirect would turn a POST into a GET and could carry the token elsewhere.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            var typed = false
            headers?.names?.forEach { name ->
                // The length comes from the streaming mode. With Expect, the JDK reports a refusal
                // as a ProtocolException and its status is lost.
                if (name.equals(B2Headers.CONTENT_LENGTH, true) || name.equals(B2Headers.EXPECT, true)) return@forEach
                if (name.equals(B2Headers.CONTENT_TYPE, true)) typed = true
                connection.setRequestProperty(name, headers.getValueOrNull(name))
            }
            // Transparent gzip would make the body length differ from Content-Length.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (body != null) {
                if (!typed && type != null) connection.setRequestProperty(B2Headers.CONTENT_TYPE, type)
                connection.doOutput = true
                // A streamed body is also one the platform never resends by itself.
                connection.setFixedLengthStreamingMode(length)
            }
            try { connection.connect() } catch (error: IOException) { throw neverSent(error, url) }
            if (closed) throw B2LocalException("closed", "The connection to B2 was closed")
            if (body != null) send(body, connection, url)
            val status = try {
                connection.responseCode
            } catch (refused: HttpRetryException) {
                // The JDK refuses to read a 401 to a streamed request; only the status survives.
                throw B2Exception.create(NO_CODE, refused.responseCode(), null, "HTTP ${refused.responseCode()}")
            } catch (error: IOException) {
                throw broken(error, url)
            }
            if (status in 200..299) return connection
            throw refusal(connection, status)
        } catch (error: Exception) {
            drop(connection)
            throw error
        }
    }

    private fun send(body: InputStream, connection: HttpURLConnection, url: String) {
        val out = try { connection.outputStream } catch (error: IOException) { throw broken(error, url) }
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val got = try { body.read(buffer) } catch (error: IOException) {
                throw B2LocalException("read_failed", "The data to send could not be read: ${error.message}", error)
            }
            if (got < 0) break
            try { out.write(buffer, 0, got) } catch (error: IOException) { throw broken(error, url) }
        }
        try { out.close() } catch (error: IOException) { throw broken(error, url) }
    }

    private fun refusal(connection: HttpURLConnection, status: Int): B2Exception {
        val retryAfter = connection.getHeaderField(B2Headers.RETRY_AFTER)?.trim()?.toIntOrNull()
        val text = try {
            connection.errorStream?.use { stream ->
                val kept = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (kept.size() < ERROR_BYTES_KEPT) {
                    val got = stream.read(buffer)
                    if (got < 0) break
                    kept.write(buffer, 0, got)
                }
                kept.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: IOException) { null }
        // A null code would become the status's default one, and a bare 401 would read as "unauthorized".
        if (text.isNullOrBlank()) return B2Exception.create(NO_CODE, status, retryAfter, "HTTP $status")
        return try {
            val error = B2Json.get().fromJson(text, B2ErrorStructure::class.java, B2JsonOptions.DEFAULT_AND_ALLOW_EXTRA_FIELDS)
            B2Exception.create(error.code, error.status, retryAfter, error.message)
        } catch (_: Exception) {
            // An intermediary's HTML, not B2's JSON.
            B2Exception.create(NO_CODE, status, retryAfter, "HTTP $status")
        }
    }

    private fun headersOf(connection: HttpURLConnection): B2Headers {
        val seen = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        connection.headerFields.forEach { (name, values) ->
            if (name != null && values.isNotEmpty()) seen.putIfAbsent(name, values.first())
        }
        val builder = B2HeadersImpl.builder()
        seen.forEach { (name, value) -> builder.set(name, value) }
        return builder.build()
    }

    private fun finish(connection: HttpURLConnection) {
        live.remove(connection)
        runCatching { connection.inputStream.close() }
    }

    private fun drop(connection: HttpURLConnection) {
        live.remove(connection)
        runCatching { connection.disconnect() }
    }

    private fun checked(url: String): URL {
        val target = parsed(url) ?: throw B2LocalException("bad_url", "B2 gave an address that is not a URL")
        val allowed = target.protocol == "https" || (target.protocol == "http" && origin(target) == plainOrigin)
        if (!allowed) throw B2LocalException("bad_url", "Refused to send a request to ${origin(target)}")
        return target
    }

    private fun parsed(url: String): URL? = try { URL(url) } catch (_: MalformedURLException) { null }

    private fun origin(url: URL): String = "${url.protocol}://${url.host}:${if (url.port < 0) url.defaultPort else url.port}"

    /** Android reports an interrupt as this exception and clears the flag the retry policy reads. */
    private fun interrupted(error: IOException): B2Exception? {
        if (error !is InterruptedIOException || error is SocketTimeoutException) return null
        Thread.currentThread().interrupt()
        return B2LocalException("interrupted", "The request was interrupted", error)
    }

    private fun neverSent(error: IOException, url: String): B2Exception = interrupted(error) ?: when (error) {
        is UnknownHostException -> B2ConnectFailedException(UNKNOWN_HOST, null, "B2 could not be found by name", error.without(url))
        is SocketTimeoutException -> B2ConnectFailedException(CONNECT_TIMED_OUT, null, "B2 did not accept a connection in time", error.without(url))
        is SSLException -> B2ConnectFailedException(INSECURE, null, "A secure connection to B2 could not be made", error.without(url))
        else -> B2ConnectFailedException("connect_failed", null, "B2 could not be reached", error.without(url))
    }

    private fun broken(error: IOException, url: String): B2Exception = interrupted(error) ?: when {
        closed -> B2LocalException("closed", "The connection to B2 was closed", error.without(url))
        error is SocketTimeoutException -> B2NetworkTimeoutException("socket_timeout", null, "B2 did not answer in time", error.without(url))
        else -> B2NetworkException("io_exception", null, "The connection to B2 was lost", error.without(url))
    }

    /** Platform messages such as "Server returned HTTP response code: 500 for URL: …" quote the URL. */
    private fun IOException.without(url: String): IOException {
        val said = message ?: return this
        val path = parsed(url)?.path.orEmpty()
        return if (said.contains(url) || (path.length > 1 && said.contains(path))) IOException(javaClass.name) else this
    }

    private inner class Body(
        private val connection: HttpURLConnection,
        private val stream: InputStream,
        private val url: String,
    ) : InputStream() {
        private var ended = false
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val got = try { stream.read(b, off, len) } catch (error: IOException) {
                val failure = broken(error, url)
                close()
                throw IOException(failure.message, failure)
            }
            if (got < 0) ended = true
            return got
        }

        override fun close() {
            if (done) return
            done = true
            if (ended) finish(connection) else drop(connection)
        }
    }

    companion object {
        const val UNKNOWN_HOST = "unknown_host"
        const val CONNECT_TIMED_OUT = "connect_timed_out"
        const val INSECURE = "ssl"
        /** The response had no body to take B2's code from. */
        const val NO_CODE = "unknown"
        private const val ERROR_BYTES_KEPT = 64 * 1024
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")
    }
}
