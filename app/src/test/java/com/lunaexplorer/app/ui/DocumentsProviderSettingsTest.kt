package com.lunaexplorer.app.ui

import android.content.pm.PackageManager
import android.provider.DocumentsContract
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.ServedFolder
import com.lunaexplorer.app.storage.LunaDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class DocumentsProviderSettingsTest {
    private var chosen = ""

    @get:Rule val harness = BrowserViewModelHarness()
        .startingWith { dir -> chosen = File(dir, "served").apply { mkdirs() }.absolutePath }
        .withSession { it.copy(preferences = it.preferences.copy(documentsProvider = true, servedFolders = listOf(ServedFolder(chosen)))) }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val packageManager get() = harness.application.packageManager
    private val component get() = LunaDocumentsProvider.component(harness.application)
    private fun componentState() = packageManager.getComponentEnabledSetting(component)
    private fun savedFolders() = runBlocking { harness.graph.database.loadSession() }?.preferences?.servedFolders

    @Test fun `a restored setting enables the component, and the switch or a reset disables it, without killing the app`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue("Restoring the session is enough; nobody touched settings",
            harness.awaitUntil { componentState() == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
        assertEquals(PackageManager.DONT_KILL_APP, shadowOf(packageManager).getComponentEnabledSettingFlags(component))

        viewModel.setPreferences(state.preferences.copy(documentsProvider = false))
        assertTrue(harness.awaitUntil { componentState() == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
        viewModel.setPreferences(state.preferences.copy(documentsProvider = true))
        assertTrue(harness.awaitUntil { componentState() == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })

        viewModel.resetSettings()
        assertTrue(harness.awaitUntil { componentState() == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
        assertFalse(state.preferences.documentsProvider)
        assertTrue(state.preferences.servedFolders.isEmpty())
    }

    @Test fun `pickers are told about a changed served list once it is saved, and not about other changes`() {
        assertTrue(harness.awaitUntil { state.ready })
        val roots = DocumentsContract.buildRootsUri(LunaDocumentsProvider.authority(harness.application))
        val resolver = shadowOf(harness.application.contentResolver)
        fun notices() = resolver.notifiedUris.count { it.uri == roots }
        assertTrue(harness.awaitUntil { savedFolders() == listOf(ServedFolder(chosen)) })
        val before = notices()

        val added = File(harness.directory, "more").apply { mkdirs() }.absolutePath
        viewModel.setPreferences(state.preferences.copy(servedFolders = state.preferences.servedFolders + ServedFolder(added)))
        assertTrue(harness.awaitUntil { notices() > before })
        assertEquals("What the provider reads is already there when pickers are told",
            listOf(ServedFolder(chosen), ServedFolder(added)), savedFolders())

        val after = notices()
        viewModel.setPreferences(state.preferences.copy(showHidden = true))
        assertTrue(harness.awaitUntil { runBlocking { harness.graph.database.loadSession() }?.preferences?.showHidden == true })
        assertEquals("A change elsewhere in settings does not disturb the pickers", after, notices())
    }
}
