package com.lunaexplorer.app.storage

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.ServedFolder
import com.lunaexplorer.app.storage.shizuku.FakeShizuku
import com.lunaexplorer.app.storage.shizuku.HelperState
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class LunaDocumentsProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val resolver get() = context.contentResolver
    private lateinit var graph: AppGraph
    private lateinit var shared: File
    private lateinit var elsewhere: File
    private lateinit var controller: ContentProviderController<LunaDocumentsProvider>
    private val provider get() = controller.get()

    @Before fun serve() {
        val root = temporary.newFolder("root")
        shared = File(root, "shared").apply { mkdirs() }
        File(shared, "notes.txt").writeText("hello")
        File(shared, "photos").mkdirs()
        File(shared, "photos/one.jpg").writeBytes(ByteArray(3))
        elsewhere = File(root, "private").apply { mkdirs() }
        File(elsewhere, "secret.txt").writeText("no")
        graph = AppGraph(context, PathProbe.OF_FILESYSTEM)
        graph.additionalRoots = listOf(LocalRoot("test", "Test", root))
        runBlocking {
            graph.database.saveSession(BrowserState(preferences = Preferences(documentsProvider = true,
                servedFolders = listOf(ServedFolder(shared.absolutePath), ServedFolder("/nowhere/at/all")))))
        }
        LunaDocumentsProvider.graphOverride = { graph }
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        controller = Robolectric.buildContentProvider(LunaDocumentsProvider::class.java).create(info)
    }

    @After fun release() {
        LunaDocumentsProvider.graphOverride = null
        controller.shutdown()
        runCatching { graph.debugLog.close() }
    }

    private fun idOf(file: File, graph: AppGraph = this.graph): String =
        LunaDocumentsProvider.idOf(requireNotNull(graph.local.referenceTo(file.absolutePath)))
    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, id)
    private fun query(uri: Uri) = requireNotNull(resolver.query(uri, null, null as Bundle?, null))
    private fun roots() = query(DocumentsContract.buildRootsUri(AUTHORITY))

    private class Row(val id: String, val mime: String, val size: Long?, val flags: Int)
    private fun children(parentId: String): Map<String, Row> =
        query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId)).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val size = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
                    put(c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)), Row(
                        c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)),
                        c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)),
                        if (c.isNull(size)) null else c.getLong(size),
                        c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS)),
                    ))
                }
            }
        }

    @Test fun `the served folder is the one root, named by its last segment, and the unresolvable path is skipped`() {
        roots().use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("shared", c.getString(c.getColumnIndexOrThrow(Root.COLUMN_TITLE)))
            assertEquals(shared.absolutePath, c.getString(c.getColumnIndexOrThrow(Root.COLUMN_SUMMARY)))
            assertEquals(idOf(shared), c.getString(c.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)))
            val flags = c.getInt(c.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue("Tree pickers need ancestry", flags and Root.FLAG_SUPPORTS_IS_CHILD != 0)
            assertTrue("A writable folder takes new documents", flags and Root.FLAG_SUPPORTS_CREATE != 0)
            assertTrue("Files on this device are local", flags and Root.FLAG_LOCAL_ONLY != 0)
        }
    }

    @Test fun `a chosen name replaces the folder's own, and renaming keeps the root id grants are held against`() {
        val idBefore = roots().use { c ->
            assertTrue(c.moveToFirst())
            c.getString(c.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID))
        }

        runBlocking {
            graph.database.saveSession(BrowserState(preferences = Preferences(documentsProvider = true,
                servedFolders = listOf(ServedFolder(shared.absolutePath, "Work files"), ServedFolder("/nowhere/at/all")))))
        }
        LunaDocumentsProvider.rootsChanged(context)

        roots().use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("Work files", c.getString(c.getColumnIndexOrThrow(Root.COLUMN_TITLE)))
            assertEquals("The summary still says where it is", shared.absolutePath,
                c.getString(c.getColumnIndexOrThrow(Root.COLUMN_SUMMARY)))
            assertEquals("A rename must not orphan a grant another app already holds", idBefore,
                c.getString(c.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID)))
        }
    }

    @Test fun `a folder outside the served list is neither a root nor reachable by id`() {
        roots().use { c ->
            while (c.moveToNext()) {
                assertNotEquals(elsewhere.absolutePath, c.getString(c.getColumnIndexOrThrow(Root.COLUMN_SUMMARY)))
            }
        }
        val secret = idOf(File(elsewhere, "secret.txt"))
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument(secret, null) }
        assertThrows(FileNotFoundException::class.java) { provider.openDocument(secret, "r", null) }
        assertEquals("no", File(elsewhere, "secret.txt").readText())
    }

    @Test fun `children carry names, types and sizes`() {
        val rows = children(idOf(shared))
        assertEquals(setOf("notes.txt", "photos"), rows.keys)
        assertEquals("text/plain", rows.getValue("notes.txt").mime)
        assertEquals(5L, rows.getValue("notes.txt").size)
        assertEquals(idOf(File(shared, "notes.txt")), rows.getValue("notes.txt").id)
        assertEquals(Document.MIME_TYPE_DIR, rows.getValue("photos").mime)
        assertNull(rows.getValue("photos").size)
        assertTrue(rows.getValue("photos").flags and Document.FLAG_SUPPORTS_DELETE != 0)
    }

    @Test fun `a cancelled document open reports platform cancellation without changing the file`() {
        val file = File(shared, "notes.txt")
        val cancelled = CancellationSignal()
        graph.providers.register(object : StorageProvider by graph.local, PathAddressable by graph.local {
            override suspend fun stat(ref: NodeRef): Entry {
                cancelled.cancel()
                awaitCancellation()
            }
        })

        assertThrows(OperationCanceledException::class.java) {
            provider.openDocument(idOf(file), "wt", cancelled).close()
        }

        assertEquals("hello", file.readText())
    }

    @Test fun `a document is described on its own, with its type and what may be done to it`() {
        val id = idOf(File(shared, "notes.txt"))
        query(documentUri(id)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("notes.txt", c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            assertEquals(id, c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)))
            val flags = c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            assertTrue(flags and Document.FLAG_SUPPORTS_WRITE != 0)
            assertTrue(flags and Document.FLAG_SUPPORTS_DELETE != 0)
            assertTrue(flags and Document.FLAG_SUPPORTS_RENAME != 0)
        }
        assertEquals("text/plain", resolver.getType(documentUri(id)))
        query(documentUri(idOf(shared))).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS)) and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
        }
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument(idOf(File(shared, "missing.txt")), null) }
    }

    @Test
    @Config(shadows = [CapturedStreamProxy::class])
    fun `a file only the helper can open is not offered for writing, nor its folder for creating`() {
        val volume = temporary.newFolder("volume")
        val saves = File(volume, "Android/data/com.game/files").apply { mkdirs() }
        File(saves, "save.dat").writeText("progress")
        val shizuku = FakeShizuku().apply { start(permitted = true) }
        val assisted = AppGraph(context, PathProbe.OF_FILESYSTEM, shizukuGateway = shizuku)
        assisted.additionalRoots = listOf(LocalRoot("volume", "Volume", volume, RootKind.INTERNAL, followLinks = true))
        runBlocking {
            assisted.database.saveSession(BrowserState(preferences = Preferences(documentsProvider = true,
                servedFolders = listOf(ServedFolder(saves.canonicalPath)))))
        }
        assisted.shizuku.setEnabled(true)
        val deadline = System.nanoTime() + 5_000_000_000
        while (assisted.shizuku.state.value != HelperState.READY && System.nanoTime() < deadline) Thread.sleep(10)
        LunaDocumentsProvider.graphOverride = { assisted }
        try {
            fun flags(file: File) = query(documentUri(idOf(file, assisted))).use { c ->
                assertTrue(c.moveToFirst()); c.getInt(c.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            }
            assertEquals("A write is served by the file's own path, and this one has none to give",
                0, flags(File(saves, "save.dat")) and Document.FLAG_SUPPORTS_WRITE)
            assertEquals(0, flags(saves) and Document.FLAG_DIR_SUPPORTS_CREATE)
            val storage = Shadow.extract<CapturedStreamProxy>(context.getSystemService(StorageManager::class.java))
            provider.openDocument(idOf(File(saves, "save.dat"), assisted), "r", null).use { descriptor ->
                assertNull("Luna must not consume a proxy it serves itself", storage.callback)
                awaitPipeBytes(descriptor, "progress".length)
                assertEquals("progress", ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes().decodeToString())
            }
        } finally {
            runCatching { assisted.debugLog.close() }
        }
    }

    @Test fun `ancestry is answered by the provider and fails closed`() {
        assertTrue(provider.isChildDocument(idOf(shared), idOf(File(shared, "photos/one.jpg"))))
        assertFalse(provider.isChildDocument(idOf(File(shared, "photos")), idOf(File(shared, "notes.txt"))))
        assertFalse(provider.isChildDocument(idOf(shared), "nonsense"))
    }

    @Test fun `a tree grant reads inside its tree and nothing beside it`() {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, idOf(shared))
        val inside = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(File(shared, "photos/one.jpg")))
        assertEquals(3, requireNotNull(resolver.openInputStream(inside)).use { it.readBytes() }.size)
        val beside = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(File(elsewhere, "secret.txt")))
        assertThrows(SecurityException::class.java) { resolver.openInputStream(beside) }
    }

    @Test fun `a file on disk opens for reading and for writing, a folder for neither`() {
        val id = idOf(File(shared, "notes.txt"))
        assertEquals("hello", requireNotNull(resolver.openInputStream(documentUri(id))).use { it.readBytes().decodeToString() })
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, "w", null)).use { it.write("HOWDY".toByteArray()) }
        assertEquals("HOWDY", File(shared, "notes.txt").readText())
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, "wt", null)).use { it.write("hi".toByteArray()) }
        assertEquals("hi", File(shared, "notes.txt").readText())
        assertThrows(FileNotFoundException::class.java) { provider.openDocument(idOf(File(shared, "photos")), "r", null) }
    }

    @Test fun `create makes a file or a folder and tells watchers of the parent`() {
        val parent = documentUri(idOf(shared))
        val file = DocumentsContract.createDocument(resolver, parent, "text/plain", "new.txt")
        assertTrue(File(shared, "new.txt").isFile)
        assertEquals(idOf(File(shared, "new.txt")), DocumentsContract.getDocumentId(requireNotNull(file)))
        DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, "made")
        assertTrue(File(shared, "made").isDirectory)
        val watched = DocumentsContract.buildChildDocumentsUri(AUTHORITY, idOf(shared))
        assertTrue(shadowOf(resolver).notifiedUris.any { it.uri == watched })
    }

    @Test fun `delete removes an empty folder and refuses one that still has contents`() {
        File(shared, "empty").mkdirs()
        assertTrue(DocumentsContract.deleteDocument(resolver, documentUri(idOf(File(shared, "empty")))))
        assertFalse(File(shared, "empty").exists())
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument(idOf(File(shared, "photos"))) }
        assertTrue("Nothing inside was touched", File(shared, "photos/one.jpg").isFile)
    }

    @Test fun `rename changes the name and answers with the new id, or nothing when the id stays`() {
        val renamed = DocumentsContract.renameDocument(resolver, documentUri(idOf(File(shared, "notes.txt"))), "renamed.txt")
        assertEquals(idOf(File(shared, "renamed.txt")), DocumentsContract.getDocumentId(requireNotNull(renamed)))
        assertTrue(File(shared, "renamed.txt").isFile)
        assertFalse(File(shared, "notes.txt").exists())
        assertNull(provider.renameDocument(idOf(File(shared, "renamed.txt")), "renamed.txt"))
    }

    private companion object {
        const val AUTHORITY = "com.lunaexplorer.app.documents"
    }
}
