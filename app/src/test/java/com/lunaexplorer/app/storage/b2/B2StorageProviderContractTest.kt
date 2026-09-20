package com.lunaexplorer.app.storage.b2

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProviderContract
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class B2StorageProviderContractTest : StorageProviderContract() {
    @get:Rule val temporary = TemporaryFolder()

    // Account-wide, so the contract's root and parentOf tests go through bucket enumeration.
    private val account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id")
    override val provider by lazy {
        B2StorageProvider(
            accounts = { listOf(account) },
            withKey = { it.copy(applicationKey = "secret") },
            connector = FakeB2Connector(),
            cache = B2ListingCache(temporary.newFolder("listings")),
            staging = temporary.newFolder("uploads"),
        )
    }
    private var fresh = 0

    override suspend fun freshRoot(): NodeRef =
        provider.create(NodeRef("b2", "cloud:media:"), "fresh-${fresh++}", directory = true).ref
}
