package com.lunaexplorer.app.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lunaexplorer.core.ProcedureNames
import com.lunaexplorer.core.containsProcedurePlaceholder
import com.lunaexplorer.core.escapeProcedurePlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class ProcedureNamesTest {
    @Test fun preparesTemplatedAndLiteralPaths() {
        val names = ProcedureNames(LocalDateTime.of(2026, 9, 24, 7, 5, 9))
        val template = "/backups/{date}/{time}-{datetime}"
        assertTrue(containsProcedurePlaceholder(template))
        assertEquals("/backups/2026-09-24/07-05-09-2026-09-24_07-05-09", names.expand(template))
        assertFalse(containsProcedurePlaceholder("/backups/plain"))
        assertEquals("/backups/plain", names.expand("/backups/plain"))

        val literal = "/backups/{date}/{{time}}/{unknown}"
        val escaped = escapeProcedurePlaceholders(literal)
        assertTrue(containsProcedurePlaceholder(escaped))
        assertEquals(literal, names.expand(escaped))
        assertEquals("{datetime}", names.expand("{{datetime}}"))
    }
}
