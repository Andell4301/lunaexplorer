package com.lunaexplorer.app

import android.app.Application
import com.lunaexplorer.app.data.LunaDatabase
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.storage.ApkInstaller
import com.lunaexplorer.app.storage.AppInventory
import com.lunaexplorer.app.storage.CredentialVault
import com.lunaexplorer.app.storage.RemoteFailure
import com.lunaexplorer.app.storage.DeviceStorage
import com.lunaexplorer.app.storage.FileInspector
import com.lunaexplorer.app.storage.IncomingStorageProvider
import com.lunaexplorer.app.storage.KeystoreVaultKeys
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.LocalStorageProvider
import com.lunaexplorer.app.storage.MediaIndex
import com.lunaexplorer.app.storage.OpenTargets
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.app.storage.SafStorageProvider
import com.lunaexplorer.app.storage.Shortcuts
import com.lunaexplorer.app.storage.StorageAnalysis
import com.lunaexplorer.app.storage.SystemNames
import com.lunaexplorer.app.storage.SystemProbe
import com.lunaexplorer.app.storage.ThumbnailLoader
import com.lunaexplorer.app.storage.VaultKeys
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.B2Connector
import com.lunaexplorer.app.storage.b2.B2ListingCache
import com.lunaexplorer.app.storage.b2.B2SdkConnector
import com.lunaexplorer.app.storage.b2.B2StorageProvider
import com.lunaexplorer.app.storage.shizuku.AssistedLocalProvider
import com.lunaexplorer.app.storage.shizuku.ShizukuGateway
import com.lunaexplorer.app.storage.shizuku.ShizukuLink
import com.lunaexplorer.app.storage.shizuku.SystemShizuku
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.app.storage.smb.SmbConnector
import com.lunaexplorer.app.storage.smb.SmbStorageProvider
import com.lunaexplorer.app.storage.smb.SmbjConnector
import com.lunaexplorer.app.storage.transfer.FtpConnector
import com.lunaexplorer.app.storage.transfer.SshjConnector
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferConnector
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.TransferStorageProvider
import com.lunaexplorer.app.storage.transfer.TransferProtocol
import com.lunaexplorer.app.storage.storageUri
import com.lunaexplorer.app.work.OperationQueue
import com.lunaexplorer.core.ArchiveEngine
import com.lunaexplorer.core.ArchiveProvider
import com.lunaexplorer.core.DigestEngine
import com.lunaexplorer.core.FolderSizer
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationEngine
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.RecycleBin
import com.lunaexplorer.core.SearchEngine
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.sweepArchiveStaging
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(
    private val application: Application,
    probe: PathProbe = SystemProbe,
    smbConnector: SmbConnector = SmbjConnector(),
    b2Connector: B2Connector = B2SdkConnector(),
    vaultKeys: VaultKeys = KeystoreVaultKeys(application),
    shizukuGateway: ShizukuGateway = SystemShizuku(application),
    ftpConnector: TransferConnector = FtpConnector(),
    sftpConnector: TransferConnector = SshjConnector(),
) {
    val debugLog: DebugLog = DebugLog.installed ?: DebugLog.install(application)
    val local = LocalStorageProvider(names = SystemNames(application), probe = probe)
    val saf = SafStorageProvider(application)
    // Swept at startup: a process killed mid-read leaves its staged archive copies behind.
    private val archiveStaging: File = File(application.cacheDir, "archive-stage")
        .apply { mkdirs(); sweepArchiveStaging(this) }
    // The registry is looked up lazily because it is built below and contains this provider.
    val insideArchives: ArchiveProvider = ArchiveProvider({ providers }, stagingDirectory = archiveStaging)
    val vault = CredentialVault(File(application.filesDir, "vault/credentials"), vaultKeys)
    /** Kept in sync with the session by the ViewModel. */
    @Volatile var smbAccounts: List<SmbAccount> = emptyList()
    val smb = SmbStorageProvider(
        accounts = { smbAccounts },
            withPassword = { account -> vault.secrets.value?.let { account.copy(password = it.smbPasswords[account.id].orEmpty()) } },
        connector = smbConnector,
    )
    @Volatile var b2Accounts: List<B2Account> = emptyList()
    val b2 = B2StorageProvider(
        accounts = { b2Accounts },
        withKey = { account -> vault.secrets.value?.let { account.copy(applicationKey = it.b2Keys[account.id].orEmpty()) } },
        connector = b2Connector,
        cache = B2ListingCache(File(application.cacheDir, "b2-listings")),
        // Swept at startup: a process killed mid-upload leaves the part it was holding behind.
        staging = File(application.cacheDir, "b2-uploads").apply { deleteRecursively(); mkdirs() },
    )
    @Volatile var transferAccounts: List<TransferAccount> = emptyList()
    private fun transferCredentials(account: TransferAccount): TransferCredentials {
        if (account.anonymous && account.protocol == TransferProtocol.FTP) {
            return TransferCredentials()
        }
        val secrets = vault.secrets.value ?: throw RemoteFailure(StorageError.AUTH, "The vault is locked")
        return secrets.transferCredentials[account.id] ?: TransferCredentials()
    }
    val ftp = TransferStorageProvider("ftp", { transferAccounts }, ::transferCredentials, ftpConnector)
    val sftp = TransferStorageProvider("sftp", { transferAccounts }, ::transferCredentials, sftpConnector)
    val incoming = IncomingStorageProvider(application)
    val shizuku = ShizukuLink(shizukuGateway, CoroutineScope(SupervisorJob() + Dispatchers.Default), roots = { local.definitions() })
    // [local] stays the plain provider for callers that only map paths; everything that opens files goes through the registry.
    private val assistedLocal = AssistedLocalProvider(local, application.packageName) { shizuku.files }
    val providers: ProviderRegistry = ProviderRegistry(listOf(assistedLocal, saf, insideArchives, smb, b2, ftp, sftp, incoming))

    /** Whether Android closes [ref] to file managers (Android/data and Android/obb since Android 11). */
    fun closedToApps(ref: NodeRef): Boolean = assistedLocal.closes(ref)
    val database = LunaDatabase(application)
    val queue = OperationQueue(application, database)
    val archives = ArchiveEngine(providers, stagingDirectory = archiveStaging)
    val engine = OperationEngine(providers, archives = archives)
    val search = SearchEngine(providers)
    val sizer = FolderSizer(providers)
    val thumbnails = ThumbnailLoader(application, providers)
    val recycleBin = RecycleBin(providers)
    val inspector = FileInspector(application, providers)
    val digests = DigestEngine(providers)
    val media = MediaIndex(application, local)
    val analysis = StorageAnalysis(application)
    val apps = AppInventory(application)
    val installer = ApkInstaller(application, providers)
    val openTargets = OpenTargets(application)
    val shortcuts = Shortcuts(application)

    suspend fun uriFor(ref: NodeRef) = storageUri(application, providers, ref)

    @Volatile var additionalRoots: List<LocalRoot> = emptyList()
        set(value) { field = value; refreshRoots(deviceRootVisible) }

    @Volatile private var deviceRootVisible = true
    @Volatile var appDataVisible = false
        set(value) { field = value; refreshRoots(deviceRootVisible) }

    // Must stay below the properties refreshRoots reads; initializers run in declaration order.
    init { runCatching { refreshRoots(includeDeviceRoot = true) } }

    fun refreshRoots(includeDeviceRoot: Boolean) {
        deviceRootVisible = includeDeviceRoot
        local.updateRoots(
            DeviceStorage.volumes(application, includeDeviceRoot) +
                listOfNotNull(if (appDataVisible) DeviceStorage.appData(application) else null) +
                additionalRoots,
        )
        shizuku.rootsChanged()
    }

    fun hasFullAccess(): Boolean = DeviceStorage.hasFullAccess(application)
}
