package com.lunaexplorer.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.FakeDocumentsProvider
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class EditorSaveTest {
    @get:Rule val harness = BrowserViewModelHarness()

    @Test fun `a document provider may add a MIME extension while staging an editor save`() {
        assertTrue(harness.awaitUntil { harness.state.ready })
        FakeDocumentsProvider.base = harness.directory
        FakeDocumentsProvider.reset()
        val info = ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(info)
        val tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT)
        val provider = harness.graph.saf
        val parent = provider.rootForTree(tree).ref
        val original = runBlocking {
            provider.takeGrant(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            provider.create(parent, "notes.txt", directory = false, mimeType = "text/plain").also {
                provider.openWrite(it.ref).use { output -> output.write("Original".toByteArray()) }
            }.copy(mimeType = "text/plain")
        }

        FakeDocumentsProvider.appendTextExtension = true
        val draft = "Saved through SAF — 日本語 🌓\n"
        var result: Result<Entry>? = null
        harness.viewModel.files.writeText(original, draft) { result = it }
        assertTrue("Save completed", harness.awaitUntil { result != null })
        val saved = requireNotNull(result).getOrThrow()
        assertEquals("notes.txt", saved.name)
        assertEquals(draft, harness.directory.resolve("notes.txt").readText())
        assertEquals(listOf("notes.txt"), harness.directory.listFiles().orEmpty().map { it.name })
        runBlocking {
            assertEquals(parent, provider.parentOf(saved.ref))
            assertEquals(draft, provider.openRead(saved.ref).use { it.readBytes().decodeToString() })
        }
    }
}
