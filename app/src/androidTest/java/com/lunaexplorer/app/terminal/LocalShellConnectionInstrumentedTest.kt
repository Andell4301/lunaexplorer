package com.lunaexplorer.app.terminal

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalShellConnectionInstrumentedTest {
    @Test(timeout = 15_000) fun narrowShellEditsPastScreenWidthAndTracksResize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shell = LocalShellConnection.open(context, columns = 47, rows = 23)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pty-edit-test").apply { isDaemon = true } }
        try {
            withContext(Dispatchers.IO) {
                executor.submit<String> {
                    val text = StringBuilder()
                    while (!text.endsWith("$ ") && !text.endsWith("# ")) {
                        val value = shell.input.read()
                        check(value >= 0)
                        text.append(value.toChar())
                    }
                    text.toString()
                }.get(5, TimeUnit.SECONDS)
                shell.output.write("printf '\\ninitial=%s,%s\\n' \"\$COLUMNS\" \"\$LINES\"\r".toByteArray())
                val initial = executor.submit<String> {
                    val text = StringBuilder()
                    while (!text.contains("initial=47,23\r\n") || (!text.endsWith("$ ") && !text.endsWith("# "))) {
                        val value = shell.input.read()
                        check(value >= 0)
                        text.append(value.toChar())
                    }
                    text.toString()
                }.get(5, TimeUnit.SECONDS)
                assertTrue(initial, initial.contains("initial=47,23\r\n"))
                shell.output.write(("printf '\\nedit=%s\\n' '" + "a".repeat(128)).toByteArray())
                shell.resize(31, 17)
                shell.output.write(ByteArray(128) { 127 })
                shell.output.write(("edited'\rprintf '\\nresized=%s,%s\\n' \"\$COLUMNS\" \"\$LINES\"; " +
                    "stty size; exit\r").toByteArray())
                val text = executor.submit<String> { shell.input.bufferedReader().readText() }.get(5, TimeUnit.SECONDS)
                assertTrue(text, text.contains("\r\nedit=edited\r\n"))
                assertTrue(text, text.contains("\r\nresized=31,17\r\n"))
                assertTrue(text, text.contains("\r\n17 31\r\n"))
            }
        } finally {
            withContext(Dispatchers.IO) { shell.close() }
            executor.shutdownNow()
        }
    }

    @Test(timeout = 15_000) fun openingShellProducesPromptBeforeInput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shell = LocalShellConnection.open(context)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pty-prompt-test").apply { isDaemon = true } }
        try {
            withContext(Dispatchers.IO) {
                val prompt = executor.submit<String> {
                    val text = StringBuilder()
                    while (!text.endsWith("$ ") && !text.endsWith("# ") && text.length < 4096) {
                        val value = shell.input.read()
                        if (value < 0) break
                        text.append(value.toChar())
                    }
                    text.toString()
                }.get(5, TimeUnit.SECONDS)
                assertTrue(prompt, prompt.endsWith("$ ") || prompt.endsWith("# "))
            }
        } finally {
            withContext(Dispatchers.IO) { shell.close() }
            executor.shutdownNow()
        }
    }

    @Test(timeout = 15_000) fun controllingTerminalResizesAndPreservesDirectory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "terminal '${UUID.randomUUID()} Ω ")
        assertTrue(directory.mkdir())
        try {
            val shell = LocalShellConnection.open(context, directory.path)
            withContext(Dispatchers.IO) {
                shell.use {
                    it.resize(101, 37)
                    it.output.write(("stty -echo; if test -t 0 && test -t 1 && test -t 2; " +
                        "then printf '%s=%s\\n' terminal yes; fi; stty size; pwd; exit 37\n").toByteArray())
                    val text = it.input.bufferedReader().readText()
                    assertTrue(text, text.contains("terminal=yes"))
                    assertTrue(text, text.contains("37 101"))
                    assertTrue(text, text.contains(directory.path))
                    withTimeout(5_000) { while (it.exitStatus == null) delay(10) }
                    assertEquals(37, it.exitStatus)
                }
            }
        } finally { directory.delete() }
    }

    @Test(timeout = 15_000) fun closingInterruptsReadAndReapsShell() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shell = LocalShellConnection.open(context)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pty-test").apply { isDaemon = true } }
        try {
            withContext(Dispatchers.IO) {
                shell.output.write("stty -echo; printf '\\npid=%s\\n' \"\$\$\"; sleep 60\n".toByteArray())
                val text = StringBuilder()
                val pattern = Regex("(?:^|\n)pid=(\\d+)\r?\n")
                while (!pattern.containsMatchIn(text)) {
                    val value = shell.input.read()
                    assertTrue(value >= 0)
                    text.append(value.toChar())
                }
                val pid = pattern.find(text)!!.groupValues[1].toInt()
                val reading = executor.submit<Int> {
                    var value: Int
                    do { value = shell.input.read() } while (value >= 0)
                    value
                }
                shell.close()
                shell.close()
                assertEquals(-1, reading.get(5, TimeUnit.SECONDS))
                withTimeout(5_000) { while (shell.exitStatus == null) delay(10) }
                assertTrue(shell.exitStatus!! >= 128)
                try {
                    Os.kill(pid, 0)
                    fail("Shell process still exists")
                } catch (failure: ErrnoException) {
                    assertEquals(OsConstants.ESRCH, failure.errno)
                }
            }
        } finally {
            withContext(Dispatchers.IO) { shell.close() }
            executor.shutdownNow()
        }
    }
}
