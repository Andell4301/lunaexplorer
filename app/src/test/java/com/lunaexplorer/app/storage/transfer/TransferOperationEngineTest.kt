package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.core.ItemStatus
import com.lunaexplorer.core.ConflictPolicy
import com.lunaexplorer.core.JournalPhase
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationEngine
import com.lunaexplorer.core.OperationEvent
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import com.lunaexplorer.core.ProviderRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferOperationEngineTest {
    private class Fixture(protocol: TransferProtocol) {
        val account = TransferAccount(id = "test", name = "Server", host = "test.local", protocol = protocol)
        val connector = FakeTransferConnector()
        val remote = TransferStorageProvider(protocol.providerId, { listOf(account) }, { TransferCredentials() }, connector)
        val local = MemoryStorageProvider("device")
        val engine = OperationEngine(ProviderRegistry(listOf(local, remote)))
        val root = NodeRef(protocol.providerId, "test:")
        fun at(path: String) = NodeRef(account.protocol.providerId, "test:$path")
    }

    @Test fun `both protocols stage and verify every file before publishing a copied folder`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val fixture = Fixture(protocol)
            val folder = fixture.local.folder(fixture.local.root, "album")
            fixture.local.file(folder, "one.txt", "one")
            fixture.local.file(fixture.local.folder(folder, "nested"), "two.txt", "two")
            val events = mutableListOf<OperationEvent>()

            val result = fixture.engine.run(OperationRequest(type = OperationType.COPY, sources = listOf(folder), destination = fixture.root)) {
                events += it
            }

            assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
            assertEquals("one", fixture.connector.text("album/one.txt"))
            assertEquals("two", fixture.connector.text("album/nested/two.txt"))
            assertEquals(listOf("album"), fixture.remote.list(fixture.root).toList().flatten().map { it.name })
            assertEquals(2, fixture.connector.readPaths.size)
            assertTrue(fixture.connector.readPaths.all { it.startsWith(".luna-") })
            val phases = events.filterIsInstance<OperationEvent.Journal>().map { it.phase }
            assertTrue(phases.indexOf(JournalPhase.VERIFIED) < phases.indexOf(JournalPhase.COMMITTING))
            assertTrue(phases.indexOf(JournalPhase.COMMITTING) < phases.indexOf(JournalPhase.PUBLISHED))
            assertTrue(fixture.local.exists(folder))
            fixture.remote.disconnect(fixture.account.id)
            assertEquals(fixture.connector.sessionsOpened.get(), fixture.connector.sessionsClosed.get())
        }
    }

    @Test fun `a corrupt upload fails verification without publishing or deleting the move source`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val fixture = Fixture(protocol)
            val file = fixture.local.file(fixture.local.root, "notes.txt", "content")
            fixture.connector.corruptWrites = true

            val result = fixture.engine.run(OperationRequest(type = OperationType.MOVE, sources = listOf(file), destination = fixture.root))

            assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
            assertEquals("content", fixture.local.text(file))
            assertNull(fixture.connector.text("notes.txt"))
            assertEquals(0, fixture.connector.renameCalls)
            assertTrue(fixture.remote.list(fixture.root).toList().flatten().isEmpty())
        }
    }

    @Test fun `a moved download is verified before its remote source is deleted`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val fixture = Fixture(protocol)
            fixture.connector.put("notes.txt", "content")
            val events = mutableListOf<OperationEvent>()

            val result = fixture.engine.run(OperationRequest(type = OperationType.MOVE,
                sources = listOf(fixture.at("notes.txt")), destination = fixture.local.root)) { events += it }

            assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
            assertEquals("content", fixture.local.text(fixture.local.childRef(fixture.local.root, "notes.txt")!!))
            assertNull(fixture.connector.text("notes.txt"))
            val phases = events.filterIsInstance<OperationEvent.Journal>().map { it.phase }
            assertTrue(phases.indexOf(JournalPhase.VERIFIED) < phases.indexOf(JournalPhase.DELETING_SOURCE))
        }
    }

    @Test fun `a lost remote move reply never starts a copy or replays the rename`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val fixture = Fixture(protocol)
            fixture.connector.put("notes.txt", "content")
            fixture.connector.folder("destination")
            fixture.connector.renameFailsAfter = true

            val result = fixture.engine.run(OperationRequest(type = OperationType.MOVE,
                sources = listOf(fixture.at("notes.txt")), destination = fixture.at("destination")))

            assertEquals(ItemStatus.FAILED, result.outcomes.single().status)
            assertEquals("content", fixture.connector.text("destination/notes.txt"))
            assertEquals(1, fixture.connector.renameCalls)
            assertEquals(0, fixture.connector.creates)
            assertTrue(fixture.connector.readPaths.isEmpty())
            assertFalse(fixture.remote.list(fixture.root).toList().flatten().any { it.name.startsWith(".luna-") })
        }
    }

    @Test fun `overwrite verifies staged bytes before replacing and then removes the move source`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val fixture = Fixture(protocol)
            val source = fixture.local.file(fixture.local.root, "notes.txt", "replacement")
            fixture.connector.put("notes.txt", "previous")
            val phases = mutableListOf<JournalPhase>()
            fixture.connector.beforeRename = { _, _ -> assertTrue(JournalPhase.VERIFIED in phases) }

            val result = fixture.engine.run(OperationRequest(type = OperationType.MOVE, sources = listOf(source),
                destination = fixture.root, conflictPolicy = ConflictPolicy.REPLACE)) {
                if (it is OperationEvent.Journal) phases += it.phase
            }

            assertTrue(result.outcomes.joinToString { it.message.orEmpty() }, result.successful)
            assertEquals("replacement", fixture.connector.text("notes.txt"))
            assertFalse(fixture.local.exists(source))
            assertEquals(listOf("notes.txt"), fixture.remote.list(fixture.root).toList().flatten().map { it.name })
            assertTrue(fixture.connector.readPaths.any { it.startsWith(".luna-") })
            fixture.remote.disconnect(fixture.account.id)
        }
    }

    @Test fun `uncertain overwrite retains the move source and records the previous remote file`() = runBlocking {
        for (protocol in TransferProtocol.entries) {
            val fixture = Fixture(protocol)
            val source = fixture.local.file(fixture.local.root, "notes.txt", "replacement")
            fixture.connector.put("notes.txt", "previous")
            fixture.connector.renameFailsAfterCall = 2

            val result = fixture.engine.run(OperationRequest(type = OperationType.MOVE, sources = listOf(source),
                destination = fixture.root, conflictPolicy = ConflictPolicy.REPLACE))

            val outcome = result.outcomes.single()
            assertEquals(ItemStatus.FAILED, outcome.status)
            assertEquals("replacement", fixture.local.text(source))
            assertEquals("replacement", fixture.connector.text("notes.txt"))
            assertTrue("The old file must remain discoverable from the operation outcome", outcome.artifacts.any {
                fixture.connector.text(it.key.substringAfter(':')) == "previous"
            })
            assertEquals(2, fixture.connector.renameCalls)
            fixture.remote.disconnect(fixture.account.id)
        }
    }
}
