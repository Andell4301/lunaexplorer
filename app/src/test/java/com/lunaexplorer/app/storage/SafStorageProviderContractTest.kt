package com.lunaexplorer.app.storage

import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageProviderContract
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafStorageProviderContractTest : StorageProviderContract() {
    @get:Rule val temporary = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    override lateinit var provider: SafStorageProvider
    private lateinit var tree: Uri
    private var counter = 0

    @Before fun grant() {
        FakeDocumentsProvider.base = temporary.root
        FakeDocumentsProvider.reset()
        val info = ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(info)
        tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT)
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        provider = SafStorageProvider(context)
    }

    override suspend fun freshRoot(): NodeRef =
        provider.create(provider.rootForTree(tree).ref, "root${counter++}", directory = true).ref

    private fun idOf(ref: NodeRef) = DocumentsContract.getDocumentId(Uri.parse(ref.key))
    private suspend fun names(ref: NodeRef, complete: Boolean = false) = provider.list(ref, complete).toList().flatten().map { it.name }

    @Test fun `a rename that changes the document id yields the reference a listing gives, and retires the old one`() = runBlocking {
        val root = freshRoot()
        val before = provider.create(root, "a.txt", directory = false)
        val after = provider.rename(before.ref, "b.txt")
        assertNotEquals(idOf(before.ref), idOf(after.ref))
        assertEquals(after.ref, provider.list(root).toList().flatten().single().ref)
        assertEquals(root, provider.parentOf(after.ref))
        expect(StorageError.NOT_FOUND) { provider.stat(before.ref) }
    }

    @Test fun `a folder still loading is streamed, then completed when the provider notifies`() = runBlocking {
        val root = freshRoot()
        listOf("one", "two", "three").forEach { provider.create(root, it, directory = false) }
        FakeDocumentsProvider.stillLoading += idOf(root)
        assertEquals("Everything arrives once the provider has notified", setOf("one", "two", "three"), names(root).toSet())
        FakeDocumentsProvider.reset(); FakeDocumentsProvider.stillLoading += idOf(root)
        assertEquals("A whole-folder caller gets the whole folder", setOf("one", "two", "three"), names(root, complete = true).toSet())
    }

    @Test fun `a provider that never settles is bounded, and cannot answer for a whole folder`() = runBlocking {
        val root = freshRoot()
        listOf("one", "two", "three").forEach { provider.create(root, it, directory = false) }
        FakeDocumentsProvider.neverSettles += idOf(root)
        assertEquals("What it did serve is shown rather than nothing", listOf("one"), names(root))
        expect(StorageError.IO) { names(root, complete = true) }
    }

    @Test fun `a released grant is a permission failure, not a disconnection`() = runBlocking {
        val root = freshRoot()
        provider.forgetRoot(provider.rootForTree(tree))
        expect(StorageError.PERMISSION) { provider.stat(root) }
        assertTrue(provider.roots().isEmpty())
    }

    @Test fun `relocate moves within the provider in one step`() = runBlocking {
        val root = freshRoot()
        val from = provider.create(root, "from", directory = true).ref
        val to = provider.create(root, "to", directory = true).ref
        val file = provider.create(from, "moving.txt", directory = false).ref
        provider.openWrite(file).use { it.write("payload".toByteArray()) }
        val moved = requireNotNull(provider.relocate(file, to, "moved.txt"))
        assertEquals("moved.txt", moved.name)
        assertEquals(to, provider.parentOf(moved.ref))
        assertEquals("payload", provider.openRead(moved.ref).use { it.readBytes().decodeToString() })
        assertTrue(names(from).isEmpty())
    }
}
