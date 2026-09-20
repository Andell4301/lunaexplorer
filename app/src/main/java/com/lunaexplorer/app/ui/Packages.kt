package com.lunaexplorer.app.ui

import android.app.Application
import android.content.Intent
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.AppFilter
import com.lunaexplorer.app.storage.InstalledApp
import com.lunaexplorer.app.storage.PackageBundle
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.NonClosing
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Packages(
    private val application: Application,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val resolver: PathResolver,
    private val state: StateFlow<BrowserState>,
    private val message: (String) -> Unit,
    private val reload: () -> Unit,
) {

    fun isPackage(entry: Entry) = graph.installer.isPackage(entry)
    fun canInstall() = graph.installer.canInstall()
    fun requestInstallPermission() = start(graph.installer.unknownSourcesIntent())

    suspend fun inspect(entry: Entry): PackageBundle = graph.installer.inspect(entry)

    suspend fun manifestOf(entry: Entry): String = graph.installer.manifestOf(entry)

    suspend fun manifestOf(app: InstalledApp): String =
        graph.installer.manifestOfFile(requireNotNull(app.sourceDir) { "This app has no readable base APK" })

    suspend fun packageNameOf(entry: Entry): String? = withContext(Dispatchers.IO) {
        resolver.pathOf(entry.ref)?.let { graph.installer.packageOfFile(it) }
    }

    fun installedVersionOf(packageName: String) = graph.installer.installedVersion(packageName)

    fun install(
        entry: Entry,
        parts: Set<String>,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            // Register before committing so the installation confirmation broadcast has a receiver.
            val receiver = graph.installer.observe(
                onPrompt = { intent -> start(intent) },
                onFinished = { ok, text ->
                    message(text)
                    onFinished(ok, text)
                    if (ok) reload()
                },
            )
            try {
                graph.installer.install(entry, parts, onStatus)
            } catch (error: Exception) {
                onFinished(false, error.message ?: "The package could not be installed")
            } finally {
                scope.launch {
                    delay(120_000)
                    graph.installer.stopObserving(receiver)
                }
            }
        }
    }

    fun listApps(filter: AppFilter): Flow<List<InstalledApp>> = graph.apps.list(filter)

    suspend fun appDetail(packageName: String) = graph.apps.detail(packageName)

    fun launchable(packageName: String): Any? = graph.apps.launchIntent(packageName)

    fun launch(packageName: String) = start(graph.apps.launchIntent(packageName))
    fun openSettings(packageName: String) = start(graph.apps.settingsIntent(packageName))
    fun openStore(packageName: String) = start(graph.apps.storeIntent(packageName))
    fun uninstall(packageName: String) = start(graph.apps.uninstallIntent(packageName))

    private fun start(intent: Intent?) {
        if (intent == null) { message("This app has nothing to open"); return }
        try {
            application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            message("Nothing on this device can handle that")
        }
    }

    fun extract(app: InstalledApp, into: String?, onDone: () -> Unit) {
        scope.launch {
            val destination = if (into.isNullOrBlank()) state.value.location?.ref
            else resolver.locate(into)?.location?.ref
            if (destination == null) {
                message("No storage Luna can reach contains ${into?.ifBlank { null } ?: "that folder"}")
                onDone(); return@launch
            }
            try {
                val all = graph.apps.componentsOf(app)
                // Unreadable splits are skipped; the rest is still exported.
                val components = withContext(Dispatchers.IO) {
                    all.filter { (_, path) -> File(path).canRead() }
                }
                if (components.isEmpty()) {
                    throw IllegalStateException(
                        "None of ${app.label}'s ${all.size} package files could be read",
                    )
                }
                val safeLabel = app.label.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { app.packageName }
                val version = app.versionName?.replace(Regex("[^A-Za-z0-9._-]"), "") ?: app.versionCode.toString()
                withContext(Dispatchers.IO) {
                    val provider = graph.providers.provider(destination)
                    if (components.size == 1) {
                        writeInto(provider, destination, "$safeLabel-$version.apk") { output ->
                            File(components.first().second).inputStream().use { it.copyTo(output) }
                        }
                    } else {
                        writeInto(provider, destination, "$safeLabel-$version.xapk") { output ->
                            // The zip must not close the provider's stream, which writeInto owns.
                            val zip = ZipOutputStream(NonClosing(output))
                            components.forEach { (name, path) ->
                                zip.putNextEntry(ZipEntry(name))
                                File(path).inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                            // Bundle installers read the split list from manifest.json.
                            zip.putNextEntry(ZipEntry("manifest.json"))
                            zip.write(bundleManifest(app, components).toByteArray())
                            zip.closeEntry()
                            zip.finish()
                            zip.flush()
                        }
                    }
                }
                message("Extracted ${app.label}")
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message(
                    "Could not extract ${app.label}: " +
                        (error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName),
                )
            } finally {
                onDone()
            }
        }
    }

    /** manifest.json of the XAPK layout. */
    private fun bundleManifest(app: InstalledApp, components: List<Pair<String, String>>): String {
        val splits = components.filter { it.first != "base.apk" }
            .joinToString(",") { """{"file":"${it.first}","id":"${it.first.removeSuffix(".apk")}"}""" }
        return """{"package_name":"${app.packageName}","name":"${app.label.replace("\"", "")}",""" +
            """"version_code":"${app.versionCode}","version_name":"${app.versionName ?: ""}",""" +
            """"split_apks":[$splits]}"""
    }

    private suspend fun writeInto(
        provider: StorageProvider,
        parent: NodeRef,
        name: String,
        block: (OutputStream) -> Unit,
    ) {
        val staged = provider.create(parent, ".luna-extract-${UUID.randomUUID()}.partial", directory = false)
        try {
            provider.openWrite(staged.ref).use(block)
            provider.commit(staged.ref, parent, name)
        } catch (error: Throwable) {
            runCatching { provider.delete(staged.ref) }
            throw error
        }
    }
}
