package com.lunaexplorer.app.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.lunaexplorer.core.ApkReport
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.EditableTag
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.MetadataReport
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.TagEditor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.UserPrincipal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FileFacts(
    val path: String? = null,
    val realPath: String? = null,
    val contentUri: String? = null,
    val documentId: String? = null,
    val size: Long? = null,
    val allocated: Long? = null,
    val blockSize: Long? = null,
    val modified: Long? = null,
    val changed: Long? = null,
    val accessed: Long? = null,
    val mode: Int? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val owner: String? = null,
    val group: String? = null,
    val inode: Long? = null,
    val device: Long? = null,
    val links: Long? = null,
    val linkTarget: String? = null,
    val brokenLink: Boolean = false,
    val mimeType: String? = null,
    val readable: Boolean? = null,
    val writable: Boolean? = null,
    val executable: Boolean? = null,
    val filesystem: String? = null,
    val mountOptions: String? = null,
    val selinux: String? = null,
    val extendedAttributes: List<String> = emptyList(),
    val modifiableTime: Boolean = false,
    val details: List<Pair<String, String>> = emptyList(),
    val metadata: MetadataReport? = null,
    val apk: ApkReport? = null,
) {
    val sparse: Boolean get() = size != null && allocated != null && size > 0 && allocated < size
}

class FileInspector(private val context: Context, private val registry: ProviderRegistry) {
    private val formats = FormatDetails(context)

    suspend fun inspect(entry: Entry): FileFacts = withContext(Dispatchers.IO) {
        val provider = registry.provider(entry.ref)
        val path = (provider as? PathAddressable)?.pathOf(entry.ref)
        val uri = (provider as? ContentAddressable)?.contentUri(entry.ref)
        val base = if (path != null) filesystemFacts(entry, path) else documentFacts(entry, provider)
        if (entry.directory) base
        else base.copy(
            details = formats.read(entry, path, uri),
            metadata = formats.metadata(entry, path, uri),
            apk = formats.packageReport(entry, path),
        )
    }

    private fun filesystemFacts(entry: Entry, path: String): FileFacts {
        val file = File(path)
        val link = runCatching { Os.lstat(path) }.getOrNull()
        val stat = runCatching { Os.stat(path) }.getOrNull() ?: link
        val isLink = link != null && OsConstants.S_ISLNK(link.st_mode)
        val mount = mountFor(path)

        return FileFacts(
            path = path,
            realPath = runCatching { file.canonicalPath }.getOrNull()?.takeIf { it != path },
            contentUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
            }.getOrNull(),
            size = if (entry.directory) null else (stat?.st_size ?: file.length()),
            allocated = stat?.let { it.st_blocks * 512 },
            blockSize = stat?.st_blksize,
            modified = stat?.let { millis(it.st_mtime) } ?: entry.modified,
            changed = stat?.let { millis(it.st_ctime) },
            accessed = stat?.let { millis(it.st_atime) },
            mode = (link ?: stat)?.st_mode,
            uid = stat?.st_uid, gid = stat?.st_gid,
            owner = principal(path, "owner"), group = principal(path, "group"),
            inode = stat?.st_ino, device = stat?.st_dev, links = stat?.st_nlink,
            linkTarget = if (isLink) runCatching { Os.readlink(path) }.getOrNull() else null,
            brokenLink = isLink && !file.exists(),
            mimeType = if (entry.directory) entry.mimeType else mimeOf(file.name) ?: entry.mimeType,
            readable = access(path, OsConstants.R_OK),
            writable = access(path, OsConstants.W_OK),
            executable = access(path, OsConstants.X_OK),
            filesystem = mount?.type, mountOptions = mount?.options,
            // SELinux labels come back NUL-terminated from the kernel.
            selinux = runCatching {
                String(Os.getxattr(path, "security.selinux")).trim('\u0000', ' ')
            }.getOrNull(),
            extendedAttributes = runCatching { Os.listxattr(path).toList() }.getOrDefault(emptyList()),
            // A symlink's own times cannot be set; see LocalStorageProvider.setModified.
            modifiableTime = !isLink && access(path, OsConstants.W_OK) == true,
        )
    }

    private fun documentFacts(entry: Entry, provider: StorageProvider): FileFacts {
        val uri = (provider as? ContentAddressable)?.contentUri(entry.ref)
        return FileFacts(
            // A file only the provider can open still has a place to show.
            path = (provider as? PathAddressable)?.shownPathOf(entry.ref),
            contentUri = uri?.toString(),
            documentId = uri?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() },
            size = if (entry.directory) null else entry.size,
            modified = entry.modified,
            mimeType = entry.mimeType,
            readable = Capability.READ in entry.capabilities || Capability.LIST in entry.capabilities,
            writable = Capability.WRITE in entry.capabilities || Capability.CREATE in entry.capabilities,
            modifiableTime = Feature.SET_TIMES in provider.features && Capability.WRITE in entry.capabilities,
        )
    }

    suspend fun setModified(ref: NodeRef, epochMillis: Long): Long = withContext(Dispatchers.IO) {
        val provider = registry.provider(ref)
        val stored = provider.setModified(ref, epochMillis)
        (provider as? PathAddressable)?.pathOf(ref)?.let { rescan(it) }
        stored
    }

    fun canEditTags(entry: Entry) = TagEditor.supports(entry.name)

    suspend fun readTags(entry: Entry): List<EditableTag> = withContext(Dispatchers.IO) {
        val path = pathOf(entry.ref) ?: return@withContext emptyList()
        TagEditor.read(File(path))
    }

    suspend fun writeTags(entry: Entry, values: Map<String, String>) = withContext(Dispatchers.IO) {
        val path = pathOf(entry.ref) ?: return@withContext null
        TagEditor.write(File(path), values).onSuccess { rescan(path) }
    }

    private fun pathOf(ref: NodeRef): String? =
        (registry.provider(ref) as? PathAddressable)?.pathOf(ref)

    private fun rescan(path: String) {
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
    }

    private fun millis(seconds: Long): Long = seconds * 1000L

    private fun access(path: String, mode: Int): Boolean? =
        runCatching { Os.access(path, mode) }.getOrNull()

    private fun principal(path: String, view: String): String? = runCatching {
        val attributes = Files.readAttributes(Paths.get(path), "unix:$view", LinkOption.NOFOLLOW_LINKS)
        (attributes[view] as? UserPrincipal)?.name
    }.getOrNull()

    private fun mimeOf(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase().ifEmpty { return null }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private data class Mount(val point: String, val type: String, val options: String)

    private fun mountFor(path: String): Mount? = runCatching {
        val mounts = File("/proc/self/mountinfo").readLines().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator < 0) return@mapNotNull null
            val head = line.substring(0, separator).split(' ')
            val tail = line.substring(separator + 3).split(' ')
            if (head.size < 6 || tail.isEmpty()) return@mapNotNull null
            Mount(head[4], tail[0], head[5])
        }
        mounts.filter { path == it.point || path.startsWith(it.point.trimEnd('/') + "/") || it.point == "/" }
            .maxByOrNull { it.point.length }
    }.getOrNull()
}

/** Formats a mode as `ls -l` does, including the type and the setuid/setgid/sticky bits. */
fun formatMode(mode: Int): String {
    val type = when {
        OsConstants.S_ISDIR(mode) -> 'd'
        OsConstants.S_ISLNK(mode) -> 'l'
        OsConstants.S_ISBLK(mode) -> 'b'
        OsConstants.S_ISCHR(mode) -> 'c'
        OsConstants.S_ISFIFO(mode) -> 'p'
        OsConstants.S_ISSOCK(mode) -> 's'
        else -> '-'
    }
    fun triad(read: Int, write: Int, execute: Int, special: Int, specialChar: Char): String {
        val x = when {
            mode and special != 0 && mode and execute != 0 -> specialChar
            mode and special != 0 -> specialChar.uppercaseChar()
            mode and execute != 0 -> 'x'
            else -> '-'
        }
        return "${if (mode and read != 0) 'r' else '-'}${if (mode and write != 0) 'w' else '-'}$x"
    }
    return type +
        triad(OsConstants.S_IRUSR, OsConstants.S_IWUSR, OsConstants.S_IXUSR, OsConstants.S_ISUID, 's') +
        triad(OsConstants.S_IRGRP, OsConstants.S_IWGRP, OsConstants.S_IXGRP, OsConstants.S_ISGID, 's') +
        triad(OsConstants.S_IROTH, OsConstants.S_IWOTH, OsConstants.S_IXOTH, OsConstants.S_ISVTX, 't')
}

fun formatOctalMode(mode: Int): String = String.format("%04o", mode and 0xFFF)

/** Decodes a packed dev_t into its major and minor device numbers. */
fun formatDevice(device: Long): String {
    val major = (device shr 8) and 0xFFF
    val minor = (device and 0xFF) or ((device shr 12) and 0xFFF00)
    return "$major:$minor"
}
