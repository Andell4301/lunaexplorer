package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.storage.InstalledApp
import com.lunaexplorer.core.BinaryXmlFixture
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class InstalledAppActionsTest {
    @get:Rule val harness = BrowserViewModelHarness()

    private fun installed(): InstalledApp {
        // Outside the served harness root, like /data/app is outside shared storage.
        val directory = File(harness.directory.parentFile, "installed 日本語\u200e").apply { mkdirs() }
        val apk = File(directory, "base.apk")
        ZipOutputStream(apk.outputStream()).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml"))
            it.write(BinaryXmlFixture.manifest(utf8 = true))
            it.closeEntry()
        }
        File(directory, "split_config.en.apk").writeBytes(byteArrayOf(1))
        return InstalledApp("test.installed", "Installed fixture", "1", 1, false, true, false,
            26, 36, 0, 0, null, 10001, null, null, apk.absolutePath,
            listOf(File(directory, "split_config.en.apk").absolutePath), apk.length(),
            emptyList(), emptySet(), emptyList())
    }

    @Test fun `installed manifest opens while device root is hidden`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready })
        assertFalse(harness.state.preferences.showDeviceRoot)
        val app = installed()
        val text = harness.viewModel.packages.manifestOf(app)
        assertTrue(text.contains("<manifest"))
        assertFalse("Reading a manifest does not change the roots setting", harness.state.preferences.showDeviceRoot)
    }

    @Test fun `explore opens the exact installation directory and reveals split files`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        val app = installed()
        val parent = requireNotNull(File(requireNotNull(app.sourceDir)).parent)
        harness.viewModel.showScreen(Screen.APPS)
        harness.viewModel.exploreAppFiles(app)
        assertTrue(harness.awaitUntil {
            harness.state.screen == Screen.BROWSER && harness.state.directoryPath == parent && !harness.state.loading
        })
        assertTrue(harness.state.preferences.showDeviceRoot)
        assertEquals(setOf("base.apk", "split_config.en.apk"), harness.state.entries.map { it.name }.toSet())
        harness.viewModel.back()
        assertEquals(Screen.APPS, harness.state.screen)
    }
}
