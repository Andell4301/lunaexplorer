package com.lunaexplorer.app.storage

import com.lunaexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipFilesTest : StorageProviderContract() {
    @get:Rule val temporary = TemporaryFolder()
    private val clips by lazy { ClipFiles(temporary.root, PathProbe.OF_FILESYSTEM) }
    override val provider get() = clips.local
    private var counter = 0

    override suspend fun freshRoot(): NodeRef =
        provider.create(provider.root("exports"), "root${counter++}", directory = true).ref

    @Test fun `queued clips survive a new store and deleting the caller's old path`() = runBlocking {
        val rendered = clips.create().apply { writeText("rendered video") }
        val (_, source) = clips.take(rendered)
        rendered.delete()
        val restarted = ClipFiles(temporary.root, PathProbe.OF_FILESYSTEM)
        assertTrue(restarted.provider.roots().isEmpty())
        val target = MemoryStorageProvider("target")
        val engine = OperationEngine(ProviderRegistry(listOf(restarted.provider, target)))
        val journal = mutableListOf<JournalPhase>()

        val result = engine.run(OperationRequest(type = OperationType.MOVE, sources = listOf(source),
            destination = target.root, name = "cut.mp4")) { event ->
            if (event is OperationEvent.Journal) journal += event.phase
        }

        assertTrue(result.successful)
        assertTrue(journal.indexOf(JournalPhase.VERIFIED) < journal.indexOf(JournalPhase.PUBLISHED))
        val saved = requireNotNull(target.child(target.root, "cut.mp4"))
        assertEquals("rendered video", target.openRead(saved.ref).use { it.readBytes().decodeToString() })
        expect(StorageError.NOT_FOUND) { restarted.provider.stat(source) }
    }

    @Test fun `a conflicting destination leaves the generated clip available`() = runBlocking {
        val (_, source) = clips.take(clips.create().apply { writeText("new video") })
        val target = MemoryStorageProvider("target")
        val existing = target.file(target.root, "cut.mp4", "original video")
        val result = OperationEngine(ProviderRegistry(listOf(clips.provider, target))).run(
            OperationRequest(type = OperationType.MOVE, sources = listOf(source), destination = target.root, name = "cut.mp4"),
        )

        assertEquals(ItemStatus.CONFLICT, result.outcomes.single().status)
        assertEquals("original video", target.openRead(existing).use { it.readBytes().decodeToString() })
        assertEquals("new video", clips.provider.openRead(source).use { it.readBytes().decodeToString() })
    }

    @Test fun `the next export clears interrupted renders and keeps queued clips`() {
        val unfinished = clips.create().apply { writeText("unfinished") }
        val (queued, _) = clips.take(clips.create().apply { writeText("queued") })

        ClipFiles(temporary.root, PathProbe.OF_FILESYSTEM).create()

        assertFalse(unfinished.exists())
        assertEquals("queued", queued.readText())
    }
}
