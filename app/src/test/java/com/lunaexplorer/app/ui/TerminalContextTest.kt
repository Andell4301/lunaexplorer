package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.storage.ShellAddressable
import com.lunaexplorer.app.storage.ShellLocation
import com.lunaexplorer.core.MemoryStorageProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class TerminalContextTest {
    @get:Rule val harness = BrowserViewModelHarness()
    private val memory = MemoryStorageProvider("terminal")

    @Test fun `terminal obtains remote shell paths from the provider instead of decoding references`() = runBlocking {
        val file = memory.file(memory.root, "a ' file", "text")
        harness.graph.providers.register(object : StorageProvider by memory, ShellAddressable {
            override val supportsShell = true
            override suspend fun shellLocation(ref: NodeRef) = ShellLocation("server", if (ref == file) "/remote/a ' file" else "/remote/")
        })
        val context = harness.viewModel.terminalContext(Overlay.Terminal(memory.root, listOf(memory.stat(file))))!!
        assertEquals("server", context.accountId)
        assertEquals("/remote/", context.directory)
        assertEquals(listOf("/remote/a ' file"), context.paths)
    }

    @Test fun `a displayed path alone never grants local shell access`() {
        harness.graph.providers.register(object : StorageProvider by memory, PathAddressable {
            override fun pathOf(ref: NodeRef): String? = null
            override fun shownPathOf(ref: NodeRef) = "/storage/emulated/0/Android/data/other"
            override fun refFor(path: String): NodeRef? = null
        })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.viewModel.terminalContext(Overlay.Terminal(memory.root)) }
        }
    }

    @Test fun `cancelled shell lookup is not reported as an unsupported folder`() {
        harness.graph.providers.register(object : StorageProvider by memory, ShellAddressable {
            override val supportsShell = true
            override suspend fun shellLocation(ref: NodeRef): ShellLocation? = throw CancellationException()
        })
        assertThrows(CancellationException::class.java) {
            runBlocking { harness.viewModel.terminalContext(Overlay.Terminal(memory.root)) }
        }
    }
}
