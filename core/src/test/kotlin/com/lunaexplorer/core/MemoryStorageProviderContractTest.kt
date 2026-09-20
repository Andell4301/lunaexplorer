package com.lunaexplorer.core

class MemoryStorageProviderContractTest : StorageProviderContract() {
    override val provider = MemoryStorageProvider("memory")
    private var counter = 0
    override suspend fun freshRoot(): NodeRef = provider.folder(provider.root, "root${counter++}")
}
