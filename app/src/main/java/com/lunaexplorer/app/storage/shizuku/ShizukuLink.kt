package com.lunaexplorer.app.storage.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.pm.PackageInfoCompat
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.storage.LocalRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

enum class HelperState {
    OFF, NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION,
    /** The user told Shizuku not to ask again, so only its own app can allow Luna now. */
    REFUSED,
    CONNECTING, READY, FAILED,
}

interface ShizukuGateway {
    fun installed(): Boolean
    fun running(): Boolean
    fun permitted(): Boolean
    /** The user told Shizuku not to ask again, so only its own app can allow Luna now. */
    fun refused(): Boolean
    fun requestPermission()
    /** [onBound] gets the helper's binder; [onLost] is called when a helper's process goes. False when Shizuku could not even be asked. */
    fun bind(onBound: (IBinder) -> Unit, onLost: () -> Unit): Boolean
    fun unbind()
    /** Called on the main thread whenever [running] or [permitted] may have changed. */
    fun watch(changed: () -> Unit)
}

// Serialize decisions off the main thread because Shizuku binder calls can block.
class ShizukuLink(
    private val gateway: ShizukuGateway,
    scope: CoroutineScope,
    private val roots: () -> List<LocalRoot>,
    /** Shizuku says nothing when a helper fails to start, so the link stops waiting by itself. */
    private val startTimeoutMillis: Long = 20_000,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(scope.coroutineContext + serial)
    private val _state = MutableStateFlow(HelperState.OFF)
    val state: StateFlow<HelperState> = _state

    /** 0 when the helper runs as root, 2000 as the adb shell; null until it has said. */
    @Volatile var uid: Int? = null
        private set
    @Volatile internal var files: HelperFiles? = null
        private set
    @Volatile private var enabled = false
    private var binding = false
    private var helper: IBinder? = null
    /** Starts in a row that came to nothing. At [GIVE_UP] the link waits to be asked again, so a helper that dies at once is not restarted forever. */
    private var failures = 0
    private var waiting: Job? = null
    /** Which start is current, so a helper or a timer from one that was given up on changes nothing. */
    private var attempt = 0

    init { gateway.watch(::evaluate) }

    /** With [ask], Shizuku is asked to allow Luna if that turns out to be what is missing. */
    fun setEnabled(on: Boolean, ask: Boolean = false) {
        scope.launch {
            if (enabled != on) { enabled = on; failures = 0 }
            decide()
            if (on && ask && _state.value == HelperState.NEEDS_PERMISSION) gateway.requestPermission()
        }
    }

    fun retry() { scope.launch { failures = 0; decide() } }

    /** Also for coming back to Luna: allowing it in Shizuku's own app sends no word. */
    fun evaluate() { scope.launch { decide() } }

    fun requestPermission() { scope.launch { gateway.requestPermission() } }

    /** Returns once the link is no longer in the middle of starting the helper, which the start timeout bounds. */
    suspend fun settled() {
        // Behind whatever was asked of the link before this.
        withContext(serial) { }
        state.first { it != HelperState.CONNECTING }
    }

    /** Roots are part of what a reference means, so the helper must hold the same ones. */
    fun rootsChanged() {
        val current = files ?: return
        scope.launch(Dispatchers.IO) {
            try { current.setRoots(roots()) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { DebugLog.w(TAG, error) { "Could not pass the roots on: ${error.message}" } }
        }
    }

    private fun decide() {
        if (!enabled) {
            if (binding || files != null) { gateway.unbind(); DebugLog.i(TAG) { "Helper released" } }
            release()
            _state.value = HelperState.OFF
            return
        }
        val next = when {
            !gateway.running() -> if (gateway.installed()) HelperState.NOT_RUNNING else HelperState.NOT_INSTALLED
            !gateway.permitted() -> if (gateway.refused()) HelperState.REFUSED else HelperState.NEEDS_PERMISSION
            files != null -> HelperState.READY
            failures >= GIVE_UP -> HelperState.FAILED
            else -> HelperState.CONNECTING
        }
        if (next != HelperState.READY && next != HelperState.CONNECTING) release()
        // Published before binding: a bind that fails at once comes back through [lost] and decides again.
        _state.value = next
        if (next == HelperState.CONNECTING && !binding) {
            binding = true
            val mine = attempt
            DebugLog.i(TAG) { "Starting the helper" }
            waiting?.cancel()
            waiting = scope.launch {
                delay(startTimeoutMillis)
                if (mine != attempt || !binding || files != null) return@launch
                DebugLog.w(TAG) { "No helper after $startTimeoutMillis ms" }
                gateway.unbind()
                release()
                failures = GIVE_UP
                decide()
            }
            if (!gateway.bind(::bound, ::lost)) {
                release()
                failures++
                decide()
            }
        }
    }

    private fun release() {
        attempt++
        binding = false; files = null; uid = null; helper = null
        waiting?.cancel(); waiting = null
    }

    private fun bound(binder: IBinder) {
        scope.launch {
            if (!binding) return@launch
            val mine = attempt
            helper = binder
            val made = HelperFiles(LOCAL, IFileHelper.Stub.asInterface(binder))
            try {
                // A helper left over from another build would misread every call.
                val speaks = made.protocol()
                if (speaks != Wire.PROTOCOL) throw IllegalStateException("The helper speaks protocol $speaks, not ${Wire.PROTOCOL}")
                made.setRoots(roots())
                val runsAs = made.uid()
                val about = made.describe()
                // The questions above suspend, and the helper may have been lost or let go meanwhile.
                if (!enabled || mine != attempt) return@launch
                files = made; uid = runsAs; failures = 0
                waiting?.cancel(); waiting = null
                DebugLog.i(TAG) { "Helper ready: $about" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.w(TAG, error) { "The helper did not start: ${error.message}" }
                if (mine != attempt) return@launch
                gateway.unbind()
                release()
                failures++
            }
            decide()
        }
    }

    // Shizuku also reports deaths to old connections; only the current helper counts.
    private fun lost() {
        scope.launch {
            val current = helper
            if (current == null || current.pingBinder()) return@launch
            DebugLog.i(TAG) { "Helper gone" }
            if (files == null) failures++
            release()
            decide()
        }
    }

    private companion object {
        const val TAG = "Shizuku"
        const val LOCAL = "local"
        const val GIVE_UP = 3
    }
}

// Shizuku retains connections after unbinding, so reuse one to avoid duplicate callbacks.
class SystemShizuku(private val context: Context) : ShizukuGateway {
    @Volatile private var onBound: ((IBinder) -> Unit)? = null
    @Volatile private var onLost: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) onBound?.invoke(service) else onLost?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) { onLost?.invoke() }
    }

    private val helper by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        Shizuku.UserServiceArgs(ComponentName(context.packageName, FileHelperService::class.java.name))
            // Dies with Luna, and is replaced when Luna is updated.
            .daemon(false)
            .processNameSuffix("files")
            .tag("files")
            .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .version(PackageInfoCompat.getLongVersionCode(info).toInt())
    }

    override fun installed(): Boolean = try { context.packageManager.getPackageInfo(MANAGER, 0); true } catch (_: Exception) { false }

    override fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    // Before version 11 permission was an ordinary runtime permission, and a user service did not exist.
    override fun permitted(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    override fun refused(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Exception) { false }

    override fun requestPermission() {
        try { Shizuku.requestPermission(REQUEST) } catch (error: Exception) {
            DebugLog.w(TAG, error) { "Could not ask Shizuku: ${error.message}" }
        }
    }

    override fun bind(onBound: (IBinder) -> Unit, onLost: () -> Unit): Boolean {
        this.onBound = onBound
        this.onLost = onLost
        return try { Shizuku.bindUserService(helper, connection); true } catch (error: Exception) {
            DebugLog.w(TAG, error) { "Could not ask for the helper: ${error.message}" }
            false
        }
    }

    override fun unbind() {
        try { Shizuku.unbindUserService(helper, connection, true) } catch (error: Exception) {
            DebugLog.w(TAG, error) { "Could not let the helper go: ${error.message}" }
        }
    }

    override fun watch(changed: () -> Unit) {
        watcher = changed
        if (!listening.compareAndSet(false, true)) return
        try {
            Shizuku.addBinderReceivedListenerSticky { watcher?.invoke() }
            Shizuku.addBinderDeadListener { watcher?.invoke() }
            Shizuku.addRequestPermissionResultListener { _, _ -> watcher?.invoke() }
        } catch (error: Exception) {
            DebugLog.w(TAG, error) { "Could not listen for Shizuku: ${error.message}" }
        }
    }

    private companion object {
        const val TAG = "Shizuku"
        const val MANAGER = "moe.shizuku.privileged.api"
        const val REQUEST = 7341
        /** Shizuku holds listeners in statics and never lets go, so they are added once and aimed at whoever watches now. */
        val listening = AtomicBoolean()
        @Volatile var watcher: (() -> Unit)? = null
    }
}
