package com.lunaexplorer.app.storage.transfer

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProviderContract

abstract class TransferStorageProviderContract(protocol: TransferProtocol) : StorageProviderContract() {
    private val account = TransferAccount(id = "test", name = "Server", host = "test.local", protocol = protocol)
    private val connector = FakeTransferConnector()
    override val provider = TransferStorageProvider(protocol.providerId, { listOf(account) }, { TransferCredentials(password = "secret") }, connector)
    private var next = 0

    override suspend fun freshRoot(): NodeRef =
        provider.create(provider.roots().single().ref, "test-${next++}", directory = true).ref
}

class SftpStorageProviderContractTest : TransferStorageProviderContract(TransferProtocol.SFTP)
class FtpStorageProviderContractTest : TransferStorageProviderContract(TransferProtocol.FTP)
