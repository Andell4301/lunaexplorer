package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class RecycleBinPruneTest {
    @get:Rule val harness = BrowserViewModelHarness()

    @Test fun `pruning keeps recovery records until storage confirms the file is missing`() = runBlocking {
        assertTrue(harness.awaitUntil { harness.state.ready && !harness.state.loading })
        val memory = MemoryStorageProvider("bin")
        val present = memory.file(memory.root, "present", "kept")
        val failures = listOf(StorageError.NOT_FOUND, StorageError.PERMISSION, StorageError.AUTH,
            StorageError.OFFLINE, StorageError.TIMEOUT, StorageError.IO)
        val refs = listOf(present, NodeRef("unmounted", "held")) + failures.map { NodeRef(memory.id, it.name) }
        harness.graph.providers.register(object : StorageProvider by memory {
            override suspend fun stat(ref: NodeRef): Entry {
                if (ref == present) return memory.stat(ref)
                throw StorageException(StorageError.valueOf(ref.key), "Storage unavailable")
            }
        })
        val records = refs.mapIndexed { index, ref ->
            val source = NodeRef(memory.id, "source-$index")
            val record = TrashedItem("record-$index", "item-$index", source, memory.root, "item-$index",
                null, false, 4, index.toLong())
            harness.graph.database.stageTrash("move-$index", listOf(record))
            harness.graph.database.reconcileTrash("move-$index", listOf(ItemOutcome(source, ref, ItemStatus.SUCCESS, "Moved")))
            record.copy(ref = ref)
        }
        val expected = records.filter { it.ref.key != StorageError.NOT_FOUND.name }.map { it.id }.toSet()
        val operations = BrowserOperations(harness.graph, this, MutableStateFlow(harness.state),
            PathResolver(harness.graph.providers) { harness.state.roots }) {}

        operations.pruneBin()
        coroutineContext[Job]!!.children.toList().joinAll()

        assertEquals(expected, harness.graph.database.trash.value.map { it.id }.toSet())
    }
}
