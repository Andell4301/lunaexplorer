package com.lunaexplorer.app.ui

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal data class PipRequest(
    val aspect: Pair<Int, Int>,
    val sourceRect: Rect?,
    val playing: Boolean,
    val seekable: Boolean,
    val autoEnter: Boolean = true,
)

internal interface PipHost {
    val supported: Boolean
    val active: Boolean
    val phase: PipPhase
    fun arm(request: PipRequest?, onAction: (PipAction) -> Unit = {}, onClosed: () -> Unit = {})
    fun enter(): Boolean
    fun moveToBackground(): Boolean
}

internal val LocalPictureInPicture = staticCompositionLocalOf<PipHost?> { null }

internal fun pictureInPictureSupported(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        pictureInPictureAllowed(context)

/** Android's own entry check reads this app-op, which the per-app setting writes. */
private fun pictureInPictureAllowed(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
    val mode = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
        }
    } catch (_: RuntimeException) {
        return true
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

internal open class ActivityPictureInPicture(private val activity: ComponentActivity) : PipHost {
    // Seeded from the activity: a recreation while the window is pinned must still know it is pinned.
    private val exit = PipExit(if (activity.isInPictureInPictureMode) PipPhase.ACTIVE else PipPhase.NONE)
    private var request: PipRequest? = null
    private var onAction: (PipAction) -> Unit = {}
    private var onClosed: () -> Unit = {}
    private var launching = false
    private var movingToBackground = false
    private var registered = false
    private var armedOnce = false
    private var allowed by mutableStateOf(pictureInPictureSupported(activity))
    private var inPip by mutableStateOf(activity.isInPictureInPictureMode)

    override val supported: Boolean get() = allowed
    override val active: Boolean get() = inPip
    override val phase: PipPhase get() = exit.phase

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.getStringExtra(EXTRA_ACTION) ?: return
            PipAction.entries.firstOrNull { it.name == name }?.let { onAction(it) }
        }
    }

    init {
        activity.addOnPictureInPictureModeChangedListener { info ->
            inPip = info.isInPictureInPictureMode
            val state = activity.lifecycle.currentState
            val closed = exit.modeChanged(info.isInPictureInPictureMode,
                state.isAtLeast(Lifecycle.State.STARTED), state.isAtLeast(Lifecycle.State.RESUMED))
            if (closed) onClosed()
        }
        activity.addOnUserLeaveHintListener {
            if (Build.VERSION.SDK_INT < 31 && automaticEntry()) enter()
        }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    launching = false
                    movingToBackground = false
                    exit.resumed()
                    allowed = pictureInPictureSupported(activity)
                    apply()
                }
                Lifecycle.Event.ON_DESTROY -> unregister()
                else -> Unit
            }
        })
    }

    override fun arm(request: PipRequest?, onAction: (PipAction) -> Unit, onClosed: () -> Unit) {
        this.request = request
        this.onAction = onAction
        this.onClosed = onClosed
        if (request == null) unregister() else { armedOnce = true; register() }
        apply()
    }

    override fun enter(): Boolean {
        val armed = request ?: return false
        return try {
            enterMode(params(armed))
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun moveToBackground(): Boolean {
        val previous = movingToBackground
        movingToBackground = true
        apply()
        val moved = try { moveTaskToBack() } catch (_: RuntimeException) { false }
        if (!moved) {
            movingToBackground = previous
            apply()
        }
        return moved
    }

    /** Anything Luna launches itself pauses the activity, which auto-enter would otherwise take for a task switch. */
    fun launching(on: Boolean) {
        launching = on
        apply()
    }

    // Robolectric does not shadow setPictureInPictureParams.
    protected open fun setParams(params: PictureInPictureParams) = activity.setPictureInPictureParams(params)

    protected open fun enterMode(params: PictureInPictureParams): Boolean =
        activity.enterPictureInPictureMode(params)

    protected open fun moveTaskToBack(): Boolean = activity.moveTaskToBack(true)

    private fun automaticEntry(): Boolean = autoEnterPip(
        armed = request != null,
        playing = request?.playing == true,
        launching = launching || movingToBackground,
        autoEnter = request?.autoEnter == true,
    )

    private fun apply() {
        if (!armedOnce) return
        val armed = request
        try {
            setParams(if (armed == null) cleared() else params(armed))
        } catch (_: RuntimeException) {
        }
    }

    private fun params(armed: PipRequest): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(armed.aspect.first, armed.aspect.second))
            .setSourceRectHint(armed.sourceRect)
            .setActions(remoteActions(armed))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(automaticEntry())
        }
        return builder.build()
    }

    private fun cleared(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setActions(emptyList())
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(false)
        return builder.build()
    }

    private fun remoteActions(armed: PipRequest): List<RemoteAction> =
        pipActions(armed.seekable, activity.maxNumPictureInPictureActions).map { action ->
            val (icon, label) = when (action) {
                PipAction.BACK -> androidx.media3.session.R.drawable.media3_icon_skip_back_10 to "Back 10 seconds"
                PipAction.FORWARD -> androidx.media3.session.R.drawable.media3_icon_skip_forward_10 to "Forward 10 seconds"
                PipAction.TOGGLE ->
                    if (armed.playing) androidx.media3.session.R.drawable.media3_icon_pause to "Pause"
                    else androidx.media3.session.R.drawable.media3_icon_play to "Play"
            }
            RemoteAction(Icon.createWithResource(activity, icon), label, label,
                PendingIntent.getBroadcast(activity, action.ordinal,
                    Intent(ACTION_PIP).setPackage(activity.packageName).putExtra(EXTRA_ACTION, action.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }

    private fun register() {
        if (registered) return
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        activity.unregisterReceiver(receiver)
        registered = false
    }

    private companion object {
        const val ACTION_PIP = "com.lunaexplorer.app.PICTURE_IN_PICTURE"
        const val EXTRA_ACTION = "action"
    }
}
