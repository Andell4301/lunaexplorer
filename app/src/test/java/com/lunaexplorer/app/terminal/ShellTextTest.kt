package com.lunaexplorer.app.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShellTextTest {
    @Test fun `inserted file arguments survive shell parsing without terminal control bytes`() {
        org.junit.Assume.assumeTrue(java.io.File("/bin/bash").isFile)
        val paths = listOf(" /tmp/a 'quote' ", "/tmp/\$(printf wrong);`false`", "/tmp/名\\tab\tline\n\r\u001b[31m\n")
        paths.forEach { path ->
            val quoted = shellInputQuote(path)
            assertFalse(quoted.any { it.code < 32 || it.code == 127 })
            val process = ProcessBuilder("/bin/bash", "-c", "printf '%s' $quoted").start()
            assertArrayEquals(path.toByteArray(), process.inputStream.readBytes())
            assertEquals(0, process.waitFor())
        }
    }
}
