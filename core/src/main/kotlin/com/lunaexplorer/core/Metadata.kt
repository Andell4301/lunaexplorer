package com.lunaexplorer.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import java.io.File
import java.io.InputStream

data class MetadataTag(val name: String, val value: String)

// A damaged file can yield both tags and parse errors in the same directory.
data class MetadataSection(
    val name: String,
    val tags: List<MetadataTag>,
    val errors: List<String> = emptyList(),
)

data class MetadataReport(
    val sections: List<MetadataSection>,
    /** Read failure; null for a readable file with no metadata. */
    val failure: String? = null,
) {
    val tagCount: Int get() = sections.sumOf { it.tags.size }
    val isEmpty: Boolean get() = sections.isEmpty()
}

object FileMetadata {

    /** Prefer this over the stream overload: stream readers may buffer the whole file to seek. */
    fun read(file: File): MetadataReport = collect { ImageMetadataReader.readMetadata(file) }

    fun read(stream: InputStream): MetadataReport = collect { ImageMetadataReader.readMetadata(stream) }

    private inline fun collect(source: () -> Metadata): MetadataReport = try {
        val metadata = source()
        val sections = metadata.directories.map { directory ->
            MetadataSection(
                name = directory.name,
                tags = directory.tags.map { tag ->
                    val described = runCatching { tag.description }.getOrNull().orEmpty()
                    MetadataTag(tag.tagName, described.ifEmpty {
                        runCatching { directory.getString(tag.tagType) }.getOrNull().orEmpty()
                    })
                },
                errors = directory.errorsAsList(),
            )
        }.filter { it.tags.isNotEmpty() || it.errors.isNotEmpty() }
        MetadataReport(sections)
    } catch (error: Throwable) {
        MetadataReport(emptyList(), failure = error.message ?: error::class.java.simpleName)
    }

    private fun Directory.errorsAsList(): List<String> =
        if (!hasErrors()) emptyList() else errors.filterNot { it in BENIGN_ERRORS }

    /** The MP4 reader reports this end-of-stream diagnostic on valid files too. */
    private val BENIGN_ERRORS = setOf("End of data reached.")
}
