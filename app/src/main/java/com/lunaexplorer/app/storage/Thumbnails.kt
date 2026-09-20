package com.lunaexplorer.app.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.core.ArchiveProvider
import com.lunaexplorer.core.BudgetedReads
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Feature
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.ReadBudget
import com.lunaexplorer.core.StorageProvider
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailLoader(context: Context, private val registry: ProviderRegistry) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val decoding = Dispatchers.IO.limitedParallelism(4)
    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).coerceIn(4 * 1024, 64 * 1024).toInt()
    ) {
        // At least 1: an entry of size 0 is never evicted.
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }

    // Preview cost is enforced while reading and cannot be inferred from file size.
    fun supports(entry: Entry, networkLimit: Long = Long.MAX_VALUE): Boolean {
        if (entry.directory) {
            if (Capability.LIST !in entry.capabilities) return false
            return !(networkLimit == 0L && remote(entry.ref))
        }
        if (Capability.READ !in entry.capabilities) return false
        if (entry.size == 0L) return false
        if (networkLimit == 0L && remote(entry.ref)) return false
        return entry.mimeType.startsWith("image/") || entry.mimeType.startsWith("video/") ||
            entry.mimeType.startsWith("audio/") || entry.mimeType == "application/pdf" ||
            entry.mimeType == "application/vnd.android.package-archive" ||
            entry.name.substringAfterLast('.', "").lowercase() in BUNDLE_EXTENSIONS
    }

    private fun remote(ref: NodeRef): Boolean = networkOf(ref) != null

    /** The id of the network provider [ref] is read from, following an archive member to where its archive is kept. Null when no network is involved. */
    fun networkOf(ref: NodeRef): String? {
        val provider = runCatching { registry.provider(ref) }.getOrNull() ?: return null
        if (Feature.NETWORK in provider.features) return provider.id
        val source = runCatching { (provider as? ArchiveProvider)?.sourceOf(ref) }.getOrNull() ?: return null
        return networkOf(source)
    }

    fun cached(entry: Entry, sizePx: Int): Bitmap? = cache.get(key(entry, sizePx))

    /** For when what can be read has changed: a preview refused before may be possible now. */
    fun forgetFailures() = failed.clear()

    suspend fun load(entry: Entry, sizePx: Int, networkBudget: Long = Long.MAX_VALUE): Bitmap? {
        if (!supports(entry, networkBudget)) return null
        val key = key(entry, sizePx)
        cache.get(key)?.let { return it }
        if (entry.directory) return folderPreview(entry, key, sizePx, networkBudget)
        // Keyed by budget so a refused preview is retried when the allowance grows.
        val failureKey = "$key|$networkBudget"
        if (failureKey in failed) return null
        val caller = currentCoroutineContext().job
        return withContext(decoding) {
            // decode() blocks this thread; cancellation reaches it through the signal.
            val signal = CancellationSignal()
            val budget = if (remote(entry.ref)) ReadBudget(networkBudget) {
                // Native media callbacks expect IOException for a cancelled read.
                if (!caller.isActive) throw InterruptedIOException("The thumbnail is no longer wanted")
            } else null
            val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    caller.ensureActive()
                    continuation.resume(decode(entry, sizePx, signal, budget))
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (_: Throwable) {
                    continuation.resume(null)
                }
            }
            // A null caused by cancellation must not be cached as a failure.
            caller.ensureActive()
            if (bitmap == null) return@withContext null.also { rememberFailure(failureKey) }
            cache.put(key, bitmap)
            bitmap
        }
    }

    // Only inspect the first listing batch to bound the cost of a folder preview.
    private suspend fun folderPreview(entry: Entry, key: String, sizePx: Int, networkBudget: Long): Bitmap? {
        val failureKey = "$key|$networkBudget"
        if (failureKey in failed) return null
        val caller = currentCoroutineContext().job
        val provider = runCatching { registry.provider(entry.ref) }.getOrNull()
            ?: return null.also { rememberFailure(failureKey) }
        // A zoom step or a scroll cancels this load; only a real error may be remembered as a failure.
        val children = try { provider.list(entry.ref).firstOrNull().orEmpty() } catch (_: Exception) {
            caller.ensureActive()
            return null.also { rememberFailure(failureKey) }
        }
        caller.ensureActive()

        // Slightly larger than the biggest card (STACK_SPAN), so tiles are never upscaled.
        val cell = (sizePx * 0.85f).toInt().coerceAtLeast(1)
        val previews = ArrayList<Bitmap>(TILE_COUNT)
        for (child in children) {
            if (previews.size == TILE_COUNT) break
            if (!supports(child, networkBudget) || child.directory) continue
            val tile = try { load(child, cell, networkBudget) } catch (_: Exception) { null }
            caller.ensureActive()
            previews += tile ?: continue
        }
        if (previews.isEmpty()) return null.also { rememberFailure(failureKey) }
        val collage = withContext(decoding) { stack(previews, sizePx) }
        cache.put(key, collage)
        return collage
    }

    // Cached previews need transparent backgrounds to work in either theme.
    private fun stack(previews: List<Bitmap>, sizePx: Int): Bitmap {
        val output = createBitmap(sizePx, sizePx)
        val canvas = Canvas(output)
        val side = sizePx.toFloat()
        val count = previews.size
        val stepX = STACK_STEP_X * side
        val stepY = STACK_STEP_Y * side
        val span = STACK_SPAN * side
        val cardWidth = span - (count - 1) * stepX
        val cardHeight = span - (count - 1) * stepY
        val left = (side - span) / 2f
        val top = (side - span) / 2f
        val radius = (0.055f * side).coerceAtLeast(1.5f)
        // No backing panel: previews with transparent corners (app icons) show it as a border.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (0.008f * side).coerceAtLeast(1f)
            color = 0x40000000
        }
        for (slot in 0 until count) {
            // Drawn back to front, so previews[0] ends up on top.
            val preview = previews[count - 1 - slot]
            val x = left + slot * stepX
            val y = top + slot * stepY
            val rect = RectF(x, y, x + cardWidth, y + cardHeight)
            canvas.drawRoundRect(rect, radius, radius, cardPaint(preview, rect))
            canvas.drawRoundRect(rect, radius, radius, edge)
        }
        return output
    }

    // Use a shader because bitmap-backed canvases do not antialias clipPath.
    private fun cardPaint(preview: Bitmap, rect: RectF): Paint {
        val scale = maxOf(rect.width() / preview.width, rect.height() / preview.height)
        return Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(preview, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        rect.left + (rect.width() - preview.width * scale) / 2f,
                        rect.top + (rect.height() - preview.height * scale) / 2f,
                    )
                })
            }
        }
    }

    private fun rememberFailure(key: String) {
        if (failed.size > 1024) failed.clear()
        failed.add(key)
    }

    // Include size because providers may omit modification time.
    private fun key(entry: Entry, sizePx: Int) =
        "${entry.ref.provider}|${entry.ref.key}|${entry.modified}|${entry.size}|$sizePx"

    private fun decode(entry: Entry, sizePx: Int, signal: CancellationSignal, budget: ReadBudget?): Bitmap? {
        val provider = registry.provider(entry.ref)
        val path = (provider as? PathAddressable)?.pathOf(entry.ref)
        val uri = (provider as? ContentAddressable)?.contentUri(entry.ref)
        if (uri != null) providerThumbnail(uri, sizePx, signal)?.let { return scale(it, sizePx) }
        // Also a file that has a place on this device which only its provider can open.
        val onlyThroughProvider = path == null && uri == null && Feature.RANGE_READ in provider.features &&
            (Feature.NETWORK in provider.features || (provider as? PathAddressable)?.shownPathOf(entry.ref) != null)
        if (onlyThroughProvider) {
            val started = System.nanoTime()
            val made = overNetwork(entry, provider, sizePx, signal, budget)
            DebugLog.d("Thumbnails") {
                "${entry.ref.provider}:${entry.ref.key}: " +
                    "${when { made != null -> "made"; signal.isCanceled -> "given up"; else -> "none" }} in ${DebugLog.millisSince(started)} ms"
            }
            return made?.let { scale(it, sizePx) }
        }
        val bitmap = when {
            entry.mimeType == "application/vnd.android.package-archive" -> path?.let(::apkIcon)
            entry.name.substringAfterLast('.', "").lowercase() in BUNDLE_EXTENSIONS ->
                path?.let(::bundleIcon)
            entry.mimeType == "application/pdf" -> firstPdfPage(entry.ref, path, sizePx)
            entry.mimeType.startsWith("video/") -> videoFrame(path, uri, sizePx)
            entry.mimeType.startsWith("audio/") -> albumArt(path, uri, sizePx)
            else -> image(entry.ref, path, uri, sizePx, budget, signal)
        } ?: return null
        return scale(bitmap, sizePx)
    }

    private fun overNetwork(entry: Entry, provider: StorageProvider, sizePx: Int, signal: CancellationSignal, budget: ReadBudget?): Bitmap? {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        return when {
            entry.mimeType == "application/vnd.android.package-archive" -> staged(entry, provider, signal, budget) { apkIcon(it.absolutePath) }
            extension in BUNDLE_EXTENSIONS -> staged(entry, provider, signal, budget) { bundleIcon(it.absolutePath, keep = false) }
            entry.mimeType == "application/pdf" -> staged(entry, provider, signal, budget) {
                firstPdfPage(entry.ref, it.absolutePath, sizePx)
            }
            entry.mimeType.startsWith("image/") -> image(entry.ref, null, null, sizePx, budget, signal)
            entry.mimeType.startsWith("video/") || entry.mimeType.startsWith("audio/") ->
                mediaSource(entry, provider, signal, budget)?.use { source ->
                    if (entry.mimeType.startsWith("video/")) videoFrame(null, null, sizePx, source)
                    else albumArt(null, null, sizePx, source)
                }
            else -> null
        }
    }

    private fun mediaSource(entry: Entry, provider: StorageProvider, signal: CancellationSignal, budget: ReadBudget?): ChannelMediaDataSource? {
        val channel = runBlocking {
            if (budget == null) provider.openChannel(entry.ref)
            else (provider as? BudgetedReads)?.openChannel(entry.ref, budget)
        } ?: return null
        return ChannelMediaDataSource(channel, signal)
    }

    private fun staged(entry: Entry, provider: StorageProvider, signal: CancellationSignal, budget: ReadBudget?, read: (File) -> Bitmap?): Bitmap? {
        if (budget != null && entry.size?.let { it > budget.remaining } == true) return null
        // With no budget, as for a file on this device that only its provider can open, the copy still has a price.
        if (budget == null && entry.size?.let { it > UNBUDGETED_STAGE_LIMIT } == true) return null
        val file = File(stagingDir, "thumbnail-stage-${System.nanoTime()}.apk")
        return try {
            runBlocking { readSource(provider, entry.ref, budget) }.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        if (signal.isCanceled) return null
                        val wanted = if (budget == null) minOf(buffer.size.toLong(), UNBUDGETED_STAGE_LIMIT - copied + 1).toInt()
                            else buffer.size
                        val got = input.read(buffer, 0, wanted)
                        if (got < 0) break
                        if (got == 0) return null
                        copied += got
                        if (budget == null && copied > UNBUDGETED_STAGE_LIMIT) return null
                        output.write(buffer, 0, got)
                    }
                }
            }
            if (signal.isCanceled) null else read(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    /** First use deletes staging files left behind when the process was killed. */
    private val stagingDir: File by lazy {
        context.cacheDir.listFiles { file -> file.name.startsWith("thumbnail-stage-") || file.name.startsWith("bundle-stage-") }
            ?.forEach { it.delete() }
        context.cacheDir
    }

    private fun providerThumbnail(uri: Uri, sizePx: Int, signal: CancellationSignal): Bitmap? = runCatching {
        DocumentsContract.getDocumentThumbnail(resolver, uri, Point(sizePx, sizePx), signal)
    }.getOrNull()

    private fun image(ref: NodeRef, path: String?, uri: Uri?, sizePx: Int, budget: ReadBudget?, signal: CancellationSignal): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies EXIF orientation; BitmapFactory does not.
            val source = when {
                path != null -> ImageDecoder.createSource(File(path))
                uri != null -> ImageDecoder.createSource(resolver, uri)
                else -> null
            }
            if (source != null) return runCatching {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, sizePx))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = openStream(ref, path, uri, budget, signal) ?: return null
        var exif: ExifInterface? = null
        input.buffered(EXIF_REWIND_LIMIT).use {
            it.mark(EXIF_REWIND_LIMIT)
            BitmapFactory.decodeStream(it, null, bounds)
            if (path == null && uri == null) {
                exif = try {
                    it.reset()
                    ExifInterface(it)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx) }
        val bitmap = openStream(ref, path, uri, budget, signal)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return orient(bitmap, if (path == null && uri == null) exif else orientation(path, uri))
    }

    private fun openStream(ref: NodeRef, path: String?, uri: Uri?, budget: ReadBudget?, signal: CancellationSignal): InputStream? = try {
        val input = when {
            path != null -> File(path).inputStream()
            uri != null -> resolver.openInputStream(uri)
            else -> runBlocking { readSource(registry.provider(ref), ref, budget) }
        }
        input?.let {
            object : FilterInputStream(it) {
                private fun checkCancelled() {
                    if (signal.isCanceled) throw InterruptedIOException("The thumbnail is no longer wanted")
                }
                override fun read(): Int {
                    checkCancelled()
                    return `in`.read().also { checkCancelled() }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    checkCancelled()
                    return `in`.read(buffer, offset, length).also { checkCancelled() }
                }
                override fun skip(count: Long): Long {
                    checkCancelled()
                    return `in`.skip(count).also { checkCancelled() }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    private suspend fun readSource(provider: StorageProvider, ref: NodeRef, budget: ReadBudget?): InputStream =
        if (budget == null) provider.openRead(ref)
        else (provider as? BudgetedReads)?.openRead(ref, budget)
            ?: throw IOException("This storage cannot limit preview downloads")

    private fun orientation(path: String?, uri: Uri?): ExifInterface? = runCatching {
        when {
            path != null -> ExifInterface(path)
            uri != null -> resolver.openInputStream(uri)?.use { ExifInterface(it) }
            else -> null
        }
    }.getOrNull()

    private fun orient(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        if (exif == null) return bitmap
        val degrees = exif.rotationDegrees
        val flipped = exif.isFlipped
        if (degrees == 0 && !flipped) return bitmap
        val matrix = Matrix().apply {
            if (flipped) setScale(-1f, 1f)
            postRotate(degrees.toFloat())
        }
        return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }.getOrDefault(bitmap)
    }

    private fun videoFrame(path: String?, uri: Uri?, sizePx: Int, source: MediaDataSource? = null): Bitmap? =
        withRetriever(path, uri, source) { retriever ->
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
                    ?.let { return@withRetriever it }
            }
            // API 26 cannot scale while decoding, and a 4K ARGB frame is about 33 MB.
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_UNSCALED_PIXELS) null else retriever.frameAtTime
        }

    private fun albumArt(path: String?, uri: Uri?, sizePx: Int, source: MediaDataSource? = null): Bitmap? =
        withRetriever(path, uri, source) { retriever ->
            val bytes = retriever.embeddedPicture ?: return@withRetriever null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }

    private fun withRetriever(
        path: String?,
        uri: Uri?,
        source: MediaDataSource?,
        read: (MediaMetadataRetriever) -> Bitmap?,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                path != null -> retriever.setDataSource(path)
                uri != null -> retriever.setDataSource(context, uri)
                source != null -> retriever.setDataSource(source)
                else -> return null
            }
            read(retriever)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun firstPdfPage(ref: NodeRef, path: String?, sizePx: Int): Bitmap? {
        val descriptor = runCatching {
            when {
                path != null -> ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                else -> (registry.provider(ref) as? ContentAddressable)?.contentUri(ref)?.let { resolver.openFileDescriptor(it, "r") }
            }
        }.getOrNull() ?: return null
        return firstPdfPage(descriptor, sizePx)
    }

    /** Closes [descriptor]. */
    private fun firstPdfPage(descriptor: ParcelFileDescriptor, sizePx: Int): Bitmap? {
        return descriptor.use { file ->
            runCatching {
                PdfRenderer(file).use { renderer ->
                    if (renderer.pageCount == 0) return@runCatching null
                    renderer.openPage(0).use { page ->
                        val scale = (sizePx.toFloat() / maxOf(page.width, page.height, 1)).coerceAtMost(1f)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }.getOrNull()
        }
    }

    private fun apkIcon(path: String): Bitmap? = runCatching {
        val packages = context.packageManager
        val info = packages.getPackageArchiveInfo(path, 0) ?: return null
        val application = info.applicationInfo ?: return null
        application.sourceDir = path
        application.publicSourceDir = path
        drawableToBitmap(application.loadIcon(packages))
    }.getOrNull()

    // PackageManager needs the base APK on disk; keep it only for unbudgeted previews.
    private fun bundleIcon(path: String, keep: Boolean = true): Bitmap? = runCatching {
        ZipFile(File(path)).use { zip ->
            val base = zip.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .let { apks ->
                    apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) }
                        ?: apks.filterNot { it.name.contains("config.", ignoreCase = true) }
                            .maxByOrNull { it.size }
                        ?: apks.maxByOrNull { it.size }
                } ?: return null

            val staged = if (keep) File(context.cacheDir, "bundle-icon-${path.hashCode()}-${base.size}.apk")
                else File(stagingDir, "bundle-stage-${System.nanoTime()}.apk")
            if (!keep || !staged.isFile || staged.length() != base.size) {
                zip.getInputStream(base).use { source ->
                    staged.outputStream().use { sink -> source.copyTo(sink, 128 * 1024) }
                }
            }
            try { apkIcon(staged.absolutePath) } finally { if (!keep) staged.delete() }
        }
    }.getOrNull()

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun scale(bitmap: Bitmap, sizePx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= sizePx || longest == 0) return bitmap
        val ratio = sizePx.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        }.getOrDefault(bitmap)
    }

    private companion object {
        /** The most copied into the cache for one preview when no read budget applies. */
        const val UNBUDGETED_STAGE_LIMIT = 64L * 1024 * 1024
        const val EXIF_REWIND_LIMIT = 64 * 1024
        val BUNDLE_EXTENSIONS = setOf("xapk", "apks", "apkm")

        /** Roughly 8 MB as ARGB_8888, times the four decode slots. */
        const val MAX_UNSCALED_PIXELS = 2_000_000L
    }
}

private const val TILE_COUNT = 4

// Fractions of the tile side: the stack's overall width, and the offset between successive cards.
private const val STACK_SPAN = 0.82f
private const val STACK_STEP_X = 0.075f
private const val STACK_STEP_Y = 0.055f
