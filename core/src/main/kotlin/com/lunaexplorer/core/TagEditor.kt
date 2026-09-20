package com.lunaexplorer.core

import com.mpatric.mp3agic.ID3v1Tag
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File

data class EditableTag(val key: String, val label: String, val value: String)

object TagEditor {

    private val FIELDS = listOf(
        "title" to "Title",
        "artist" to "Artist",
        "album" to "Album",
        "albumArtist" to "Album artist",
        "track" to "Track",
        "year" to "Year",
        "genre" to "Genre",
        "comment" to "Comment",
        "composer" to "Composer",
    )

    fun supports(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "mp3"

    fun read(file: File): List<EditableTag> = runCatching {
        val mp3 = Mp3File(file)
        val tag = mp3.id3v2Tag ?: mp3.id3v1Tag
        val values = mapOf(
            "title" to tag?.title,
            "artist" to tag?.artist,
            "album" to tag?.album,
            "albumArtist" to (mp3.id3v2Tag?.albumArtist),
            "track" to tag?.track,
            "year" to tag?.year,
            "genre" to tag?.genreDescription,
            "comment" to tag?.comment,
            "composer" to (mp3.id3v2Tag?.composer),
        )
        FIELDS.map { (key, label) -> EditableTag(key, label, values[key].orEmpty()) }
    }.getOrElse { FIELDS.map { (key, label) -> EditableTag(key, label, "") } }

    /**
     * Writes to a sibling file and renames it over [file]; if the rename fails, falls back to a
     * non-atomic copy. Unedited ID3v2 frames are kept.
     */
    fun write(file: File, values: Map<String, String>): Result<Unit> = runCatching {
        val mp3 = Mp3File(file)
        val tag = mp3.id3v2Tag ?: ID3v24Tag().also { mp3.id3v2Tag = it }
        values.forEach { (key, value) ->
            val text = value.trim()
            when (key) {
                "title" -> tag.title = text
                "artist" -> tag.artist = text
                "album" -> tag.album = text
                "albumArtist" -> tag.albumArtist = text
                "track" -> tag.track = text
                "year" -> tag.year = text
                "genre" -> runCatching { tag.genreDescription = text }
                "comment" -> tag.comment = text
                "composer" -> tag.composer = text
            }
        }
        // Keep an existing ID3v1 tag in step with the ID3v2 values.
        if (mp3.hasId3v1Tag()) {
            val legacy = mp3.id3v1Tag ?: ID3v1Tag()
            legacy.title = tag.title
            legacy.artist = tag.artist
            legacy.album = tag.album
            legacy.year = tag.year
            mp3.id3v1Tag = legacy
        }

        val staged = File(file.parentFile, ".luna-tag-${file.name}")
        try {
            mp3.save(staged.absolutePath)
            if (!staged.isFile || staged.length() == 0L) {
                throw StorageException(StorageError.IO, "The rewritten file came out empty")
            }
            val modified = file.lastModified()
            if (!staged.renameTo(file)) {
                staged.inputStream().use { source ->
                    file.outputStream().use { sink -> source.copyTo(sink) }
                }
                staged.delete()
            }
            file.setLastModified(modified)
        } finally {
            if (staged.exists()) staged.delete()
        }
    }
}
