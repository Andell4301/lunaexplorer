package com.lunaexplorer.app.storage

import com.lunaexplorer.core.NodeRef

data class ShellLocation(val accountId: String, val path: String)

interface ShellAddressable {
    val supportsShell: Boolean
    suspend fun shellLocation(ref: NodeRef): ShellLocation?
}
