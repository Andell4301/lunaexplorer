package com.lunaexplorer.app.ui

import com.lunaexplorer.app.model.Crumb
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.LocalStorageProvider
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    private val provider by lazy {
        LocalStorageProvider(listOf(LocalRoot("workspace", "workspace", temporary.root)), probe = PathProbe.OF_FILESYSTEM)
    }
    private val registry by lazy { ProviderRegistry(listOf(provider)) }
    private val resolver by lazy { PathResolver(registry) { runBlocking { provider.roots() } } }
    private val base get() = temporary.root.absolutePath

    @Test fun `cancelled metadata reads do not turn into missing locations`() {
        val memory = MemoryStorageProvider("cancelled")
        val parent = memory.folder(memory.root, "parent")
        val child = memory.file(parent, "notes.txt", "notes")
        val cancelled = CancellationException("Storage request cancelled")
        val provider = object : StorageProvider by memory {
            override suspend fun stat(ref: NodeRef): Entry = throw cancelled
        }
        val resolver = PathResolver(ProviderRegistry(listOf(provider))) { runBlocking { memory.roots() } }

        assertThrows(CancellationException::class.java) {
            runBlocking { resolver.resolves(Location(listOf(Crumb(parent, "parent")))) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { resolver.crumbsTo(child) }
        }
    }

    @Test fun `an exact path resolves to a crumb chain from the served root`() = runBlocking {
        File(temporary.root, "a/b").mkdirs()
        val located = requireNotNull(resolver.locate("$base/a/b"))
        assertEquals(listOf("workspace", "a", "b"), located.location.crumbs.map { it.name })
        assertEquals("$base/a/b", located.used)
        assertEquals("The root itself is a place", listOf("workspace"),
            resolver.locate(base)!!.location.crumbs.map { it.name })
    }

    @Test fun `a trailing space or a direction mark is part of the name and is never cleaned away`() = runBlocking {
        File(temporary.root, "a").mkdirs()
        for (typed in listOf("$base/a ", "$base/a‎")) {
            val missed = resolver.locate(typed)
            assertFalse("'$typed' must not open the plain folder",
                missed != null && resolver.resolves(missed.location))

            assertTrue(File(typed).mkdir())
            val located = requireNotNull(resolver.locate(typed))
            assertEquals(typed.substringAfterLast('/'), located.location.crumbs.last().name)
            assertEquals("The path is used exactly as typed", typed, located.used)
            assertTrue(resolver.resolves(located.location))
        }
    }

    @Test fun `a path under no served root is nowhere, but one under a root is a place even before it exists`() = runBlocking {
        assertNull(resolver.locate("/definitely/not/served"))
        // A missing path still resolves, so opening it can report the specific error.
        assertEquals(listOf("workspace", "missing"),
            resolver.locate("$base/missing")!!.location.crumbs.map { it.name })
    }

    @Test fun `a file's crumbs are walked back up to the root by asking the provider`() = runBlocking {
        val file = File(temporary.root, "a/b/c.txt"); file.parentFile!!.mkdirs(); file.writeText("x")
        val ref = requireNotNull(resolver.refFor(file.absolutePath))
        assertEquals(listOf("workspace", "a", "b"), resolver.crumbsTo(ref)!!.crumbs.map { it.name })
        assertEquals(file.absolutePath, resolver.pathOf(ref))
    }

    @Test fun `a remembered location stops resolving when its folder goes`() = runBlocking {
        val folder = File(temporary.root, "gone"); folder.mkdirs()
        val location = resolver.locate(folder.absolutePath)!!.location
        assertTrue(resolver.resolves(location))
        assertTrue(folder.delete())
        assertFalse(resolver.resolves(location))
    }
}
