package com.lunaexplorer.app.storage.smb

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProviderContract
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbStorageProviderContractTest : StorageProviderContract() {
    // Server-scoped account, so the contract's root and parentOf tests go through share enumeration.
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", username = "me")
    private val connector = FakeSmbConnector()
    override val provider = SmbStorageProvider(
        accounts = { listOf(account) },
        withPassword = { it.copy(password = "secret") },
        connector = connector,
    )
    private var fresh = 0

    override suspend fun freshRoot(): NodeRef =
        provider.create(NodeRef("smb", "nas:media:"), "fresh-${fresh++}", directory = true).ref
}
