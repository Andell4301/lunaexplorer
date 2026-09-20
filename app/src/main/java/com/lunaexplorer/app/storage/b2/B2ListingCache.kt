package com.lunaexplorer.app.storage.b2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** One child of a saved listing. [fileId] is null for a folder. */
@Serializable
internal data class B2Listed(
    val name: String,
    val fileId: String? = null,
    val size: Long = 0,
    val modified: Long = 0,
    val contentType: String = "application/octet-stream",
) {
    val directory: Boolean get() = fileId == null
}

// Listings never expire; each file starts with its folder key followed by one child per line.
class B2ListingCache(private val directory: File, private val foldersInMemory: Int = 64) {
    @Serializable
    private data class Header(val key: String)

    private val memory = object : LinkedHashMap<String, List<B2Listed>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<B2Listed>>) = size > foldersInMemory
    }
    /** Key to file, read from the first line of each file the first time it is needed. */
    private var index: HashMap<String, File>? = null
    /** Counts forgets, so a listing that one overtook is recognised when it asks to be saved. */
    private var clock = 0L
    private val forgottenAt = HashMap<String, Long>()
    private val sweeps = ArrayDeque<Pair<String, Long>>()
    /** Forgets no longer remembered one by one; a listing begun before this is refused outright. */
    private var lostTrackAt = 0L

    @Synchronized internal fun now(): Long = clock

    @Synchronized internal fun read(key: String): List<B2Listed>? {
        memory[key]?.let { return it }
        val file = indexed()[key] ?: return null
        val listed = try {
            file.useLines { lines ->
                lines.drop(1).filter { it.isNotEmpty() }.map { json.decodeFromString(B2Listed.serializer(), it) }.toList()
            }
        } catch (_: Exception) {
            // A truncated or foreign file is only a cache miss.
            file.delete(); indexed().remove(key)
            return null
        }
        memory[key] = listed
        return listed
    }

    @Synchronized internal fun has(key: String): Boolean = memory.containsKey(key) || indexed().containsKey(key)

    /** [begun] is [now] as it was when the listing started. */
    @Synchronized internal fun write(key: String, children: List<B2Listed>, begun: Long = clock) {
        if (lostTrackAt > begun || (forgottenAt[key] ?: 0) > begun) return
        if (sweeps.any { (prefix, at) -> at > begun && key.startsWith(prefix) }) return
        memory[key] = children
        directory.mkdirs()
        val file = File(directory, nameFor(key))
        val staging = File(directory, file.name + ".writing")
        try {
            staging.bufferedWriter().use { out ->
                out.write(json.encodeToString(Header.serializer(), Header(key))); out.newLine()
                children.forEach { out.write(json.encodeToString(B2Listed.serializer(), it)); out.newLine() }
            }
            if (!staging.renameTo(file)) { staging.copyTo(file, overwrite = true); staging.delete() }
            indexed()[key] = file
        } catch (_: Exception) {
            // The disk copy is optional; the listing is still served from memory.
            staging.delete()
        }
    }

    @Synchronized internal fun forget(key: String) {
        if (forgottenAt.size >= REMEMBERED) { forgottenAt.clear(); lostTrackAt = clock + 1 }
        forgottenAt[key] = ++clock
        memory.remove(key)
        indexed().remove(key)?.delete()
    }

    @Synchronized internal fun forgetStartingWith(prefix: String) {
        sweeps.addLast(prefix to ++clock)
        if (sweeps.size > REMEMBERED) lostTrackAt = sweeps.removeFirst().second
        (memory.keys + indexed().keys).filter { it.startsWith(prefix) }.toSet().forEach(::forget)
    }

    @Synchronized internal fun clear() {
        lostTrackAt = ++clock
        forgottenAt.clear()
        sweeps.clear()
        memory.clear()
        index = HashMap()
        directory.listFiles()?.forEach { it.delete() }
    }

    @Synchronized internal fun bytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    private fun indexed(): HashMap<String, File> = index ?: HashMap<String, File>().also { found ->
        directory.listFiles { file -> file.name.endsWith(SUFFIX) }?.forEach { file ->
            val key = try {
                file.bufferedReader().use { it.readLine() }?.let { json.decodeFromString(Header.serializer(), it).key }
            } catch (_: Exception) { null }
            if (key == null) file.delete() else found[key] = file
        }
        index = found
    }

    private fun nameFor(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + SUFFIX

    private companion object {
        const val SUFFIX = ".list"
        const val REMEMBERED = 1024
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
