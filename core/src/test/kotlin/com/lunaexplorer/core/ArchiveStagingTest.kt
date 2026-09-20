package com.lunaexplorer.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    private val members: Members = linkedMapOf("a.txt" to bytesOf("body"))

    @Test fun `the sweep deletes staged copies and leaves everything else`() {
        val directory = temporary.newFolder("stage")
        val browsing = File(directory, "luna-archive8811.7z").apply { writeText("x") }
        val extraction = File(directory, "luna-extract4021.rar").apply { writeText("x") }
        val rewrite = File(directory, "luna-rewrite-1f0c.zip").apply { writeText("x") }
        val member = File(directory, "luna-member-77aa").apply { writeText("x") }
        val unrelated = File(directory, "notes.txt").apply { writeText("x") }

        sweepArchiveStaging(directory)

        assertFalse("A copy left behind by a killed process is removed", browsing.exists())
        assertFalse(extraction.exists())
        assertFalse(rewrite.exists())
        assertFalse(member.exists())
        assertTrue("Nothing else in the directory is touched", unrelated.exists())
    }

    @Test fun `a browsed archive stages into the given directory and drops the copy on eviction`() = runBlocking {
        val staging = temporary.newFolder("browse-stage")
        val source = ByteSource("bundle.7z", sevenZBytes(members), seekable = false)
        val others = (1..5).map { ByteSource("other$it.7z", sevenZBytes(members), seekable = false, id = "other$it") }
        lateinit var registry: ProviderRegistry
        val archive = ArchiveProvider({ registry }, stagingDirectory = staging)
        registry = ProviderRegistry(listOf(source) + others + archive)

        val root = archive.open(source.ref, "bundle.7z")
        assertEquals("body", archive.text(memberRef(root, "a.txt")))
        val staged = staging.listFiles()!!.single()
        assertTrue(staged.name, staged.name.startsWith("luna-archive"))

        // The cache holds four archives, so opening five more evicts this one.
        for (other in others) archive.open(other.ref, other.name)

        assertFalse("The evicted archive's copy is deleted", staged.exists())
    }

    @Test fun `extraction stages into the given directory and removes the copy when it closes`() = runBlocking {
        val staging = temporary.newFolder("extract-stage")
        val source = ByteSource("bundle.7z", sevenZBytes(members), seekable = false)
        val into = MemoryStorageProvider("into")
        // Progress events are buffered, so sample the staging directory from inside the extraction.
        val duringExtraction = mutableListOf<String>()
        val destination = object : StorageProvider by into {
            override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String): Entry {
                duringExtraction += staging.list().orEmpty()
                return into.create(parent, name, directory, mimeType)
            }
        }
        val engine = ArchiveEngine(ProviderRegistry(listOf(source, destination)), stagingDirectory = staging)

        engine.extract(source.ref, into.root, null).collect {}

        assertTrue("Staged where the engine was told: $duringExtraction",
            duringExtraction.any { it.startsWith("luna-extract") })
        assertEquals("and removed once its channel closes", 0, staging.listFiles()!!.size)
        assertEquals("body", into.text(into.childRef(into.root, "a.txt")!!))
    }
}
