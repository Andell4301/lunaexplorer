package org.apache.commons.compress.archivers.sevenz

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class SevenZDictionariesTest {
    private fun opened(): Pair<SevenZFile, Folder> {
        val written = SeekableInMemoryByteChannel()
        SevenZOutputFile(written).use { out ->
            out.putArchiveEntry(SevenZArchiveEntry().apply { name = "a.txt" })
            out.write("alpha".toByteArray())
            out.closeArchiveEntry()
            out.finish()
        }
        val file = SevenZFile.builder()
            .setSeekableByteChannel(SeekableInMemoryByteChannel(written.array().copyOf(written.size().toInt()))).get()
        val archive = SevenZFile::class.java.getDeclaredField("archive").apply { isAccessible = true }.get(file) as Archive
        return file to archive.folders.single()
    }

    @Test fun `a block of four gigabytes or more keeps its declared dictionary`() {
        val (file, folder) = opened()
        file.use {
            val declared = folder.coders.single().properties[0]
            folder.unpackSizes[0] = 1L shl 33
            fitDictionariesToContent(it)
            assertEquals(declared, folder.coders.single().properties[0])
        }
    }

    @Test fun `a folder whose sizes do not line up with its coders is left alone`() {
        val (file, folder) = opened()
        file.use {
            val declared = folder.coders.single().properties[0]
            folder.unpackSizes = LongArray(0)
            fitDictionariesToContent(it)
            assertEquals(declared, folder.coders.single().properties[0])
        }
    }
}
