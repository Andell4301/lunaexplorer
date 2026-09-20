package com.lunaexplorer.app.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageAnalysisTest {
    @get:Rule val temporary = TemporaryFolder()

    private val analysis by lazy { StorageAnalysis(RuntimeEnvironment.getApplication()) }

    private fun write(name: String, bytes: ByteArray): Pair<String, Long> {
        val file = File(temporary.root, name)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath to bytes.size.toLong()
    }

    @Test fun `identical files are found and the recoverable total counts all but one`() = runBlocking {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val files = listOf(write("a.bin", content), write("b.bin", content), write("c.bin", content))
        val report = analysis.duplicates(files)

        assertEquals(1, report.groups.size)
        assertEquals(3, report.groups.single().paths.size)
        assertEquals(4096L * 2, report.reclaimable)
        assertTrue(report.complete)
    }

    @Test fun `files of the same length that differ are not duplicates`() = runBlocking {
        val one = ByteArray(2048) { 1 }
        val other = ByteArray(2048) { 1 }.also { it[2047] = 9 }
        val report = analysis.duplicates(listOf(write("one.bin", one), write("other.bin", other)))
        assertTrue("Same size is not sameness", report.groups.isEmpty())
    }

    @Test fun `files sharing a length and a long identical opening are still compared whole`() = runBlocking {
        val shared = ByteArray(300_000) { (it % 97).toByte() }
        val one = shared.copyOf().also { it[299_999] = 1 }
        val other = shared.copyOf().also { it[299_999] = 2 }
        val report = analysis.duplicates(listOf(write("one.dat", one), write("other.dat", other)))
        assertTrue("Only the tail differs, and it still must not be called a duplicate",
            report.groups.isEmpty())
    }

    @Test fun `files of different lengths are never even read`() = runBlocking {
        val report = analysis.duplicates(listOf(
            write("small.bin", ByteArray(10)),
            write("large.bin", ByteArray(20)),
        ))
        assertTrue(report.groups.isEmpty())
        assertEquals("Nothing shares a length, so nothing should have been hashed", 0, report.examined)
    }

    @Test fun `unreadable files are not reported as identical`() = runBlocking {
        val candidates = listOf(write("one.bin", byteArrayOf(1)), write("other.bin", byteArrayOf(2)))
        val files = candidates.map { File(it.first) }
        try {
            files.forEach { file ->
                assertTrue(file.setReadable(false, false))
                org.junit.Assume.assumeFalse(file.canRead())
            }

            val report = analysis.duplicates(candidates)

            assertTrue(report.groups.isEmpty())
            assertFalse(report.complete)
        } finally {
            files.forEach { it.setReadable(true, true) }
        }
    }

    @Test fun `a path that is not there is skipped rather than throwing`() = runBlocking {
        val content = ByteArray(64) { 7 }
        val real = write("real.bin", content)
        val report = analysis.duplicates(listOf(real, "/does/not/exist.bin" to 64L))
        assertTrue(report.groups.isEmpty())
    }

    @Test fun `reaching the reading budget is reported rather than passed off as a full answer`() = runBlocking {
        val content = ByteArray(200_000) { (it % 13).toByte() }
        val files = (0 until 6).map { write("copy$it.bin", content) }
        val report = analysis.duplicates(files, budgetBytes = 1)
        assertFalse("A scan that stopped early must say so", report.complete)
    }
}
