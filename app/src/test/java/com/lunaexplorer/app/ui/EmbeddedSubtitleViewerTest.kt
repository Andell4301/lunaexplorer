package com.lunaexplorer.app.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.lunaexplorer.app.playback.PlaybackSource
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EmbeddedSubtitleViewerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `failed extraction stays in the viewer and can be closed`() {
        val source = PlaybackSource(Uri.parse("luna-media://playback/invalid.mkv"),
            DataSource.Factory { ByteArrayDataSource(byteArrayOf(1, 2, 3)) })
        val identity = mediaTrackIdentity(Format.Builder().setId("2")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        var closed = false
        compose.setContent {
            MaterialTheme {
                EmbeddedSubtitleViewer(source, MediaTrack(2, "English", readableSubtitle = identity)) { closed = true }
            }
        }
        compose.waitUntil(10_000L) {
            compose.waitForIdle()
            compose.onAllNodesWithText("This video's subtitles could not be read").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun `metadata toggles without rereading the video and resets on reopening`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        var opens = 0
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"), DataSource.Factory {
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long { opens++; return delegate.open(dataSpec) }
            }
        })
        val identity = mediaTrackIdentity(Format.Builder().setId("2").setLabel("English").setLanguage("eng")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        var shown by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (shown) EmbeddedSubtitleViewer(source, MediaTrack(2, "English", readableSubtitle = identity)) { shown = false }
            }
        }
        awaitRows()
        val loaded = opens
        compose.onNodeWithText("Metadata").assertIsNotSelected().performClick()
        awaitRows()
        compose.onNodeWithText("Metadata").assertIsSelected()
        assertEquals(loaded, opens)
        compose.onNodeWithText("Metadata").performClick()
        awaitRows()
        compose.onNodeWithText("First caption").assertIsDisplayed()
        assertEquals(loaded, opens)
        compose.onNodeWithText("Metadata").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { shown = true }
        awaitRows()
        compose.onNodeWithText("Metadata").assertIsNotSelected()
    }

    @Test fun `embedded subtitles open as read only rows and close independently of the video`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/subtitles/embedded.mkv")).use { it.readBytes() }
        val source = PlaybackSource(Uri.parse("luna-media://playback/embedded.mkv"),
            DataSource.Factory { ByteArrayDataSource(bytes) })
        val identity = mediaTrackIdentity(Format.Builder().setId("2").setLabel("English").setLanguage("eng")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        var closed = false
        compose.setContent {
            MaterialTheme {
                EmbeddedSubtitleViewer(source, MediaTrack(2, "English", readableSubtitle = identity)) { closed = true }
            }
        }
        compose.waitUntil(10_000L) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("First caption").assertIsDisplayed()
        compose.onNodeWithTag("editorText").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save").assertDoesNotExist()
        compose.onNodeWithContentDescription("Find").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Subtitle text copied").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    private fun awaitRows() {
        compose.waitUntil(10_000L) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("editorRows").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
