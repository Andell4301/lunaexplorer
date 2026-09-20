package com.lunaexplorer.core

import java.security.MessageDigest
import java.security.Security
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Checksum
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** [value] is lowercase hexadecimal. */
data class Digest(val algorithm: String, val value: String)

data class DigestProgress(val bytesRead: Long, val totalBytes: Long?, val results: List<Digest>, val complete: Boolean)

class DigestEngine(
    private val registry: ProviderRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private val PREFERRED = listOf("CRC32", "ADLER32", "MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512")
    }

    fun available(): List<String> {
        val digests = Security.getAlgorithms("MessageDigest").map { it.uppercase() }
        return PREFERRED.filter { it == "CRC32" || it == "ADLER32" || it.uppercase() in digests }
    }

    fun compute(ref: NodeRef, algorithms: List<String> = available(), totalBytes: Long? = null): Flow<DigestProgress> = flow {
        val checksums = LinkedHashMap<String, Checksum>()
        val digests = LinkedHashMap<String, MessageDigest>()
        algorithms.forEach { name ->
            when (name) {
                "CRC32" -> checksums[name] = CRC32()
                "ADLER32" -> checksums[name] = Adler32()
                else -> runCatching { MessageDigest.getInstance(name) }.getOrNull()?.let { digests[name] = it }
            }
        }
        if (checksums.isEmpty() && digests.isEmpty()) {
            emit(DigestProgress(0, totalBytes, emptyList(), complete = true))
            return@flow
        }
        var read = 0L
        var lastEmit = 0L
        registry.provider(ref).openRead(ref).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                // update(byte[]) without an offset only became a Checksum default method at API 34.
                checksums.values.forEach { it.update(buffer, 0, count) }
                digests.values.forEach { it.update(buffer, 0, count) }
                read += count
                val now = System.nanoTime()
                if (now - lastEmit > 120_000_000L) {
                    lastEmit = now
                    emit(DigestProgress(read, totalBytes, emptyList(), complete = false))
                }
            }
        }
        val results = buildList {
            checksums.forEach { (name, checksum) -> add(Digest(name, "%08x".format(checksum.value))) }
            digests.forEach { (name, digest) -> add(Digest(name, digest.digest().joinToString("") { "%02x".format(it) })) }
        }.sortedBy { result -> PREFERRED.indexOf(result.algorithm).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        emit(DigestProgress(read, totalBytes ?: read, results, complete = true))
    }.flowOn(dispatcher)
}
