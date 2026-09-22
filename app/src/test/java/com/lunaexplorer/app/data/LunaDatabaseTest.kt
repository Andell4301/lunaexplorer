package com.lunaexplorer.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.app.model.Bookmark
import com.lunaexplorer.app.model.Destination
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.BrowserTab
import com.lunaexplorer.app.model.Crumb
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.SortOrder
import com.lunaexplorer.app.model.ViewMode
import com.lunaexplorer.app.model.ThemeMode
import com.lunaexplorer.core.ConflictPolicy
import com.lunaexplorer.core.TrashedItem
import com.lunaexplorer.core.ItemOutcome
import com.lunaexplorer.core.ItemStatus
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProcedureLocation
import com.lunaexplorer.core.ProcedureSource
import com.lunaexplorer.core.ProcedureStep
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LunaDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: LunaDatabase
    private lateinit var databaseName: String
    private val source = NodeRef("test", "opaque-source")
    private val destination = NodeRef("test", "opaque-destination")

    @Before fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "luna-test-${UUID.randomUUID()}.db"
        database = LunaDatabase(context, databaseName)
    }

    @After fun removeDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun operation(type: OperationType = OperationType.COPY) =
        OperationRequest(type = type, sources = listOf(source), destination = destination)

    @Test fun anUnsupportedDatabaseSchemaIsRefusedWithoutDeletingItsContents() = runBlocking {
        val request = operation()
        database.enqueue(request, "Queued copy")
        database.writableDatabase.version = 3
        database.close()
        database = LunaDatabase(context, databaseName)

        assertThrows(SQLiteException::class.java) { database.readableDatabase }

        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { saved ->
            saved.rawQuery("SELECT id FROM operations", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(request.id, it.getString(0))
            }
            assertEquals(3, saved.version)
        }
    }

    @Test fun `upgrading a version one database preserves session queue journal and defaults`() = runBlocking {
        val request = operation()
        val state = BrowserState(preferences = Preferences(theme = ThemeMode.DARK))
        database.close()
        object : SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', bytes INTEGER NOT NULL DEFAULT 0, current_name TEXT NOT NULL DEFAULT '', conflicts TEXT NOT NULL DEFAULT '[]', cancelled INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE INDEX operation_order ON operations(status, created)")
                db.execSQL("CREATE TABLE operation_events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL REFERENCES operations(id), message TEXT NOT NULL, artifacts TEXT NOT NULL DEFAULT '[]', source TEXT, destination TEXT)")
                db.execSQL("CREATE INDEX event_order ON operation_events(operation_id, sequence)")
                db.execSQL("CREATE TABLE trash (id TEXT PRIMARY KEY, operation_id TEXT NOT NULL, state TEXT NOT NULL, name TEXT NOT NULL, ref TEXT NOT NULL, source_ref TEXT NOT NULL, original_parent TEXT NOT NULL, original_name TEXT NOT NULL, original_path TEXT, directory INTEGER NOT NULL, size INTEGER, deleted_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE open_defaults (\"key\" TEXT PRIMARY KEY, target TEXT NOT NULL, mime TEXT NOT NULL DEFAULT '', label TEXT NOT NULL DEFAULT '')")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
        }.use { legacy ->
            legacy.writableDatabase.apply {
                execSQL("INSERT INTO session(id,payload) VALUES(1,?)", arrayOf(SessionCodec.encode(state)))
                execSQL("INSERT INTO operations(id,created,payload,title,status) VALUES(?,1,?,'Copy','QUEUED')",
                    arrayOf(request.id, SessionCodec.json.encodeToString(request)))
                execSQL("INSERT INTO operation_events(operation_id,message) VALUES(?,'Staged safely')", arrayOf(request.id))
                execSQL("INSERT INTO open_defaults(\"key\",target) VALUES('ext:txt','editor')")
            }
        }

        database = LunaDatabase(context, databaseName)
        assertEquals(ThemeMode.DARK, database.loadSession()!!.preferences.theme)
        assertEquals(request, database.request(request.id))
        database.refreshQueue()
        assertEquals(listOf("Staged safely"), database.queue.value.single().results)
        assertEquals("editor", database.openDefault(listOf("ext:txt"))!!.target)
        val procedure = OperationRequest(type = OperationType.PROCEDURE, procedureId = "clean",
            steps = listOf(ProcedureStep(OperationType.DELETE, listOf(ProcedureSource(ProcedureLocation(source))))))
        assertTrue(database.enqueueProcedure(procedure, "Clean"))
        assertEquals(request, database.claimNext())
        database.finish(request.id, "SUCCEEDED", "Completed")
        assertEquals(procedure, database.claimNext())
    }

    @Test fun anItemOnlyEntersTheBinWhenItsMoveActuallySucceeded() = runBlocking {
        val moved = NodeRef("test", "photo.jpg")
        val stuck = NodeRef("test", "locked.jpg")
        val landed = NodeRef("test", "bin/photo.jpg")
        val request = operation().copy(sources = listOf(moved, stuck))
        database.enqueue(request, "Recycle 2 items")
        database.stageTrash(request.id, listOf(trashed("a", moved), trashed("b", stuck)))
        assertTrue("A staged row is not yet recoverable", database.trash.value.isEmpty())

        database.reconcileTrash(request.id, listOf(
            ItemOutcome(moved, landed, ItemStatus.SUCCESS, "Moved"),
            ItemOutcome(stuck, null, ItemStatus.FAILED, "Refused"),
        ))
        reopen()
        database.refreshTrash()
        val held = database.trash.value.single()
        assertEquals("photo.jpg", held.originalName)
        assertEquals("The bin records where the item actually went", landed, held.ref)
        assertEquals(destination, held.originalParent)
    }

    @Test fun leavingTheBinRemovesTheRecordWhicheverWayItLeaves() = runBlocking {
        val binned = NodeRef("test", "bin/notes.txt")
        val restored = NodeRef("test", "home/notes.txt")
        val first = operation()
        database.enqueue(first, "Recycle")
        database.stageTrash(first.id, listOf(trashed("a", NodeRef("test", "home/notes.txt"))))
        database.reconcileTrash(first.id, listOf(
            ItemOutcome(NodeRef("test", "home/notes.txt"), binned, ItemStatus.SUCCESS, "Moved"),
        ))
        assertEquals(1, database.trash.value.size)

        val second = operation()
        database.enqueue(second, "Restore")
        database.reconcileTrash(second.id, listOf(ItemOutcome(binned, restored, ItemStatus.SUCCESS, "Moved")))
        assertTrue("A restored item is no longer in the bin", database.trash.value.isEmpty())
    }

    @Test fun anInterruptedRecycleLeavesNoPhantomBinEntry() = runBlocking {
        val source = NodeRef("test", "video.mkv")
        val request = operation()
        database.enqueue(request, "Recycle")
        database.stageTrash(request.id, listOf(trashed("a", source)))
        // The worker reconciles with no outcomes when it is cancelled or dies.
        database.reconcileTrash(request.id, emptyList())
        reopen()
        database.refreshTrash()
        assertTrue("Nothing moved, so nothing is claimed to be recoverable", database.trash.value.isEmpty())
    }

    private fun trashed(id: String, ref: NodeRef) = TrashedItem(
        id = id, name = ref.key.substringAfterLast('/'), ref = ref, originalParent = destination,
        originalName = ref.key.substringAfterLast('/'), originalPath = "/storage/${ref.key}",
        directory = false, size = 10, deletedAt = 1_700_000_000_000,
    )

    private fun conflicted(ref: NodeRef, directory: Boolean = false, name: String = ref.key.substringAfterLast('/')) =
        ItemOutcome(ref, status = ItemStatus.CONFLICT, message = "Name collision",
            sourceDirectory = directory, conflictName = name)

    private fun reopen() {
        database.close()
        database = LunaDatabase(context, databaseName)
    }

    @Test fun boundedPreviewRetainsCompleteExportableJournalAfterReopen() = runBlocking {
        val request = operation()
        database.enqueue(request, "Large copy")
        repeat(205) { index ->
            database.journal(request.id, "Event $index", emptyList(), "Completed item $index", source, destination)
        }
        database.finish(request.id, "SUCCEEDED", "205 completed items")
        reopen()
        database.refreshQueue()
        val preview = database.queue.value.single().results
        assertEquals(101, preview.size) // Truncation explanation plus the most recent hundred events.
        assertTrue(preview.first().contains("205"))
        assertFalse(preview.contains("Completed item 0"))
        val report = File(context.cacheDir, "report-${UUID.randomUUID()}.txt")
        try {
            database.exportReport(request.id, report)
            val text = report.readText()
            assertTrue(text.contains("Completed item 0\n"))
            assertTrue(text.contains("Completed item 204\n"))
            assertTrue(text.contains("opaque-source"))
            assertTrue(text.contains("opaque-destination"))
        } finally { report.delete() }
    }

    @Test fun queuedRequestSurvivesDatabaseReopenAndClaimIsExclusive() = runBlocking {
        val request = operation().copy(name = "report.txt", conflictPolicy = ConflictPolicy.KEEP_BOTH)
        database.enqueue(request, "Copy report")
        reopen()
        assertTrue(database.hasQueued())
        assertEquals(request, database.request(request.id))
        assertEquals(request, database.claimNext())
        assertNull(database.claimNext())
        database.refreshQueue()
        assertEquals("RUNNING", database.queue.value.single().status)
    }

    @Test fun interruptedRunningMutationIsNeverAutomaticallyReplayed() = runBlocking {
        val running = operation(OperationType.MOVE)
        val waiting = operation()
        database.enqueue(running, "Move first")
        database.enqueue(waiting, "Copy second")
        assertEquals(running, database.claimNext())
        val artifact = NodeRef("test", "staging-ref")
        database.journal(running.id, "COMMITTING: verified destination", listOf(artifact), "Copied first child")
        reopen() // Simulate loss of the worker and all in-memory state.
        database.recoverInterrupted()
        val recovered = database.queue.value.first { it.id == running.id }
        assertEquals("INTERRUPTED", recovered.status)
        assertEquals(listOf(artifact), recovered.artifacts)
        assertTrue(recovered.results.contains("Copied first child"))
        assertEquals(waiting, database.claimNext())
        assertNull(database.claimNext())
        database.recoverInterrupted()
        assertNull(database.claimNext())
    }

    @Test fun queuedCancellationPersistsAndPreventsClaim() = runBlocking {
        val request = operation(OperationType.DELETE)
        database.enqueue(request, "Delete files")
        database.cancel(request.id)
        reopen()
        assertTrue(database.isCancelled(request.id))
        assertNull(database.claimNext())
        database.refreshQueue()
        assertEquals("CANCELLED", database.queue.value.single().status)
    }

    @Test fun cancelledRunningRequestRecoversAsInterruptedWithoutReplay() = runBlocking {
        val request = operation(OperationType.MOVE)
        database.enqueue(request, "Move files")
        database.claimNext()
        database.cancel(request.id)
        reopen()
        database.recoverInterrupted()
        assertTrue(database.isCancelled(request.id))
        assertEquals("INTERRUPTED", database.queue.value.single().status)
        assertNull(database.claimNext())
    }

    @Test fun queueClaimsRequestsInInsertionOrder() = runBlocking {
        val requests = List(3) { operation() }
        requests.forEachIndexed { index, request -> database.enqueue(request, "Item $index") }
        requests.forEach { expected ->
            assertEquals(expected, database.claimNext())
            database.finish(expected.id, "SUCCESS", "Copied")
        }
        assertNull(database.claimNext())
    }

    @Test fun resolvingConflictRetriesOnlyConflictedSourcesAndCannotDoubleEnqueue() = runBlocking {
        val completedSource = NodeRef("test", "already-copied")
        val conflictedSource = NodeRef("test", "needs-decision")
        val original = operation().copy(sources = listOf(completedSource, conflictedSource))
        database.enqueue(original, "Copy two files")
        database.claimNext()
        database.journal(original.id, "One name collision", emptyList(), "Copied already-copied")
        database.saveConflicts(original.id, listOf(conflicted(conflictedSource)))
        database.finish(original.id, "CONFLICT", "Choose how to handle the remaining name")
        reopen()
        assertTrue(database.resolveConflict(original.id, ConflictPolicy.KEEP_BOTH))
        assertFalse(database.resolveConflict(original.id, ConflictPolicy.KEEP_BOTH))
        val retry = database.claimNext()!!
        assertNotEquals(original.id, retry.id)
        assertEquals(listOf(conflictedSource), retry.sources)
        assertEquals(ConflictPolicy.KEEP_BOTH, retry.conflictPolicy)
        assertEquals(destination, retry.destination)
        assertNull("A policy resolution must not invent a name", retry.name)
        assertNull(database.claimNext())
    }

    @Test fun cancelledConflictCannotBeResolvedIntoNewWork() = runBlocking {
        val request = operation()
        database.enqueue(request, "Copy conflicting file")
        database.claimNext()
        database.saveConflicts(request.id, listOf(conflicted(source)))
        database.finish(request.id, "CONFLICT", "Choose a name")
        database.cancel(request.id)
        reopen()
        assertFalse(database.resolveConflict(request.id, ConflictPolicy.REPLACE))
        assertNull(database.claimNext())
        database.refreshQueue()
        assertEquals("CANCELLED", database.queue.value.single().status)
    }

    @Test fun cancelBetweenEngineReturnAndConflictFinishDoesNotResurrectConflict() = runBlocking {
        val request = operation()
        database.enqueue(request, "Copy conflicting file")
        database.claimNext()
        // The engine has returned, but the worker has not recorded its final state yet.
        database.cancel(request.id)
        database.saveConflicts(request.id, listOf(conflicted(source)))
        database.finish(request.id, "CONFLICT", "Choose a name")
        assertEquals("CANCELLED", database.queue.value.single().status)
        assertFalse(database.resolveConflict(request.id, ConflictPolicy.KEEP_BOTH))
        assertNull(database.claimNext())
    }

    @Test fun aWaitingConflictRecordsWhetherItIsAFolderSoTheRightDecisionIsOffered() = runBlocking {
        val folder = NodeRef("test", "colliding-folder")
        val file = NodeRef("test", "colliding-file")
        val request = operation().copy(sources = listOf(folder, file))
        database.enqueue(request, "Copy a folder and a file")
        database.claimNext()
        database.saveConflicts(request.id, listOf(conflicted(folder, directory = true), conflicted(file)))
        database.finish(request.id, "CONFLICT", "A folder named photos already exists.")
        reopen()
        database.refreshQueue()
        val item = database.queue.value.single()
        assertTrue("A folder collision must offer Merge", item.conflictFolders)
        assertTrue("and a file collision alongside it must still offer Overwrite", item.conflictFiles)
        assertEquals("The names are offered in retry order", listOf("colliding-folder", "colliding-file"), item.conflictNames)
        assertTrue(database.resolveConflict(request.id, ConflictPolicy.KEEP_BOTH))
        assertEquals(listOf(folder, file), database.claimNext()!!.sources)
        database.refreshQueue()
        assertTrue("A policy resolution clears every waiting row",
            database.queue.value.first { it.id == request.id }.conflictNames.isEmpty())
    }

    @Test fun renamingOneConflictRetriesOnlyThatItemAndLeavesTheRestWaiting() = runBlocking {
        val first = NodeRef("test", "first.txt")
        val second = NodeRef("test", "second.txt")
        val request = operation().copy(sources = listOf(first, second))
        database.enqueue(request, "Copy two files")
        database.claimNext()
        database.saveConflicts(request.id, listOf(conflicted(first), conflicted(second)))
        database.finish(request.id, "CONFLICT", "Two names already exist")
        reopen()

        assertTrue(database.resolveConflict(request.id, ConflictPolicy.ASK, "first copy.txt"))
        val retry = database.claimNext()!!
        assertEquals("One typed name can only serve one item", listOf(first), retry.sources)
        assertEquals("first copy.txt", retry.name)
        assertEquals("A rename must ask again rather than collide silently", ConflictPolicy.ASK, retry.conflictPolicy)
        assertEquals(destination, retry.destination)

        database.refreshQueue()
        val waiting = database.queue.value.first { it.id == request.id }
        assertEquals("CONFLICT", waiting.status)
        assertEquals(listOf("second.txt"), waiting.conflictNames)

        assertTrue(database.resolveConflict(request.id, ConflictPolicy.ASK, "second copy.txt"))
        assertEquals(listOf(second), database.claimNext()!!.sources)
        database.refreshQueue()
        assertEquals("RESOLVED", database.queue.value.first { it.id == request.id }.status)
    }

    @Test fun renamingSurvivesReopenAndCannotDoubleEnqueue() = runBlocking {
        val request = operation()
        database.enqueue(request, "Copy one file")
        database.claimNext()
        database.saveConflicts(request.id, listOf(conflicted(source, name = "notes.txt")))
        database.finish(request.id, "CONFLICT", "notes.txt already exists")
        reopen()

        assertTrue(database.resolveConflict(request.id, ConflictPolicy.ASK, "notes copy.txt"))
        assertFalse(database.resolveConflict(request.id, ConflictPolicy.ASK, "notes again.txt"))
        assertEquals("notes copy.txt", database.claimNext()!!.name)
        assertNull("Only one retry may be queued", database.claimNext())
    }

    @Test fun aCancelledConflictCannotBeRenamed() = runBlocking {
        val request = operation()
        database.enqueue(request, "Copy conflicting file")
        database.claimNext()
        database.saveConflicts(request.id, listOf(conflicted(source)))
        database.finish(request.id, "CONFLICT", "Choose a name")
        database.cancel(request.id)
        reopen()
        assertFalse(database.resolveConflict(request.id, ConflictPolicy.ASK, "renamed.txt"))
        assertNull(database.claimNext())
    }

    @Test fun aFolderCreationCollisionKeepsItsNameForRenaming() = runBlocking {
        val request = OperationRequest(type = OperationType.CREATE_FOLDER, destination = destination, name = "projects")
        database.enqueue(request, "Create folder projects")
        database.claimNext()
        // A folder creation has no source, so only its name identifies the collision.
        database.saveConflicts(request.id, listOf(ItemOutcome(null, status = ItemStatus.CONFLICT,
            message = "Name collision", sourceDirectory = true, conflictName = "projects")))
        database.finish(request.id, "CONFLICT", "An item named projects already exists here")
        reopen()
        database.refreshQueue()
        assertEquals(listOf("projects"), database.queue.value.single().conflictNames)

        assertTrue(database.resolveConflict(request.id, ConflictPolicy.ASK, "projects 2024"))
        val retry = database.claimNext()!!
        assertEquals(OperationType.CREATE_FOLDER, retry.type)
        assertEquals("projects 2024", retry.name)
        assertTrue("A folder creation carries no sources", retry.sources.isEmpty())
    }

    @Test fun tabsHistoriesBookmarksAndPreferencesSurviveReopen() = runBlocking {
        val root = Location(listOf(Crumb(destination, "Storage")))
        val nested = Location(root.crumbs + Crumb(NodeRef("test", "nested"), "Projects"))
        val first = BrowserTab("first", listOf(root, nested), 0)
        val second = BrowserTab("second", listOf(nested), 0)
        val saved = BrowserState(
            tabs = listOf(first, second), activeTabId = second.id,
            bookmarks = listOf(Bookmark("Work", Destination.Place(location = nested))), recent = listOf(nested, root),
            preferences = Preferences(view = ViewMode.GRID_MEDIUM, sort = SortOrder.SIZE, descending = true,
                showHidden = true, theme = ThemeMode.DARK, uiScale = 1.25f),
        )
        database.saveSession(saved)
        reopen()
        val restored = database.loadSession()!!
        assertEquals(saved.tabs, restored.tabs)
        assertEquals(saved.activeTabId, restored.activeTabId)
        assertEquals(saved.bookmarks, restored.bookmarks)
        assertEquals(saved.recent, restored.recent)
        assertEquals(saved.preferences, restored.preferences)
        assertEquals(nested, restored.tabs.first().history[1]) // Forward history must survive.
    }
}
