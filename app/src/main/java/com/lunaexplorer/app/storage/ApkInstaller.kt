package com.lunaexplorer.app.storage

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.lunaexplorer.core.AndroidBinaryXml
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PackagePart(
    val name: String,
    val size: Long,
    val kind: PartKind,
    val detail: String,
    val recommended: Boolean,
)

enum class PartKind { BASE, ABI, DENSITY, LANGUAGE, FEATURE, OBB, OTHER }

data class PackageBundle(val parts: List<PackagePart>, val xapk: Boolean)

class ApkInstaller(context: Context, private val registry: ProviderRegistry) {
    private val context = context.applicationContext

    companion object {
        private const val ACTION_RESULT = "com.lunaexplorer.app.INSTALL_RESULT"
        private const val MANIFEST = "AndroidManifest.xml"
        private const val BUFFER = 128 * 1024
        private val DENSITIES = listOf(
            120 to "ldpi", 160 to "mdpi", 213 to "tvdpi", 240 to "hdpi",
            320 to "xhdpi", 480 to "xxhdpi", 640 to "xxxhdpi",
        )

        fun certificateDetailsOf(context: Context, path: String): List<Pair<String, String>> = runCatching {
            val signatures = signaturesOf(context, path)
            if (signatures.isEmpty()) return listOf("Signature" to "None found; this package is unsigned")
            val factory = CertificateFactory.getInstance("X.509")
            buildList {
                signatures.forEachIndexed { index, signature ->
                    val prefix = if (signatures.size == 1) "" else "Signer ${index + 1} "
                    val certificate = runCatching {
                        factory.generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
                    }.getOrNull()
                    if (certificate == null) {
                        add("${prefix}SHA-256" to PackageMetadata.fingerprint(signature.toByteArray()))
                        return@forEachIndexed
                    }
                    add("${prefix}Subject" to certificate.subjectX500Principal.name)
                    add("${prefix}Issuer" to
                        if (certificate.subjectX500Principal != certificate.issuerX500Principal) {
                            certificate.issuerX500Principal.name
                        } else {
                            "Self-signed"
                        })
                    add("${prefix}Valid from" to certificate.notBefore.toString())
                    add("${prefix}Valid until" to certificate.notAfter.toString())
                    add("${prefix}Algorithm" to certificate.sigAlgName)
                    add("${prefix}Serial" to certificate.serialNumber.toString(16).uppercase())
                    add("${prefix}SHA-256" to PackageMetadata.fingerprint(certificate.encoded))
                    add("${prefix}SHA-1" to PackageMetadata.fingerprint(certificate.encoded, "SHA-1"))
                }
            }
        }.getOrElse { listOf("Signature" to "Could not be read: ${it.message}") }

        private fun signaturesOf(context: Context, path: String): List<Signature> {
            val info = context.packageManager.getPackageArchiveInfo(path, PackageMetadata.signingFlags)
                ?: return emptyList()
            return PackageMetadata.signatures(info)
        }
    }

    private fun isBundle(entry: Entry): Boolean {
        val name = entry.name.lowercase()
        return name.endsWith(".xapk") || name.endsWith(".apks") || name.endsWith(".apkm")
    }

    fun isPackage(entry: Entry): Boolean =
        entry.mimeType == "application/vnd.android.package-archive" ||
            entry.name.lowercase().endsWith(".apk") || isBundle(entry)

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun inspect(entry: Entry): PackageBundle = withContext(Dispatchers.IO) {
        if (!isBundle(entry)) {
            return@withContext PackageBundle(listOf(
                PackagePart(entry.name, entry.size ?: 0, PartKind.BASE, "The application", recommended = true),
            ), xapk = false)
        }
        val parts = mutableListOf<PackagePart>()
        registry.provider(entry.ref).openRead(entry.ref).use { raw ->
            ZipInputStream(raw.buffered(BUFFER)).use { zip ->
                while (true) {
                    val item = zip.nextEntry ?: break
                    val name = item.name.substringAfterLast('/')
                    if (!item.isDirectory && name.isNotEmpty()) {
                        val kind = kindOf(name)
                        if (kind != PartKind.OTHER) {
                            parts += PackagePart(item.name, item.size.coerceAtLeast(0), kind,
                                describe(name, kind), recommended = suits(name, kind))
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        PackageBundle(parts.sortedBy { it.kind.ordinal }, xapk = true)
    }

    suspend fun manifestOf(entry: Entry): String = withContext(Dispatchers.IO) {
        val bytes = registry.provider(entry.ref).openRead(entry.ref).use { raw ->
            if (isBundle(entry)) baseManifest(raw) else memberOf(raw, MANIFEST)
        } ?: throw StorageException(
            StorageError.NOT_FOUND,
            if (isBundle(entry)) "This bundle has no base APK to read a manifest from."
            else "This file has no AndroidManifest.xml, so it is not an APK.",
        )
        AndroidBinaryXml.toText(AndroidBinaryXml.decode(bytes))
    }

    /** Installed base APKs are readable by path even when the device-root bookmark is hidden. */
    suspend fun manifestOfFile(path: String): String = withContext(Dispatchers.IO) {
        ZipFile(path).use { zip ->
            val member = zip.getEntry(MANIFEST) ?: error("This APK has no AndroidManifest.xml")
            val limit = 16 * 1024 * 1024
            require(member.size <= limit) { "This manifest is too large to display" }
            val bytes = zip.getInputStream(member).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "This manifest is too large to display" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            AndroidBinaryXml.toText(AndroidBinaryXml.decode(bytes))
        }
    }

    private fun memberOf(raw: InputStream, path: String): ByteArray? =
        ZipInputStream(raw.buffered(BUFFER)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: return null
                if (!item.isDirectory && item.name == path) return zip.readBytes()
                zip.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    private fun baseManifest(raw: InputStream): ByteArray? =
        ZipInputStream(raw.buffered(BUFFER)).use { outer ->
            while (true) {
                val item = outer.nextEntry ?: return null
                val name = item.name.substringAfterLast('/').lowercase()
                if (!item.isDirectory && name.endsWith(".apk") && kindOf(name) == PartKind.BASE) {
                    // memberOf closes its stream; the outer ZIP has to stay open.
                    val shielded = object : FilterInputStream(outer) {
                        override fun close() {}
                    }
                    memberOf(shielded, MANIFEST)?.let { return it }
                }
                outer.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    private fun kindOf(name: String): PartKind {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".obb") -> PartKind.OBB
            !lower.endsWith(".apk") -> PartKind.OTHER
            lower == "base.apk" || !lower.contains("config.") -> PartKind.BASE
            abiOf(lower) != null -> PartKind.ABI
            densityOf(lower) != null -> PartKind.DENSITY
            languageOf(lower) != null -> PartKind.LANGUAGE
            else -> PartKind.FEATURE
        }
    }

    private fun configSuffix(name: String): String? =
        name.lowercase().removeSuffix(".apk").substringAfter("config.", "").takeIf { it.isNotEmpty() }

    private fun abiOf(name: String): String? = configSuffix(name)?.takeIf {
        it in setOf("armeabi_v7a", "arm64_v8a", "x86", "x86_64", "armeabi", "mips")
    }

    private fun densityOf(name: String): String? = configSuffix(name)?.takeIf { suffix ->
        DENSITIES.any { it.second == suffix } || suffix == "nodpi" || suffix == "anydpi"
    }

    private fun languageOf(name: String): String? = configSuffix(name)?.takeIf { it.length in 2..3 && it.all { c -> c.isLetter() } }

    private fun describe(name: String, kind: PartKind) = when (kind) {
        PartKind.BASE -> "The application itself — always required"
        PartKind.ABI -> "Native code for ${abiOf(name)}"
        PartKind.DENSITY -> "Graphics for ${densityOf(name)} screens"
        PartKind.LANGUAGE -> "Language: ${Locale(languageOf(name).orEmpty()).displayLanguage}"
        PartKind.FEATURE -> "Optional feature module"
        PartKind.OBB -> "Expansion data"
        PartKind.OTHER -> "Not part of the package"
    }

    private fun suits(name: String, kind: PartKind): Boolean = when (kind) {
        PartKind.BASE, PartKind.FEATURE, PartKind.OBB -> true
        PartKind.ABI -> abiOf(name) in Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }
        PartKind.DENSITY -> densityOf(name) == deviceDensity() || densityOf(name) in setOf("nodpi", "anydpi")
        PartKind.LANGUAGE -> languageOf(name) == Locale.getDefault().language
        PartKind.OTHER -> false
    }

    private fun deviceDensity(): String {
        val dpi = context.resources.displayMetrics.densityDpi
        return DENSITIES.minByOrNull { abs(it.first - dpi) }?.second ?: "xxhdpi"
    }

    suspend fun install(entry: Entry, chosen: Set<String>, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        var obbSkipped = 0
        try {
            installer.openSession(sessionId).use { session ->
                if (isBundle(entry)) {
                    registry.provider(entry.ref).openRead(entry.ref).use { raw ->
                        ZipInputStream(raw.buffered(BUFFER)).use { zip ->
                            while (true) {
                                val item = zip.nextEntry ?: break
                                val name = item.name
                                if (!item.isDirectory && name in chosen) {
                                    if (name.endsWith(".obb", true)) {
                                        if (!writeObb(name, zip)) obbSkipped++
                                    } else {
                                        onStatus("Writing ${name.substringAfterLast('/')}")
                                        // Length -1 (unknown): a streamed ZIP member's size is not reliable.
                                        session.openWrite(name.substringAfterLast('/'), 0, -1).use { out ->
                                            zip.copyTo(out, BUFFER)
                                            session.fsync(out)
                                        }
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                } else {
                    onStatus("Writing ${entry.name}")
                    registry.provider(entry.ref).openRead(entry.ref).use { input ->
                        session.openWrite(entry.name, 0, entry.size ?: -1).use { out ->
                            input.copyTo(out, BUFFER)
                            session.fsync(out)
                        }
                    }
                }
                onStatus(if (obbSkipped > 0) "Committing; $obbSkipped expansion file(s) could not be placed" else "Committing")
                session.commit(resultSender(sessionId))
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun writeObb(name: String, source: InputStream): Boolean = runCatching {
        val leaf = name.substringAfterLast('/')
        // OBB names are main.<version>.<package>.obb.
        val owner = leaf.removeSuffix(".obb").split('.').drop(2).joinToString(".")
        if (owner.isBlank()) return false
        val directory = File(Environment.getExternalStorageDirectory(), "Android/obb/$owner")
        if (!directory.exists() && !directory.mkdirs()) return false
        File(directory, leaf).outputStream().use { source.copyTo(it, BUFFER) }
        true
    }.getOrDefault(false)

    private fun resultSender(sessionId: Int): IntentSender {
        val intent = Intent(ACTION_RESULT).setPackage(context.packageName).putExtra("session", sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    fun observe(onPrompt: (Intent) -> Unit, onFinished: (Boolean, String) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) onPrompt(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else onFinished(false, "Android did not offer the confirmation step")
                    }
                    PackageInstaller.STATUS_SUCCESS -> onFinished(true, "Installed")
                    else -> onFinished(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "Installation failed ($status)")
                }
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    fun stopObserving(receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun installedVersion(packageName: String): Pair<String?, Long>? =
        PackageMetadata.installedVersion(context.packageManager, packageName)

    fun packageOfFile(path: String): String? = runCatching {
        context.packageManager.getPackageArchiveInfo(path, 0)?.packageName
    }.getOrNull()
}
