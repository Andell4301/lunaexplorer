package com.lunaexplorer.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import com.lunaexplorer.app.storage.OpenCandidate
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import com.lunaexplorer.app.storage.Shortcuts
import com.lunaexplorer.app.storage.ViewerAdvertising
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lunaexplorer.app.storage.DeviceStorage
import com.lunaexplorer.app.storage.ExternalStreamService
import com.lunaexplorer.app.storage.storageUri
import com.lunaexplorer.app.ui.ActivityPictureInPicture
import com.lunaexplorer.app.ui.BrowserViewModel
import com.lunaexplorer.app.ui.LocalPictureInPicture
import com.lunaexplorer.app.ui.LunaActions
import com.lunaexplorer.app.ui.LunaApp
import com.lunaexplorer.app.model.ThemeMode
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.app.work.ProcedureNotifications
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID

class MainActivity : FragmentActivity() {
    private val requestedFolder = mutableStateOf<String?>(null)
    private val requestedFile = mutableStateOf<ViewerAdvertising.ViewRequest?>(null)
    private val requestedQueue = mutableStateOf(false)
    private lateinit var pictureInPicture: ActivityPictureInPicture

    // App-launched activities also trigger auto-enter PiP; startActivity routes through this override.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (::pictureInPicture.isInitialized) pictureInPicture.launching(true)
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (error: RuntimeException) {
            // Nothing pauses this activity when the launch fails, so nothing would clear the flag.
            if (::pictureInPicture.isInitialized) pictureInPicture.launching(false)
            throw error
        }
    }

    // No negative button: BiometricPrompt rejects one when DEVICE_CREDENTIAL is allowed.
    private fun promptForVault(onDone: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Luna's credentials")
            .setSubtitle("The passwords and keys for your accounts are kept behind this")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Shortcuts.folderFrom(intent)?.let { requestedFolder.value = it }
        ViewerAdvertising.viewRequestFrom(intent)?.let { requestedFile.value = it }
        if (intent.action == ProcedureNotifications.ACTION) requestedQueue.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedFolder.value = Shortcuts.folderFrom(intent)
        // On recreation the ViewModel already holds whatever this intent opened.
        if (savedInstanceState == null) requestedFile.value = ViewerAdvertising.viewRequestFrom(intent)
        if (savedInstanceState == null) requestedQueue.value = intent.action == ProcedureNotifications.ACTION
        pictureInPicture = ActivityPictureInPicture(this)
        enableEdgeToEdge()
        setContent {
            val vm: BrowserViewModel = viewModel(factory = remember {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        BrowserViewModel(application, (application as LunaApplication).graph) as T
                }
            })
            val state by vm.state.collectAsStateWithLifecycle()
            val pendingQueue by requestedQueue
            LaunchedEffect(state.ready, pendingQueue) {
                if (state.ready && pendingQueue) {
                    requestedQueue.value = false
                    vm.showOverlay(Overlay.Queue)
                }
            }

            // Wait for the session to be restored, or the restored tab would replace these.
            val pending by requestedFolder
            LaunchedEffect(state.ready, pending) {
                val path = pending
                if (state.ready && path != null) {
                    requestedFolder.value = null
                    vm.goTo(path)
                }
            }
            val pendingFile by requestedFile
            LaunchedEffect(state.ready, pendingFile) {
                val request = pendingFile
                if (state.ready && request != null) {
                    requestedFile.value = null
                    vm.openExternal(request.uri, request.type)
                }
            }
            val systemDark = isSystemInDarkTheme()
            val dark = state.preferences.theme == ThemeMode.DARK || (state.preferences.theme == ThemeMode.SYSTEM && systemDark)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) result.data?.let { data -> data.data?.let { vm.grantFolder(it, data.flags) } }
            }
            // The file picker can outlive the Activity, so the pending request is kept in the
            // ViewModel; a callback held by this composition would be gone when the result arrives.
            val settingsWriter = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri != null) vm.transfer.onExportTarget(uri) else vm.transfer.cancelExport()
            }
            val settingsReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.transfer.openForImport(uri) { }
            }
            val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshAccess() }
            val fullAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                vm.refreshAccess()
                val fallback = DeviceStorage.legacyPermissions()
                if (!DeviceStorage.hasFullAccess(this@MainActivity) && fallback.isNotEmpty()) legacyStorage.launch(fallback)
            }
            LifecycleResumeEffect(Unit) {
                vm.refreshAccess()
                onPauseOrDispose { }
            }

            var notificationRequested by rememberSaveable { mutableStateOf(false) }
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                if (!allowed) vm.showMessage("Enable notifications in Android settings to see background progress.")
            }
            val working = state.operations.any { it.status == "QUEUED" || it.status == "RUNNING" }
            LaunchedEffect(working) {
                if (!notificationRequested && working && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationRequested = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            CompositionLocalProvider(LocalPictureInPicture provides pictureInPicture) {
                LunaApp(vm, LunaActions(
                    grantFolder = { path ->
                        try {
                            folderPicker.launch(DeviceStorage.pickerIntent(path))
                        } catch (_: ActivityNotFoundException) {
                            vm.showMessage("This device does not provide Android's folder picker")
                        }
                    },
                    open = { entry, share -> openEntry(entry, share, vm) },
                    openWith = { entry, candidate, type -> openEntry(entry, share = false, vm = vm, typeOverride = type, candidate = candidate) },
                    shareReport = { id -> shareReport(id, vm) },
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationRequested = true
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    requestFullAccess = {
                        val intents = DeviceStorage.fullAccessSettingsIntents(this)
                        if (intents.isEmpty()) {
                            val permissions = DeviceStorage.legacyPermissions()
                            if (permissions.isEmpty()) vm.showMessage("This Android version has no broader storage access to grant")
                            else legacyStorage.launch(permissions)
                        } else if (!launchFirst(intents) { fullAccess.launch(it) }) {
                            vm.showMessage("This device does not expose the all-files access setting")
                        }
                    },
                    unlockVault = { onDone -> promptForVault(onDone) },
                    saveSettings = { name ->
                        try {
                            settingsWriter.launch(name)
                        } catch (_: ActivityNotFoundException) {
                            vm.transfer.cancelExport()
                            vm.showMessage("This device has nowhere to save the file")
                        }
                    },
                    openSettings = {
                        try {
                            // Some providers hand JSON out under a generic type, so take anything.
                            settingsReader.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } catch (_: ActivityNotFoundException) {
                            vm.showMessage("This device has no file picker")
                        }
                    },
                    openSystemBrowser = { path ->
                        if (!launchFirst(DeviceStorage.systemBrowseIntents(this, path)) { startActivity(it) }) {
                            try {
                                folderPicker.launch(DeviceStorage.pickerIntent(path))
                            } catch (_: ActivityNotFoundException) {
                                vm.showMessage("No installed app can browse this folder")
                            }
                        }
                    },
                ))
            }
        }
    }

    private inline fun launchFirst(intents: List<Intent>, launch: (Intent) -> Unit): Boolean {
        for (intent in intents) {
            try {
                launch(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return false
    }

    private fun shareReport(id: String, vm: BrowserViewModel) {
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "reports").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                    File(directory, "luna-operation-${UUID.randomUUID()}.txt").also {
                        (application as LunaApplication).graph.database.exportReport(id, it)
                    }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", report)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Luna operation report", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share operation report"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { vm.showMessage("Could not export report: ${error.message}") }
        }
    }

    private fun openEntry(
        entry: Entry,
        share: Boolean,
        vm: BrowserViewModel,
        typeOverride: String? = null,
        candidate: OpenCandidate? = null,
    ) {
        lifecycleScope.launch {
            var handoff: Closeable? = null
            try {
                val graph = (application as LunaApplication).graph
                val current = withContext(Dispatchers.IO) { graph.providers.provider(entry.ref).stat(entry.ref) }
                require(!current.directory && Capability.READ in current.capabilities) { "Choose a readable file to open or share" }
                val uri = storageUri(this@MainActivity, graph.providers, current.ref)
                val declared = typeOverride?.takeIf { it.contains('/') } ?: current.mimeType
                val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                    type = declared; putExtra(Intent.EXTRA_STREAM, uri)
                } else Intent(Intent.ACTION_VIEW).setDataAndType(uri, declared)
                intent.clipData = ClipData.newRawUri(current.name, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val streamingType = declared.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
                    ?: current.mimeType
                handoff = ExternalStreamService.prepare(this@MainActivity, uri, current.name, streamingType, share)
                if (candidate != null) {
                    intent.setComponent(ComponentName(candidate.packageName, candidate.activityName))
                    startActivity(intent)
                } else {
                    startActivity(Intent.createChooser(intent, if (share) "Share ${current.name}" else "Open ${current.name}"))
                }
                // Opening the URI acquires descriptor leases; an unused handoff expires automatically.
                handoff = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ActivityNotFoundException) { vm.showMessage("No installed app can open this file type") }
            catch (error: Exception) { vm.showMessage("Could not ${if (share) "share" else "open"} the file: ${error.message}") }
            finally { handoff?.close() }
        }
    }
}
