package com.lunaexplorer.app.storage

import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemProbeTest {
    @Test fun `a missing name is absent, and a forbidden one under a searchable parent is present`() {
        assertFalse("ENOENT means it is not there",
            SystemProbe.existsFromErrno(OsConstants.ENOENT) { true })
        assertFalse(SystemProbe.existsFromErrno(OsConstants.ENOTDIR) { true })

        assertTrue("EACCES under a searchable parent means it exists",
            SystemProbe.existsFromErrno(OsConstants.EACCES) { true })
    }

    @Test fun `a denial inherited from further up the path is not read as a file`() {
        assertFalse("EACCES with an unsearchable parent proves nothing",
            SystemProbe.existsFromErrno(OsConstants.EACCES) { false })
        assertFalse(SystemProbe.existsFromErrno(OsConstants.EPERM) { false })
    }

    @Test fun `mount points are the direct children of the directory asked about`() {
        val lines = listOf(
            "23 1 0:21 / /proc rw,nosuid shared:5 - proc proc rw",
            "24 1 0:22 / /sys rw,nosuid shared:6 - sysfs sysfs rw",
            "31 1 253:6 / /apex/com.android.art ro,nodev shared:9 - ext4 /dev/block/x ro",
            "32 1 253:7 / /apex/com.android.media ro,nodev shared:10 - ext4 /dev/block/y ro",
            "40 1 0:44 / /apex/com.android.art/bin ro shared:11 - tmpfs tmpfs ro",
        )
        assertEquals(listOf("proc", "sys", "apex"), SystemProbe.mountPointsIn(lines, "/"))
        assertEquals("Only direct children, not what is mounted beneath them",
            listOf("com.android.art", "com.android.media"), SystemProbe.mountPointsIn(lines, "/apex"))
        assertEquals(emptyList<String>(), SystemProbe.mountPointsIn(lines, "/nowhere"))
    }

    @Test fun `an octal-escaped mount point is unescaped`() {
        // mountinfo escapes a space as \040.
        val lines = listOf("55 1 0:60 / /mnt/media\\040rw rw shared:2 - tmpfs tmpfs rw")
        assertEquals(listOf("media rw"), SystemProbe.mountPointsIn(lines, "/mnt"))
    }

    @Test fun `a malformed line is skipped rather than failing the listing`() {
        val lines = listOf("nonsense", "", "23 1 0:21 / /proc rw - proc proc rw")
        assertEquals(listOf("proc"), SystemProbe.mountPointsIn(lines, "/"))
    }
}
