package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.NetworkThumbnails
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class TransferSettingsUiTest : RobolectricBrowserUiTest() {
    @Test fun ftpAndSftpHaveSeparateServerPagesAndThumbnailSettings() {
        awaitListing()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        openSettingsPage("Network")
        compose.onNodeWithText("FTP").performScrollTo().performClick()
        compose.onNodeWithText("Add an FTP server").assertExists()
        compose.onNodeWithText("Add an SFTP server").assertDoesNotExist()
        compose.onNodeWithText(NetworkThumbnails.OFF.label).performScrollTo().performClick()
        compose.waitUntil(10_000) { viewModel.state.value.preferences.thumbnailsOn("ftp") == NetworkThumbnails.OFF }

        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("SFTP").performScrollTo().performClick()

        compose.onNodeWithText("Add an SFTP server").assertExists()
        compose.onNodeWithText("Add an FTP server").assertDoesNotExist()
        compose.onNodeWithText(NetworkThumbnails.MB_25.label).performScrollTo().assertIsSelected()
        assertEquals(NetworkThumbnails.MB_25, viewModel.state.value.preferences.thumbnailsOn("sftp"))
    }
}
