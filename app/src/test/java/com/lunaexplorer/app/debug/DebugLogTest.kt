package com.lunaexplorer.app.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

class DebugLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val made = mutableListOf<DebugLog>()

    // Logs are closed before TemporaryFolder deletes their directory, or a late flush would recreate it.
    private fun track(log: DebugLog) = log.also { made += it }

    @After fun closeAll() { made.forEach { it.close() } }

    private fun recording(folder: File = temporary.root, maxChars: Int = DebugLog.DEFAULT_MAX_CHARS) =
        track(DebugLog(folder, maxChars).apply { setRecording(true) })

    @Test fun `nothing is kept while it is not recording`() {
        val log = track(DebugLog(temporary.root))
        log.log(DebugLog.Level.WARN, "Smb", "not kept")

        assertFalse(log.enabled)
        assertEquals("", log.snapshot().text)
    }

    @Test fun `a line says when, how serious, who, on which thread, and what`() {
        val log = recording()
        log.log(DebugLog.Level.WARN, "Smb", "list NAS/media failed")

        val line = log.snapshot().text.lines().last { it.isNotEmpty() }
        assertTrue(line, Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d W Smb \[.+]: list NAS/media failed""").matches(line))
    }

    @Test fun `a control character is shown as a mark rather than written into the text`() {
        val log = recording()
        log.log(DebugLog.Level.INFO, "Test", "root\u0000path\tcolumn")

        val text = log.snapshot().text
        assertFalse(text.contains('\u0000'))
        assertTrue(text, text.contains("root|path\tcolumn"))
    }

    @Test fun `an error is kept with every cause under it`() {
        val log = recording()
        log.log(DebugLog.Level.WARN, "Smb", "read failed", IOException("outer", SocketTimeoutException("Read timed out")))

        val text = log.snapshot().text
        assertTrue(text.contains("java.io.IOException: outer"))
        assertTrue(text.contains("Caused by: java.net.SocketTimeoutException: Read timed out"))
    }

    @Test fun `the oldest lines go first, and a view that fell behind them is told to read it all again`() {
        val log = recording(maxChars = 2_000)
        val before = log.snapshot()
        repeat(200) { log.log(DebugLog.Level.DEBUG, "Test", "line $it") }

        val text = log.snapshot().text
        assertTrue("Kept within the cap: ${text.length}", text.length <= 2_000)
        assertTrue(text.contains("line 199"))
        assertFalse(text.contains("line 0\n"))
        assertNull("Its place is no longer held", log.since(before.sequence, before.epoch))
    }

    @Test fun `a view that kept up is given only what came after`() {
        val log = recording()
        log.log(DebugLog.Level.INFO, "Test", "first")
        val seen = log.snapshot()
        log.log(DebugLog.Level.INFO, "Test", "second")

        val update = log.since(seen.sequence, seen.epoch)
        assertNotNull(update)
        assertTrue(update!!.text.contains("second"))
        assertFalse(update.text.contains("first"))
    }

    @Test fun `what was recorded, and that it is recording, outlast the process`() {
        val folder = temporary.newFolder()
        recording(folder).apply {
            log(DebugLog.Level.INFO, "Test", "before the restart")
            flush()
        }

        val again = track(DebugLog(folder))
        assertTrue(again.enabled)
        assertTrue(again.snapshot().text.contains("before the restart"))
    }

    @Test fun `clearing empties the log and the file behind it`() {
        val folder = temporary.newFolder()
        val log = recording(folder)
        log.log(DebugLog.Level.INFO, "Test", "gone")
        log.flush()
        val seen = log.snapshot()

        log.clear()

        assertEquals("", log.snapshot().text)
        assertFalse(track(DebugLog(folder)).snapshot().text.contains("gone"))
        repeat(3) { log.log(DebugLog.Level.INFO, "Test", "after $it") }
        assertNull("A view is told to read it again, however much was logged since", log.since(seen.sequence, seen.epoch))
    }

    @Test fun `turning recording off keeps what was recorded`() {
        val log = recording()
        log.log(DebugLog.Level.INFO, "Test", "kept")
        log.setRecording(false)
        log.log(DebugLog.Level.INFO, "Test", "not kept")

        val text = log.snapshot().text
        assertTrue(text.contains("kept"))
        assertTrue(text.contains("Recording stopped"))
        assertFalse(text.contains("not kept"))
    }

    @Test fun `the file stays near the size of what is kept however long it records`() {
        val folder = temporary.newFolder()
        val log = recording(folder, maxChars = 5_000)
        repeat(2_000) { log.log(DebugLog.Level.DEBUG, "Test", "line $it"); if (it % 100 == 0) log.flush() }
        log.flush()

        val file = File(folder, "luna.log")
        assertTrue("${file.length()} bytes", file.length() <= 12_000)
        assertTrue(file.readText().contains("line 1999"))
    }

    @Test fun `a failure reported again at the next layer up is not written out twice`() {
        val log = recording()
        val root = SocketTimeoutException("Read timed out")
        log.log(DebugLog.Level.WARN, "SmbConnector", "list: TIMEOUT", root)
        log.log(DebugLog.Level.WARN, "Smb", "list NAS failed", IOException("list failed", root))

        val text = log.snapshot().text
        assertEquals(text, 1, Regex("""java.net.SocketTimeoutException: Read timed out\n\s+at """).findAll(text).count())
        assertTrue(text, text.contains("java.io.IOException: list failed, from java.net.SocketTimeoutException: Read timed out (trace above)"))
    }

    @Test fun `a closed log writes what was waiting, and a line after that is no trouble to whoever logs it`() {
        val folder = temporary.newFolder()
        val log = recording(folder)
        log.log(DebugLog.Level.INFO, "Test", "before close")

        log.close()
        log.log(DebugLog.Level.INFO, "Test", "after close")

        assertTrue(File(folder, "luna.log").readText().contains("before close"))
    }

    @Test fun `copy all takes the whole log however full it has got`() {
        val log = recording()
        repeat(6_000) { log.log(DebugLog.Level.DEBUG, "Test", "line $it, long enough that six thousand of them pass the cap") }

        val text = log.snapshot().text
        assertEquals(text, DebugLog.tailForClipboard(text).text)
    }

    @Test fun `the clipboard gets the whole log, or its newest whole lines`() {
        val small = "a\nb\n"
        DebugLog.tailForClipboard(small, limit = 100).let {
            assertEquals(small, it.text)
            assertEquals(2, it.lines)
            assertEquals(2, it.totalLines)
        }

        val big = (1..100).joinToString("") { "line $it\n" }
        val clip = DebugLog.tailForClipboard(big, limit = 50)
        assertTrue(clip.text.length <= 50)
        assertTrue("Starts at a whole line: ${clip.text}", clip.text.startsWith("line "))
        assertTrue(clip.text.endsWith("line 100\n"))
        assertEquals(100, clip.totalLines)
        assertTrue(clip.lines < 100)
    }
}
