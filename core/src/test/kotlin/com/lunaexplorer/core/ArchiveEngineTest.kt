package com.lunaexplorer.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ArchiveEngineTest {
    private val storage = ArchiveTestProvider()
    private val engine = ArchiveEngine(ProviderRegistry(listOf(storage)))
    private val source = storage.folder(storage.root, "source")
    private val destination = storage.folder(storage.root, "destination")

    private fun roundTrip(format: ArchiveFormat, level: Int = CompressionPreset.NORMAL.level) = runBlocking {
        val tree = storage.folder(source, "notes")
        storage.file(tree, "one.txt", "first file")
        val nested = storage.folder(tree, "nested")
        storage.file(nested, "two.txt", "second file, a little longer so compression has something to do")

        val name = "bundle.${format.extension}"
        val created = engine.create(listOf(tree), destination, name, ArchiveOptions(format, level)).last()
        assertTrue(created.complete)
        assertNotNull(created.produced)
        assertFalse("Staging must not survive a successful archive", storage.hasStages())

        val archive = storage.childRef(destination, name)!!
        val into = storage.folder(storage.root, "extracted-${format.name}")
        engine.extract(archive, into, null).last()
        assertTree(into)
    }

    @Test fun `a zip round trip preserves a nested tree`() = roundTrip(ArchiveFormat.ZIP)
    @Test fun `a stored zip round trip preserves a nested tree`() = roundTrip(ArchiveFormat.ZIP, CompressionPreset.STORE.level)
    @Test fun `a tar round trip preserves a nested tree`() = roundTrip(ArchiveFormat.TAR)
    @Test fun `a gzipped tar round trip preserves a nested tree`() = roundTrip(ArchiveFormat.TAR_GZ)
    @Test fun `a tar xz round trip preserves a nested tree`() = roundTrip(ArchiveFormat.TAR_XZ)

    @Test fun `an xz single file round trips`() = runBlocking {
        val item = storage.file(source, "one.txt", "compress me ".repeat(200))
        engine.create(listOf(item), destination, "one.xz", ArchiveOptions(ArchiveFormat.XZ)).last()
        val archive = storage.childRef(destination, "one.xz")!!
        val into = storage.folder(storage.root, "xz-out")
        engine.extract(archive, into, null).last()
        assertEquals("compress me ".repeat(200), storage.text(storage.childRef(into, "one")!!))
    }

    private fun encryptedRoundTrip(scheme: ZipEncryption, level: Int = CompressionPreset.NORMAL.level): Unit = runBlocking {
        val body = "secret contents that must survive ".repeat(40)
        val fileName = "secret-${scheme.name}-$level.txt"
        val item = storage.file(source, fileName, body)
        val name = "locked-${scheme.name}-$level.zip"
        engine.create(listOf(item), destination, name,
            ArchiveOptions(ArchiveFormat.ZIP, level, encryption = scheme, password = "correct horse")).last()
        val archive = storage.childRef(destination, name)!!

        val into = storage.folder(storage.root, "open-${scheme.name}-$level")
        engine.extract(archive, into, null, password = "correct horse").last()
        assertEquals(body, storage.text(storage.childRef(into, fileName)!!))

        val wrong = storage.folder(storage.root, "wrong-${scheme.name}-$level")
        assertThrows(Exception::class.java) {
            runBlocking { engine.extract(archive, wrong, null, password = "not the password").last() }
        }
    }

    @Test fun `a zipcrypto archive round trips deflated`() = encryptedRoundTrip(ZipEncryption.ZIP_CRYPTO)
    @Test fun `a zipcrypto archive round trips stored`() =
        encryptedRoundTrip(ZipEncryption.ZIP_CRYPTO, CompressionPreset.STORE.level)
    @Test fun `an aes archive round trips deflated`() = encryptedRoundTrip(ZipEncryption.AES_256)
    @Test fun `an aes archive round trips stored`() =
        encryptedRoundTrip(ZipEncryption.AES_256, CompressionPreset.STORE.level)

    @Test fun `indexed formats are looked inside anywhere, compressed tars only where reads are local`() {
        fun entry(name: String) = Entry(NodeRef(storage.id, name), name, directory = false)
        for (name in listOf("photos.zip", "library.jar", "book.epub", "backup.tar", "backup.7z", "backup.rar")) {
            assertTrue(name, engine.canBrowse(entry(name)))
        }
        assertTrue("Local reads may decompress the whole file", engine.canBrowse(entry("backup.tar.gz")))
        assertTrue(engine.canBrowse(entry("backup.tar.bz2")))
        assertFalse(engine.canBrowse(entry("notes.gz")))
        assertFalse(engine.canBrowse(entry("notes.bz2")))
        assertFalse(engine.canBrowse(entry("notes.txt")))

        val remote = object : StorageProvider by storage {
            override val id = "remote"
            override val features = setOf(Feature.NETWORK)
        }
        val overNetwork = ArchiveEngine(ProviderRegistry(listOf(remote)))
        fun remoteEntry(name: String) = Entry(NodeRef("remote", name), name, directory = false)
        assertFalse(overNetwork.canBrowse(remoteEntry("backup.tar.gz")))
        assertFalse(overNetwork.canBrowse(remoteEntry("backup.tbz2")))
        assertFalse(overNetwork.canBrowse(remoteEntry("backup.txz")))
        assertTrue(overNetwork.canBrowse(remoteEntry("backup.tar")))
        assertTrue(overNetwork.canBrowse(remoteEntry("backup.7z")))
        assertTrue(overNetwork.canBrowse(remoteEntry("backup.rar")))
        assertTrue(overNetwork.canBrowse(remoteEntry("photos.zip")))
        assertFalse("Unknown providers are not browsed", engine.canBrowse(remoteEntry("backup.tar.gz")))

        for (name in listOf("a.zip", "a.jar", "a.apk", "a.apks", "a.xapk", "a.apkm", "a.epub", "a.tar", "a.tgz", "a.tar.gz",
            "a.txz", "a.tar.xz", "a.tbz2", "a.tar.bz2", "a.tbz", "a.gz", "a.xz", "a.bz2", "a.7z", "a.rar")) {
            assertTrue("$name is extracted", engine.canExtract(entry(name)))
        }
        assertFalse(engine.canExtract(entry("notes.txt")))
        assertFalse(engine.canExtract(Entry(NodeRef(storage.id, "a.zip"), "a.zip", directory = true)))
        assertFalse(engine.canBrowse(Entry(NodeRef(storage.id, "a.tar"), "a.tar", directory = true)))
        assertEquals(ArchiveFormat.TAR_BZ2, engine.formatOf("a.TBZ2"))
        assertEquals(ArchiveFormat.SEVEN_Z, engine.formatOf("a.7z"))
        assertEquals(ArchiveFormat.RAR, engine.formatOf("a.rar"))
        assertEquals(ArchiveFormat.BZIP2, engine.formatOf("a.bz2"))
        assertEquals("Unknown names are still tried as zip", ArchiveFormat.ZIP, engine.formatOf("a.bin"))
    }

    @Test fun `read-only formats cannot be created`() = runBlocking {
        val item = storage.file(source, "single.txt", "content")
        for (format in listOf(ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR, ArchiveFormat.TAR_BZ2, ArchiveFormat.BZIP2)) {
            val failure = runCatching {
                engine.create(listOf(item), destination, "out.${format.extension}", ArchiveOptions(format)).last()
            }.exceptionOrNull()
            assertEquals(format.name, StorageError.UNSUPPORTED, (failure as? StorageException)?.reason)
            assertNull(storage.childRef(destination, "out.${format.extension}"))
            assertFalse(storage.hasStages())
        }
    }

    @Test fun `compressing actually shrinks repetitive content`() = runBlocking {
        val repetitive = "the same sentence over and over. ".repeat(400)
        storage.file(source, "repeats.txt", repetitive)
        val item = storage.childRef(source, "repeats.txt")!!

        engine.create(listOf(item), destination, "stored.zip", ArchiveOptions(ArchiveFormat.ZIP, CompressionPreset.STORE.level)).last()
        engine.create(listOf(item), destination, "small.zip", ArchiveOptions(ArchiveFormat.ZIP, CompressionPreset.BEST.level)).last()

        val stored = storage.bytes(storage.childRef(destination, "stored.zip")!!).size
        val compressed = storage.bytes(storage.childRef(destination, "small.zip")!!).size
        assertTrue("Compressed $compressed should be well under stored $stored", compressed < stored / 2)
    }

    @Test fun `progress names each entry and finishes complete`() = runBlocking {
        val folder = storage.folder(source, "many")
        repeat(5) { storage.file(folder, "file-$it.txt", "contents $it") }
        val steps = engine.create(listOf(folder), destination, "many.zip", ArchiveOptions()).toList()
        assertTrue(steps.first().entriesTotal >= 6)
        assertTrue(steps.any { it.currentName.endsWith("file-3.txt") })
        assertTrue(steps.last().complete)
        assertEquals(steps.first().entriesTotal, steps.last().entriesDone)
    }

    @Test fun `an archive that cannot be written leaves nothing behind`() = runBlocking {
        storage.file(source, "data.bin", "content")
        val item = storage.childRef(source, "data.bin")!!
        storage.failWrites = true
        val failure = runCatching {
            engine.create(listOf(item), destination, "broken.zip", ArchiveOptions()).last()
        }
        assertTrue(failure.isFailure)
        assertNull("A failed archive must not be published", storage.childRef(destination, "broken.zip"))
        assertFalse("and must not leave its staging object", storage.hasStages())
    }

    @Test fun `entry paths from an archive cannot escape the chosen folder`() = runBlocking {
        val bytes = jdkZipBytes(mapOf("../../escaped.txt" to bytesOf("should not land outside")))
        val archive = storage.binary(source, "evil.zip", bytes)
        val into = storage.folder(storage.root, "safe")

        engine.extract(archive, into, null).last()
        assertNotNull("The name is kept, its traversal is not", storage.childRef(into, "escaped.txt"))
        assertNull(storage.childRef(storage.root, "escaped.txt"))
        assertNull(storage.childRef(source, "escaped.txt"))
    }

    @Test fun `extracting into a new folder keeps the destination tidy`() = runBlocking {
        val tree = storage.folder(source, "docs")
        storage.file(tree, "readme.md", "hello")
        engine.create(listOf(tree), destination, "docs.zip", ArchiveOptions()).last()
        val archive = storage.childRef(destination, "docs.zip")!!

        engine.extract(archive, destination, "unpacked").last()
        val folder = storage.childRef(destination, "unpacked")!!
        assertEquals("hello", storage.text(storage.childRef(storage.childRef(folder, "docs")!!, "readme.md")!!))
    }

    @Test fun `gzip refuses a selection it cannot represent`() = runBlocking {
        val folder = storage.folder(source, "pair")
        storage.file(folder, "a.txt", "a")
        storage.file(folder, "b.txt", "b")
        val failure = runCatching {
            engine.create(listOf(folder), destination, "pair.gz", ArchiveOptions(ArchiveFormat.GZIP)).last()
        }
        assertTrue(failure.isFailure)
        assertEquals(StorageError.UNSUPPORTED, (failure.exceptionOrNull() as? StorageException)?.reason)
        assertFalse(storage.hasStages())
    }

    private val tree: Members = linkedMapOf(
        "notes" to null,
        "notes/one.txt" to bytesOf("first file"),
        "notes/nested/two.txt" to bytesOf("second file, a little longer so compression has something to do"),
    )

    private fun assertTree(into: NodeRef) {
        val root = storage.childRef(into, "notes")!!
        assertEquals("first file", storage.text(storage.childRef(root, "one.txt")!!))
        val deeper = storage.childRef(root, "nested")!!
        assertEquals("second file, a little longer so compression has something to do", storage.text(storage.childRef(deeper, "two.txt")!!))
    }

    private fun extractionFailure(archive: NodeRef, into: NodeRef, password: String = ""): StorageException =
        storageFailure { runBlocking { engine.extract(archive, into, null, password).last() } }

    @Test fun `a tar bz2 is extracted`() = runBlocking {
        val archive = storage.binary(source, "bundle.tar.bz2", tarBytes(ArchiveFormat.TAR_BZ2, tree))
        val into = storage.folder(storage.root, "from-tbz2")
        engine.extract(archive, into, null).last()
        assertTree(into)
    }

    @Test fun `a bzip2 single file is extracted under its own name`() = runBlocking {
        val body = "compress me ".repeat(200)
        val packed = ByteArrayOutputStream().also { raw ->
            BZip2CompressorOutputStream(raw).use { it.write(body.toByteArray()) }
        }.toByteArray()
        val archive = storage.binary(source, "one.txt.bz2", packed)
        val into = storage.folder(storage.root, "from-bz2")
        engine.extract(archive, into, null).last()
        assertEquals(body, storage.text(storage.childRef(into, "one.txt")!!))
    }

    @Test fun `a 7z is extracted, staged when the source cannot seek`() = runBlocking {
        val archive = storage.binary(source, "bundle.7z", sevenZBytes(tree))
        val into = storage.folder(storage.root, "from-7z")
        val stagedBefore = stagedExtractionFiles()
        val steps = engine.extract(archive, into, null).toList()
        assertTree(into)
        assertTrue(steps.last().complete)
        assertTrue(steps.any { it.currentName == "notes/nested/two.txt" })
        assertEquals("The staged copy is removed", stagedBefore, stagedExtractionFiles())
    }

    @Test fun `a 7z over a channel is extracted without staging`() = runBlocking {
        val remote = ByteSource("bundle.7z", sevenZBytes(tree), id = "remote")
        val over = ArchiveEngine(ProviderRegistry(listOf(remote, storage)))
        val into = storage.folder(storage.root, "from-7z-channel")
        over.extract(remote.ref, into, null).last()
        assertTree(into)
        assertEquals(0, remote.streamOpens)
        assertEquals(1, remote.channelOpens)
        assertEquals(0, remote.openHandles)
    }

    @Test fun `an encrypted 7z needs its password to extract`() = runBlocking {
        val content = storage.binary(source, "enc.7z", ArchiveFixtureData.ENC_7Z)
        val headers = storage.binary(source, "hdr.7z", ArchiveFixtureData.HDR_7Z)
        for ((archive, label) in listOf(content to "content", headers to "headers")) {
            val missing = extractionFailure(archive, storage.folder(storage.root, "$label-missing"))
            assertEquals(label, StorageError.AUTH, missing.reason)
            val wrong = extractionFailure(archive, storage.folder(storage.root, "$label-wrong"), "wrong")
            assertEquals(label, StorageError.AUTH, wrong.reason)

            val into = storage.folder(storage.root, "$label-right")
            engine.extract(archive, into, null, password = "Secret1").last()
            assertEquals("top level text\n", storage.text(storage.childRef(into, "readme.txt")!!))
            val docs = storage.childRef(into, "docs")!!
            assertEquals("second doc\n", storage.text(storage.childRef(docs, "second.txt")!!))
            assertEquals("nested notes, a little longer so the codec has something to do " + (1..40).joinToString(" ") + "\n",
                storage.text(storage.childRef(storage.childRef(docs, "nested")!!, "notes.txt")!!))
        }
    }

    @Test fun `rar archives are extracted, solid blocks and rar5 included`() = runBlocking {
        val folders = storage.binary(source, "dirs.rar", ArchiveFixtureData.RAR4_DIRECTORY)
        val intoFolders = storage.folder(storage.root, "from-rar-dirs")
        engine.extract(folders, intoFolders, null).last()
        assertEquals("baz\n", storage.text(storage.childRef(storage.childRef(intoFolders, "foo")!!, "bar.txt")!!))

        for ((bytes, label) in listOf(ArchiveFixtureData.RAR4_SOLID to "rar4", ArchiveFixtureData.RAR5_SOLID to "rar5")) {
            val archive = storage.binary(source, "solid-$label.rar", bytes)
            val into = storage.folder(storage.root, "from-solid-$label")
            engine.extract(archive, into, null).last()
            for (index in 1..9) assertEquals("file$index\n", storage.text(storage.childRef(into, "file$index.txt")!!))
        }
    }

    @Test fun `an encrypted rar needs its password to extract`() = runBlocking {
        val fixtures = listOf(
            ArchiveFixtureData.RAR4_ENCRYPTED_HEADERS to "rar4-headers",
            ArchiveFixtureData.RAR4_ENCRYPTED_FILES to "rar4-files",
            ArchiveFixtureData.RAR5_ENCRYPTED_HEADERS to "rar5-headers",
            ArchiveFixtureData.RAR5_ENCRYPTED_FILES to "rar5-files",
        )
        for ((bytes, label) in fixtures) {
            val archive = storage.binary(source, "$label.rar", bytes)
            assertEquals(label, StorageError.AUTH, extractionFailure(archive, storage.folder(storage.root, "$label-missing")).reason)
            assertEquals(label, StorageError.AUTH, extractionFailure(archive, storage.folder(storage.root, "$label-wrong"), "wrong").reason)

            val into = storage.folder(storage.root, "$label-right")
            engine.extract(archive, into, null, password = "junrar").last()
            assertEquals(label, "file1\n", storage.text(storage.childRef(into, "file1.txt")!!))
        }
    }

    private fun stagedExtractionFiles(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("luna-extract") }
            .orEmpty().map { it.name }.toSet()
}

private class ArchiveTestProvider : StorageProvider {
    override val id = "archive"
    private class Node(val ref: NodeRef, var parent: NodeRef?, var name: String, val directory: Boolean,
        var data: ByteArray = byteArrayOf())
    private val nodes = linkedMapOf<NodeRef, Node>()
    private var sequence = 0
    val root = insert(null, "storage", true)
    var failWrites = false

    fun folder(parent: NodeRef, name: String): NodeRef = insert(parent, name, true)
    fun file(parent: NodeRef, name: String, text: String): NodeRef =
        insert(parent, name, false).also { nodes.getValue(it).data = text.toByteArray() }
    fun binary(parent: NodeRef, name: String, bytes: ByteArray): NodeRef =
        insert(parent, name, false).also { nodes.getValue(it).data = bytes }
    fun text(ref: NodeRef) = nodes.getValue(ref).data.decodeToString()
    fun bytes(ref: NodeRef) = nodes.getValue(ref).data
    fun childRef(parent: NodeRef, name: String) = nodes.values.firstOrNull { it.parent == parent && it.name == name }?.ref
    fun hasStages() = nodes.values.any { it.name.startsWith(".luna-") }

    private fun insert(parent: NodeRef?, name: String, directory: Boolean): NodeRef {
        if (parent != null && childRef(parent, name) != null) throw StorageException(StorageError.CONFLICT, "Name exists")
        val ref = NodeRef(id, "node-${++sequence}")
        nodes[ref] = Node(ref, parent, name, directory)
        return ref
    }

    private fun node(ref: NodeRef) = nodes[ref] ?: throw StorageException(StorageError.NOT_FOUND, "Gone")
    private fun entry(ref: NodeRef): Entry {
        val node = node(ref)
        return Entry(ref, node.name, node.directory, if (node.directory) null else node.data.size.toLong(),
            modified = 1_700_000_000_000, capabilities = Capability.entries.toSet())
    }

    override suspend fun stat(ref: NodeRef) = entry(ref)
    override fun list(parent: NodeRef, complete: Boolean): Flow<List<Entry>> = flow {
        val children = nodes.values.filter { it.parent == parent }.map { entry(it.ref) }
        for (batch in children.chunked(4)) emit(batch)
    }
    override suspend fun create(parent: NodeRef, name: String, directory: Boolean, mimeType: String) =
        entry(insert(parent, name, directory))
    override suspend fun rename(ref: NodeRef, name: String): Entry {
        node(ref).parent?.let { if (childRef(it, name) != null) throw StorageException(StorageError.CONFLICT, "Name exists") }
        node(ref).name = name
        return entry(ref)
    }
    override suspend fun delete(ref: NodeRef) { nodes.remove(ref) }
    override suspend fun openRead(ref: NodeRef): InputStream = ByteArrayInputStream(node(ref).data)
    override suspend fun openWrite(ref: NodeRef): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            if (failWrites) throw StorageException(StorageError.IO, "Refused")
            node(ref).data = toByteArray()
            super.close()
        }
    }
    override suspend fun isDescendant(candidate: NodeRef, ancestor: NodeRef): Boolean {
        var cursor: NodeRef? = candidate
        while (cursor != null) {
            if (cursor == ancestor) return true
            cursor = nodes[cursor]?.parent
        }
        return false
    }
    override suspend fun commit(staged: NodeRef, parent: NodeRef, name: String, replace: Entry?, onRetained: RetainedObjects?): Entry {
        replace?.ref?.let { nodes.remove(it) }
        node(staged).parent = parent
        node(staged).name = name
        return entry(staged)
    }
}
