package org.apache.commons.compress.archivers.sevenz

private const val LZMA_DICTIONARY_MIN = 4096L
private const val LZMA2_DICTIONARY_BITS_MAX = 40

/** The parsed header is private to SevenZFile, and the structures under it are private to this package. */
private val archiveField = runCatching {
    SevenZFile::class.java.getDeclaredField("archive").apply { isAccessible = true }
}.getOrNull()

/**
 * Lowers each LZMA and LZMA2 coder's declared dictionary to the smallest that still holds the whole
 * of its output. commons-compress checks and allocates the declared size, up to 4 GiB at strong
 * settings, however little the block holds; a match cannot reach further back than what was
 * produced, so the smaller dictionary decodes the same bytes. It only ever helps: a header it does
 * not understand, or a library laid out differently, is left exactly as the library read it.
 */
internal fun fitDictionariesToContent(file: SevenZFile) {
    val archive = runCatching { archiveField?.get(file) as? Archive }.getOrNull() ?: return
    for (folder in archive.folders.orEmpty()) {
        try {
            val coders = folder.coders ?: continue
            // Sizes are looked up by coder position, which only lines up when every coder has one output.
            if (folder.unpackSizes?.size != coders.size) continue
            for (coder in coders) fit(coder, folder.getUnpackSizeForCoder(coder))
        } catch (_: RuntimeException) {
        }
    }
}

private fun fit(coder: Coder, produced: Long) {
    val properties = coder.properties ?: return
    if (produced <= 0) return
    val method = coder.decompressionMethodId
    if (method.contentEquals(SevenZMethod.LZMA2.id) && properties.isNotEmpty()) {
        val declared = properties[0].toInt() and 0xff
        if (declared > LZMA2_DICTIONARY_BITS_MAX) return
        // No size covers a block of 4 GiB or more, which keeps the dictionary it declared.
        val needed = (0..LZMA2_DICTIONARY_BITS_MAX).firstOrNull { lzma2DictionarySize(it) >= produced } ?: return
        if (needed < declared) properties[0] = needed.toByte()
    } else if (method.contentEquals(SevenZMethod.LZMA.id) && properties.size >= 5) {
        // Little-endian unsigned 32-bit size after the lc/lp/pb byte.
        val declared = (1..4).fold(0L) { size, i -> size or ((properties[i].toLong() and 0xff) shl (8 * (i - 1))) }
        val needed = maxOf(produced, LZMA_DICTIONARY_MIN)
        if (needed < declared) for (i in 1..4) properties[i] = (needed shr (8 * (i - 1))).toByte()
    }
}

/** The size an LZMA2 property byte stands for: 2 or 3 shifted by half the value, and all ones at the top. */
private fun lzma2DictionarySize(bits: Int): Long =
    if (bits == LZMA2_DICTIONARY_BITS_MAX) 0xFFFF_FFFFL else (2L or (bits.toLong() and 1L)) shl (bits / 2 + 11)
