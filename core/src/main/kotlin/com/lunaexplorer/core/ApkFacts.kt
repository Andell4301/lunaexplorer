package com.lunaexplorer.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import net.dongliu.apk.parser.ApkFile

data class ApkSection(val name: String, val facts: List<Pair<String, String>>)

data class ApkReport(
    val sections: List<ApkSection>,
    val anomalies: List<String>,
    val failure: String? = null,
)

// Signatures belong to PackageManager; apk-parser can misread valid APK Signing Blocks.
object ApkFacts {

    fun read(file: File): ApkReport = try {
        ApkFile(file).use { apk ->
            val meta = apk.apkMeta
            val sections = buildList {
                add(ApkSection("Package", buildList {
                    add("Name" to meta.label.orEmpty().ifEmpty { meta.packageName })
                    add("Package" to meta.packageName)
                    add("Version" to "${meta.versionName} (${meta.versionCode})")
                    meta.minSdkVersion?.let { add("Minimum SDK" to it) }
                    meta.targetSdkVersion?.let { add("Target SDK" to it) }
                    meta.maxSdkVersion?.let { add("Maximum SDK" to it) }
                    meta.compileSdkVersion?.let { add("Compiled against SDK" to it) }
                    meta.installLocation?.let { add("Install location" to it) }
                    add("Split" to (meta.split ?: "base"))
                    add("Feature split" to if (meta.isFeatureSplit) "Yes" else "No")
                    add("Split required" to if (meta.isSplitRequired) "Yes" else "No")
                }))

                val permissions = runCatching { meta.usesPermissions.orEmpty() }.getOrDefault(emptyList())
                if (permissions.isNotEmpty()) {
                    add(ApkSection("Permissions requested (${permissions.size})",
                        permissions.sorted().map { it.substringAfterLast('.') to it }))
                }

                val features = runCatching { meta.usesFeatures.orEmpty() }.getOrDefault(emptyList())
                if (features.isNotEmpty()) {
                    add(ApkSection("Features", features.map {
                        it.name.substringAfterLast('.') to if (it.isRequired) "required" else "optional"
                    }))
                }

                val locales = runCatching { apk.locales.orEmpty() }.getOrDefault(emptyList())
                if (locales.isNotEmpty()) {
                    add(ApkSection("Languages (${locales.size})", listOf(
                        "Declared" to locales.joinToString(", ") { it.toString().ifEmpty { "default" } },
                    )))
                }

                // Read from the zip directly: the parser has no entry listing of its own.
                val libraries = runCatching {
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("lib/") }
                            .map { it.removePrefix("lib/").substringBefore('/') }
                            .distinct().sorted().toList()
                    }
                }.getOrDefault(emptyList())
                if (libraries.isNotEmpty()) {
                    add(ApkSection("Native code", listOf("Architectures" to libraries.joinToString(", "))))
                }

                val classes = runCatching { apk.dexClasses?.size ?: 0 }.getOrDefault(0)
                if (classes > 0) {
                    add(ApkSection("Code", listOf("Classes in the first DEX" to "%,d".format(classes))))
                }
            }
            ApkReport(sections, inspect(file))
        }
    } catch (error: Throwable) {
        ApkReport(emptyList(), emptyList(), error.message ?: error::class.java.simpleName)
    }

    /** Structural problems in the ZIP container, as display strings. */
    fun inspect(file: File): List<String> = runCatching {
        val found = mutableListOf<String>()
        ZipFile(file).use { zip ->
            val names = mutableSetOf<String>()
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                if (!names.add(entry.name)) {
                    found += "Duplicate entry: ${entry.name}"
                }
                if (entry.name.isEmpty()) found += "An entry has an empty name"
                if (entry.name.split('/').any { it == ".." }) found += "Entry escapes its own tree: ${entry.name}"
                if (entry.name.startsWith("/")) found += "Entry has an absolute path: ${entry.name}"
                // Only stored and deflated are defined for an APK; anything else Android cannot read.
                if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                    found += "Unsupported compression on ${entry.name} (method ${entry.method})"
                }
            }
            if (zip.getEntry("AndroidManifest.xml") == null) {
                found += "No AndroidManifest.xml — this is not an installable package"
            }
            if (zip.getEntry("classes.dex") == null && names.none { it.matches(Regex("classes\\d*\\.dex")) }) {
                found += "No DEX code; this is a resource-only package"
            }
            if (names.none { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }) {
                found += "No v1 signature block; signed with v2 or later only, or unsigned"
            }
            if (count == 0) found += "The archive declares no entries"
        }
        found.distinct()
    }.getOrElse { listOf("Structure could not be read: ${it.message}") }
}
