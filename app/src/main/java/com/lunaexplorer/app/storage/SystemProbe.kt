package com.lunaexplorer.app.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** An interface because Robolectric cannot model Os.access; tests use [OF_FILESYSTEM]. */
interface PathProbe {
    fun exists(path: String): Boolean
    fun traversable(path: String): Boolean
    fun mountPointsUnder(directory: String): List<String>

    companion object {
        val OF_FILESYSTEM: PathProbe = object : PathProbe {
            override fun exists(path: String) = File(path).exists()
            override fun traversable(path: String) = File(path).let { it.isDirectory && it.canExecute() }
            override fun mountPointsUnder(directory: String) = emptyList<String>()
        }
    }
}

object SystemProbe : PathProbe {
    // access(F_OK) works where SELinux denies stat/getattr but permits path search; AOSP
    // File.exists does the same.
    override fun exists(path: String): Boolean {
        try {
            Os.access(path, OsConstants.F_OK)
            return true
        } catch (error: ErrnoException) {
            return existsFromErrno(error.errno) { parentIsSearchable(path) }
        } catch (_: Exception) {
            return false
        }
    }

    fun existsFromErrno(errno: Int, parentSearchable: () -> Boolean): Boolean = when (errno) {
        OsConstants.ENOENT, OsConstants.ENOTDIR, OsConstants.ELOOP, OsConstants.ENAMETOOLONG -> false
        // Under a searchable parent a denial means the entry exists; otherwise the denial may come
        // from an ancestor.
        OsConstants.EACCES, OsConstants.EPERM -> parentSearchable()
        else -> false
    }

    override fun traversable(path: String): Boolean = try {
        Os.access(path, OsConstants.X_OK)
        true
    } catch (_: Exception) {
        false
    }

    private fun parentIsSearchable(path: String): Boolean = File(path).parent?.let { traversable(it) } ?: false

    override fun mountPointsUnder(directory: String): List<String> = runCatching {
        mountPointsIn(File("/proc/self/mountinfo").readLines(), directory)
    }.getOrDefault(emptyList())

    fun mountPointsIn(lines: List<String>, directory: String): List<String> {
        val prefix = if (directory == "/") "/" else "$directory/"
        return lines.mapNotNull { line ->
            // mountinfo fields: id, parent, major:minor, root, mountpoint.
            val point = line.split(' ').getOrNull(4)?.let(::unescape) ?: return@mapNotNull null
            if (!point.startsWith(prefix) || point == directory) return@mapNotNull null
            val remainder = point.removePrefix(prefix)
            if (remainder.isEmpty()) null else remainder.substringBefore('/')
        }.distinct()
    }

    /** mountinfo escapes space, tab, newline and backslash as octal. */
    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 3 < value.length) {
                val octal = value.substring(index + 1, index + 4).toIntOrNull(8)
                if (octal != null) {
                    out.append(octal.toChar())
                    index += 4
                    continue
                }
            }
            out.append(character)
            index++
        }
        return out.toString()
    }
}
