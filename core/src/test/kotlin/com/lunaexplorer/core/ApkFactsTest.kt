package com.lunaexplorer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkFactsTest {

    @Test fun `something that is not an apk is reported rather than thrown`() {
        val file = File.createTempFile("luna-not-an-apk", ".apk")
        try {
            file.writeText("this is plain text, not a package")
            val report = ApkFacts.read(file)
            assertNotNull("An unreadable package must say so", report.failure)
            assertTrue(report.sections.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test fun `a zip with no manifest is not an installable package`() {
        val file = zip("readme.txt" to "hello")
        try {
            val problems = ApkFacts.inspect(file)
            assertTrue(problems.any { it.contains("No AndroidManifest.xml") })
        } finally {
            file.delete()
        }
    }

    @Test fun `a traversing entry name is reported`() {
        val file = zip("AndroidManifest.xml" to "x", "../escaped.so" to "y")
        try {
            assertTrue(ApkFacts.inspect(file).any { it.contains("escapes its own tree") })
        } finally {
            file.delete()
        }
    }

    @Test fun `a package with no code is described as resource-only`() {
        val file = zip("AndroidManifest.xml" to "x", "res/values.arsc" to "y")
        try {
            assertTrue(ApkFacts.inspect(file).any { it.contains("resource-only") })
        } finally {
            file.delete()
        }
    }

    @Test fun `a well formed package raises nothing about its own shape`() {
        val file = zip(
            "AndroidManifest.xml" to "x",
            "classes.dex" to "y",
            "META-INF/CERT.RSA" to "z",
            "assets/version..txt" to "dots are part of a name",
        )
        try {
            val problems = ApkFacts.inspect(file)
            assertTrue("Expected no structural complaints, got $problems", problems.isEmpty())
        } finally {
            file.delete()
        }
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("luna-apk-shape", ".apk")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        file.writeBytes(bytes.toByteArray())
        return file
    }
}
