package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipPlaybackCacheTest {
    private val uri = Uri.parse("luna-media://playback/movie.mkv")
    private val bytes = ByteArray(256) { it.toByte() }
    private var reads = 0
    private var opens = 0
    private var closes = 0
    private var failRead = false
    private val upstream = DataSource.Factory {
        val delegate = ByteArrayDataSource(bytes)
        object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long { opens++; return delegate.open(dataSpec) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (failRead) throw IOException("Disconnected")
                return delegate.read(buffer, offset, length).also { if (it > 0) reads += it }
            }
            override fun close() { closes++; delegate.close() }
        }
    }

    @Test fun `nearby frame seeks reuse source bytes across reader instances`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        assertArrayEquals(bytes.copyOfRange(35, 43), read(cache, 35, 8))
        val before = reads
        val opened = opens
        assertArrayEquals(bytes.copyOfRange(37, 45), read(cache, 37, 8))
        assertEquals(before, reads)
        assertEquals(opened, opens)
        assertEquals(opens, closes)
    }

    @Test fun `sequential cached reads preserve byte order across block boundaries and eof`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        assertArrayEquals(bytes, read(cache, 0, bytes.size))
        assertEquals(bytes.size, reads)
        assertEquals(1, opens)
        assertEquals(opens, closes)
        val reader = cache.createDataSource()
        try {
            assertEquals(0L, reader.open(DataSpec.Builder().setUri(uri).setPosition(bytes.size.toLong()).build()))
            assertEquals(C.RESULT_END_OF_INPUT, reader.read(ByteArray(1), 0, 1))
        } finally { reader.close() }
    }

    @Test fun `the cache evicts older blocks within its memory limit`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        read(cache, 0, 1)
        read(cache, 32, 1)
        read(cache, 64, 1)
        val before = reads
        assertArrayEquals(bytes.copyOfRange(0, 1), read(cache, 0, 1))
        assertEquals(before + 32, reads)
    }

    @Test fun `closing the editor drops cached blocks and restores direct reads`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        read(cache, 0, 8)
        cache.setEnabled(false)
        val before = reads
        assertArrayEquals(bytes.copyOfRange(0, 8), read(cache, 0, 8))
        assertEquals(before + 8, reads)
        cache.setEnabled(true)
        read(cache, 0, 8)
        assertEquals(before + 40, reads)
    }

    @Test fun `subtitle resources bypass the video cache`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        read(cache, 0, 8)
        val before = reads
        val subtitle = Uri.parse("file:///captions.srt")
        assertArrayEquals(bytes.copyOfRange(0, 8), read(cache, 0, 8, subtitle))
        assertEquals(before + 8, reads)
        assertEquals(opens, closes)
    }

    @Test fun `a failed read closes the source without caching partial data`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        failRead = true
        try {
            read(cache, 0, 8)
            fail("Expected a read failure")
        } catch (_: IOException) {
            assertEquals(opens, closes)
        }
        failRead = false
        assertArrayEquals(bytes.copyOfRange(0, 8), read(cache, 0, 8))
        assertEquals(32, reads)
    }

    @Test fun `a bounded request stops at its requested end even when more bytes are cached`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        read(cache, 0, 8)
        val reader = cache.createDataSource()
        try {
            assertEquals(3L, reader.open(DataSpec.Builder().setUri(uri).setPosition(4).setLength(3).build()))
            val target = ByteArray(8)
            assertEquals(3, reader.read(target, 0, target.size))
            assertArrayEquals(bytes.copyOfRange(4, 7), target.copyOf(3))
            assertEquals(C.RESULT_END_OF_INPUT, reader.read(target, 0, target.size))
        } finally { reader.close() }
    }

    @Test fun `a first bounded request does not truncate the cached source length`() {
        val cache = ClipPlaybackCache(uri, upstream, capacityBytes = 64, blockBytes = 32)
        cache.setEnabled(true)
        val reader = cache.createDataSource()
        try {
            assertEquals(3L, reader.open(DataSpec.Builder().setUri(uri).setLength(3).build()))
            val target = ByteArray(8)
            assertEquals(3, reader.read(target, 0, target.size))
            assertArrayEquals(bytes.copyOfRange(0, 3), target.copyOf(3))
            assertEquals(C.RESULT_END_OF_INPUT, reader.read(target, 0, target.size))
        } finally { reader.close() }

        assertArrayEquals(bytes.copyOfRange(4, 12), read(cache, 4, 8))
        assertEquals(opens, closes)
    }

    private fun read(cache: ClipPlaybackCache, position: Long, count: Int, source: Uri = uri): ByteArray {
        val reader = cache.createDataSource()
        return try {
            reader.open(DataSpec.Builder().setUri(source).setPosition(position).build())
            val output = ByteArray(count)
            var read = 0
            while (read < count) {
                val got = reader.read(output, read, count - read)
                assertTrue(got > 0)
                read += got
            }
            output
        } finally { reader.close() }
    }
}
