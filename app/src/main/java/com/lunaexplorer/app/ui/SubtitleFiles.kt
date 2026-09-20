package com.lunaexplorer.app.ui

import java.io.File
import java.io.InputStream
import java.util.Locale

internal const val MAX_SUBTITLE_BYTES = 24L * 1024 * 1024
private val SUBTITLE_MIME_TYPES = mapOf(
    "srt" to "application/x-subrip", "ass" to "text/x-ssa", "ssa" to "text/x-ssa",
    "vtt" to "text/vtt", "ttml" to "application/ttml+xml", "dfxp" to "application/ttml+xml",
)

internal fun subtitleMimeType(name: String): String? =
    SUBTITLE_MIME_TYPES[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]

internal fun isSubtitleFile(name: String): Boolean = subtitleMimeType(name) != null

/** Copies the bytes verbatim: ASS styles, encoding and BOM must survive, so never rewrite as text. */
internal fun copySubtitle(input: InputStream, name: String, directory: File, limit: Long = MAX_SUBTITLE_BYTES): File {
    require(isSubtitleFile(name)) { "Choose an SRT, ASS, SSA, WebVTT or TTML subtitle file" }
    check(directory.isDirectory || directory.mkdirs()) { "Subtitle cache could not be created" }
    // Only the extension of [name] is used; the generated cache name cannot escape the directory.
    val file = File.createTempFile("subtitle-", ".${name.substringAfterLast('.').lowercase(Locale.ROOT)}", directory)
    try {
        file.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "This subtitle file is too large to open here" }
                output.write(buffer, 0, read)
            }
        }
        return file
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}
