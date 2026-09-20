package com.lunaexplorer.app.storage.shizuku

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface

class FakeShizuku : ShizukuGateway {
    var installed = false
    var running = false
    var permitted = false
    var refused = false
    var requests = 0
    var binds = 0
    var unbinds = 0
    /** Null stands for a helper that never starts, which Shizuku does not report. */
    var helper: (() -> IBinder?) = { MortalHelper() }
    private var changed: () -> Unit = {}
    private var lost: (() -> Unit)? = null
    private var handed: IBinder? = null

    fun start(permitted: Boolean = this.permitted) { installed = true; running = true; this.permitted = permitted; changed() }
    fun stop() { running = false; (handed as? MortalHelper)?.alive = false; lost?.invoke(); changed() }
    fun grant() { permitted = true; changed() }
    /** Allowed from inside Shizuku's own app, which tells the client nothing. */
    fun grantSilently() { permitted = true; refused = false }
    fun killHelper() { (handed as? MortalHelper)?.alive = false; lost?.invoke() }
    /** Real Shizuku tells every connection about every helper's death, the one in hand or not. */
    fun echoLoss() { lost?.invoke() }

    override fun installed() = installed
    override fun running() = running
    override fun permitted() = permitted
    override fun refused() = refused
    override fun requestPermission() { requests++ }
    override fun bind(onBound: (IBinder) -> Unit, onLost: () -> Unit): Boolean {
        binds++; lost = onLost
        helper()?.let { handed = it; onBound(it) }
        return true
    }
    override fun unbind() { unbinds++ }
    override fun watch(changed: () -> Unit) { this.changed = changed }
}

/** The real helper behind a binder that can be made to look dead. */
class MortalHelper(private val answers: Boolean = true) : Binder() {
    @Volatile var alive = true
    private val real = FileHelperService()
    private val face = object : IFileHelper by real {
        override fun protocol(): Int = if (answers) real.protocol() else throw DeadObjectException("The helper died as it started")
        override fun asBinder(): IBinder = this@MortalHelper
    }
    override fun queryLocalInterface(descriptor: String): IInterface = face
    override fun pingBinder(): Boolean = alive
}
