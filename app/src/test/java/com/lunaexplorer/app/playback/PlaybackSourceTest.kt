package com.lunaexplorer.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.ProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `internal playback reads storage without an exported content provider`() = runBlocking {
        val provider = MemoryStorageProvider("remote")
        val ref = provider.file(provider.root, "movie.mp4", "media bytes")
        val source = PlaybackSource.create(RuntimeEnvironment.getApplication(),
            ProviderRegistry(listOf(provider)), provider.stat(ref))
        val reader = source.dataSources.createDataSource()
        try {
            reader.open(DataSpec.Builder().setUri(source.uri).setPosition(6).build())
            val bytes = ByteArray(5)
            assertEquals(5, reader.read(bytes, 0, bytes.size))
            assertEquals("bytes", bytes.decodeToString())
            assertEquals(C.RESULT_END_OF_INPUT, reader.read(bytes, 0, bytes.size))
        } finally {
            reader.close()
        }
    }

    @Test fun `external subtitle files use their own source`() = runBlocking {
        val provider = MemoryStorageProvider("remote")
        val ref = provider.file(provider.root, "movie.mp4", "media bytes")
        val source = PlaybackSource.create(RuntimeEnvironment.getApplication(),
            ProviderRegistry(listOf(provider)), provider.stat(ref))
        val subtitle = temporary.newFile("captions.srt").apply { writeText("captions") }
        val reader = source.dataSources.createDataSource()
        try {
            assertEquals(8L, reader.open(DataSpec(Uri.fromFile(subtitle))))
            val bytes = ByteArray(8)
            assertEquals(8, reader.read(bytes, 0, bytes.size))
            assertEquals("captions", bytes.decodeToString())
        } finally {
            reader.close()
        }
    }
}
