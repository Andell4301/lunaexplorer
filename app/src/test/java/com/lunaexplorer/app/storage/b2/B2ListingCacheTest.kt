package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class B2ListingCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val backend = FakeB2Backend("media").apply {
        put("media", "docs/a.txt", "a")
        put("media", "docs/deep/b.txt", "b")
    }
    private val account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media")
    private val listings: File by lazy { temporary.newFolder("listings") }
    private val uploads: File by lazy { temporary.newFolder("uploads") }
    private fun newProvider() = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") },
        FakeB2Connector(backend), B2ListingCache(listings), uploads)
    private val provider by lazy { newProvider() }
    private val docs = NodeRef("b2", "cloud:media:docs")

    private suspend fun names(provider: B2StorageProvider = this.provider, complete: Boolean = false) =
        provider.list(docs, complete).toList().flatten().map { it.name }

    @Test fun `opening a folder again asks B2 nothing`() = runBlocking {
        assertEquals(listOf("deep", "a.txt"), names())
        val asked = backend.listCalls

        assertEquals(listOf("deep", "a.txt"), names())
        assertEquals(asked, backend.listCalls)
    }

    @Test fun `a saved listing outlives the process`() = runBlocking {
        names()
        val asked = backend.listCalls

        assertEquals(listOf("deep", "a.txt"), names(newProvider()))
        assertEquals("A new provider reads the snapshot from disk", asked, backend.listCalls)
    }

    @Test fun `a saved listing is served without connecting`() = runBlocking {
        names()
        val connector = FakeB2Connector(backend)
        val again = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") }, connector, B2ListingCache(listings), uploads)

        assertEquals(listOf("deep", "a.txt"), again.list(docs).toList().flatten().map { it.name })
        assertEquals("No authorization was asked for", emptyList<String>(), connector.keysSeen)
    }

    @Test fun `a locked vault shuts the account at the door, saved listings and all`() = runBlocking {
        names()
        val locked = B2StorageProvider({ listOf(account) }, { null }, FakeB2Connector(backend), B2ListingCache(listings), uploads)

        val refused = runCatching { locked.list(docs).toList() }.exceptionOrNull() as? StorageException

        assertEquals("Let in, the first file opened would be where the key is asked for", StorageError.AUTH, refused?.reason)
    }

    @Test fun `an account already authorized stays open when the vault locks`() = runBlocking {
        var key: String? = "s"
        val provider = B2StorageProvider({ listOf(account) }, { account -> key?.let { account.copy(applicationKey = it) } },
            FakeB2Connector(backend), B2ListingCache(listings), uploads)
        names(provider)

        key = null

        assertEquals(listOf("deep", "a.txt"), names(provider))
        assertEquals("a", provider.openRead(NodeRef("b2", "cloud:media:docs/a.txt")).use { it.readBytes().decodeToString() })
    }

    @Test fun `forgetting a folder makes its next listing fresh, and only its own`() = runBlocking {
        names()
        provider.list(NodeRef("b2", "cloud:media:docs/deep")).toList()
        backend.put("media", "docs/new.txt", "n")
        assertEquals("Until told otherwise the saved copy stands", listOf("deep", "a.txt"), names())

        provider.forget(docs)
        val asked = backend.listCalls

        assertEquals(listOf("deep", "a.txt", "new.txt"), names())
        provider.list(NodeRef("b2", "cloud:media:docs/deep")).toList()
        assertEquals("The subfolder was not forgotten, so only one request was made", asked + 1, backend.listCalls)
    }

    @Test fun `a complete listing always asks, and refreshes the saved copy`() = runBlocking {
        names()
        backend.put("media", "docs/new.txt", "n")

        assertEquals(listOf("deep", "a.txt", "new.txt"), names(complete = true))
        val asked = backend.listCalls
        assertEquals(listOf("deep", "a.txt", "new.txt"), names())
        assertEquals(asked, backend.listCalls)
    }

    @Test fun `Luna's own changes forget the folder they touched`() = runBlocking {
        names()

        provider.create(docs, "made.txt", directory = false)
        assertTrue("made.txt" in names())

        provider.delete(NodeRef("b2", "cloud:media:docs/made.txt"))
        assertTrue("made.txt" !in names())

        provider.rename(NodeRef("b2", "cloud:media:docs/a.txt"), "renamed.txt")
        assertEquals(listOf("deep", "renamed.txt"), names())
    }

    @Test fun `renaming a folder forgets the listings beneath its old name`() = runBlocking {
        val deep = NodeRef("b2", "cloud:media:docs/deep")
        provider.list(deep).toList()

        provider.rename(deep, "deeper")
        backend.put("media", "docs/deep/again.txt", "recreated by someone else")

        assertEquals("A folder of the old name must be read afresh, not from the old snapshot",
            listOf("again.txt"), provider.list(deep).toList().flatten().map { it.name })
    }

    @Test fun `clearing empties the cache, and its size says so`() = runBlocking {
        names()
        assertTrue(provider.cachedBytes() > 0)

        provider.forget(null)

        assertEquals(0L, provider.cachedBytes())
        val asked = backend.listCalls
        names()
        assertEquals(asked + 1, backend.listCalls)
    }

    @Test fun `a damaged snapshot is a miss, not a failure`() = runBlocking {
        names()
        listings.listFiles()!!.forEach { it.writeText("{\"key\":\"cloud:media:docs\"}\nnot json\n") }

        assertEquals(listOf("deep", "a.txt"), names(newProvider()))
    }

    @Test fun `a listing that a change overtook is not saved`() {
        val cache = B2ListingCache(listings)
        val begun = cache.now()
        cache.forget("cloud:media:docs")

        cache.write("cloud:media:docs", listOf(B2Listed("a.txt", "id-1")), begun)
        cache.write("cloud:media:other", listOf(B2Listed("b.txt", "id-2")), begun)

        assertNull(cache.read("cloud:media:docs"))
        assertEquals("A folder the change did not touch is saved as usual", listOf("b.txt"), cache.read("cloud:media:other")?.map { it.name })
    }

    @Test fun `a listing abandoned halfway saves nothing`() = runBlocking {
        val paged = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") }, FakeB2Connector(backend),
            B2ListingCache(listings), uploads, pageSize = 1)
        val firstBatch = paged.list(docs).first()
        assertEquals(1, firstBatch.size)

        assertEquals("Only a listing that ran to its end may be saved", setOf("deep", "a.txt"), names(paged).toSet())
    }
}
