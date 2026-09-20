package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.SearchEngine
import com.lunaexplorer.core.SearchEvent
import com.lunaexplorer.core.SearchFilter
import com.lunaexplorer.core.SearchType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class B2SearchTest {
    @get:Rule val temporary = TemporaryFolder()

    private val backend = FakeB2Backend("media").apply {
        put("media", "readme.txt", "r")
        put("media", "photos/2024/a.jpg", "a")
        put("media", "photos/2024/trip/b.jpg", "b")
        put("media", "photos/2025/c.jpg", "c")
        put("media", "photos/.thumbs/t.jpg", "t")
        put("media", "docs/report.txt", "d")
        put("media", "docs/empty/.bzEmpty", "")
    }
    private val account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media")
    private val listings: File by lazy { temporary.newFolder("listings") }
    private fun newProvider(pageSize: Int = 1000) = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") },
        FakeB2Connector(backend), B2ListingCache(listings), temporary.newFolder(), pageSize)
    private val provider by lazy { newProvider() }
    private val bucket = NodeRef("b2", "cloud:media:")
    private fun at(path: String) = NodeRef("b2", "cloud:media:$path")

    private suspend fun search(filter: SearchFilter, root: NodeRef = bucket, provider: B2StorageProvider = this.provider) =
        SearchEngine(ProviderRegistry(listOf(provider))).search(root, filter).toList()

    private fun keys(events: List<SearchEvent>) =
        events.filterIsInstance<SearchEvent.Batch>().flatMap { it.entries }.map { it.ref.key.removePrefix("cloud:media:") }.toSet()

    private suspend fun names(folder: NodeRef, provider: B2StorageProvider = this.provider) =
        provider.list(folder).toList().flatten().map { it.name }.toSet()

    @Test fun `a whole bucket is searched in one request, not one for every folder`() = runBlocking {
        val found = search(SearchFilter("jpg", SearchType.FILES))

        assertEquals(setOf("photos/2024/a.jpg", "photos/2024/trip/b.jpg", "photos/2025/c.jpg"), keys(found))
        assertEquals(1, backend.listCalls)
    }

    @Test fun `what is hidden, and everything under it, is found only when asked for`() = runBlocking {
        assertEquals(emptySet<String>(), keys(search(SearchFilter("t.jpg"))))
        assertEquals(setOf("photos/.thumbs/t.jpg"), keys(search(SearchFilter("t.jpg", includeHidden = true), provider = newProvider())))
    }

    @Test fun `folders are found too, an empty one by the marker that keeps it`() = runBlocking {
        val found = search(SearchFilter(type = SearchType.FOLDERS))

        assertEquals(setOf("photos", "photos/2024", "photos/2024/trip", "photos/2025", "docs", "docs/empty"), keys(found))
    }

    @Test fun `every folder a sweep finishes is saved, the same as a listing of it would be`() = runBlocking {
        search(SearchFilter("nothing-is-called-this"))
        val asked = backend.listCalls
        val folders = listOf("", "photos", "photos/2024", "photos/2024/trip", "docs", "docs/empty")

        val saved = folders.associateWith { names(at(it)) }

        assertEquals("Browsing what was swept asks B2 nothing", asked, backend.listCalls)
        val fresh = B2StorageProvider({ listOf(account) }, { it.copy(applicationKey = "s") }, FakeB2Connector(backend),
            B2ListingCache(temporary.newFolder("other")), temporary.newFolder())
        for (folder in folders) assertEquals("The saved listing of '$folder'", names(at(folder), fresh), saved[folder])
        assertTrue("Hidden folders are saved with the rest: hiding is for the screen", ".thumbs" in names(at("photos")))
    }

    @Test fun `what is already saved is read from there, and only the rest is swept`() = runBlocking {
        names(bucket); names(at("photos")); names(at("photos/2024")); names(at("photos/2024/trip")); names(at("photos/2025"))
        names(at("photos/.thumbs"))
        val asked = backend.listCalls

        val found = search(SearchFilter(type = SearchType.FILES))

        assertEquals(setOf("readme.txt", "photos/2024/a.jpg", "photos/2024/trip/b.jpg", "photos/2025/c.jpg", "docs/report.txt"), keys(found))
        assertEquals("Only docs had to be asked for", 1, backend.listCalls - asked)
    }

    @Test fun `a search that stops early saves no folder it had not finished`() = runBlocking {
        repeat(30) { backend.put("media", "photos/2024/extra-$it.jpg", "x") }
        val paged = newProvider(pageSize = 1)

        val found = search(SearchFilter("jpg", SearchType.FILES, maxResults = 2), provider = paged)

        assertTrue((found.last() as SearchEvent.Complete).truncated)
        val asked = backend.listCalls
        assertEquals("The folder it stopped in is listed afresh, and whole", 31, names(at("photos/2024"), paged).count { it.endsWith(".jpg") })
        assertTrue(backend.listCalls > asked)
    }

    @Test fun `a file and a folder of one name are found as the file, as a listing shows them`() = runBlocking {
        backend.put("media", "notes", "a file")
        backend.put("media", "notes/inside.txt", "unreachable")

        val found = search(SearchFilter("", includeHidden = true))

        assertTrue("notes" in keys(found))
        assertTrue("What lies under a name a file holds cannot be opened, so it is not offered", "notes/inside.txt" !in keys(found))
        assertEquals(1, names(bucket).count { it == "notes" })
    }

    @Test fun `the folder object other tools write is not a nameless file`() = runBlocking {
        backend.put("media", "music/", "")
        backend.put("media", "music/song.mp3", "s")

        val found = search(SearchFilter(), root = at("music"))

        assertEquals(setOf("music/song.mp3"), keys(found))
        assertEquals(setOf("song.mp3"), names(at("music")))
    }

    @Test fun `a key confined to a prefix searches only what it can see`() = runBlocking {
        backend.auth = backend.auth.copy(namePrefix = "photos/2024/")

        val found = search(SearchFilter(type = SearchType.FILES))

        assertEquals(setOf("photos/2024/a.jpg", "photos/2024/trip/b.jpg"), keys(found))
    }
}
