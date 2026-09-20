@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.Gif
import com.lunaexplorer.core.GifFrame
import com.lunaexplorer.core.GifReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.sqrt

private class FrameThumbnail(val index: Int, val delayMillis: Int, val image: ImageBitmap)

/** Every thumbnail is held at once, so their size shrinks as the frame count grows. */
private const val THUMBNAIL_BUDGET = 48L * 1024 * 1024
private val THUMBNAIL_SIDE = 48..256

private const val TOO_LARGE = "This GIF is too large to show frame by frame"

private val BELOW_THE_BAR @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

private fun bitmapOf(frame: GifFrame, maxSide: Int? = null): Bitmap {
    // createBitmap copies the pixels, which the reader is about to draw over.
    val full = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    val longest = maxOf(frame.width, frame.height)
    if (maxSide == null || longest <= maxSide) return full
    val scale = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(full, (frame.width * scale).toInt().coerceAtLeast(1),
        (frame.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
}

@Composable
internal fun GifFrames(name: String, bytes: ByteArray, onBack: () -> Unit) {
    var count by remember(bytes) { mutableStateOf<Int?>(null) }
    var failure by remember(bytes) { mutableStateOf<String?>(null) }
    val thumbnails = remember(bytes) { mutableStateListOf<FrameThumbnail>() }
    var opened by remember(bytes) { mutableStateOf<Int?>(null) }
    val grid = rememberLazyGridState()

    LaunchedEffect(bytes) {
        withContext(Dispatchers.Default) {
            try {
                val total = Gif.frameCount(bytes)
                count = total
                val side = sqrt(THUMBNAIL_BUDGET / (4.0 * total.coerceAtLeast(1))).toInt()
                    .coerceIn(THUMBNAIL_SIDE.first, THUMBNAIL_SIDE.last)
                Gif.frames(bytes) { frame ->
                    thumbnails += FrameThumbnail(frame.index, frame.delayMillis, bitmapOf(frame, side).asImageBitmap())
                    isActive
                }
                if (isActive) count = thumbnails.size
            } catch (error: IOException) {
                failure = error.message ?: "This image could not be read"
            } catch (_: OutOfMemoryError) {
                if (thumbnails.isEmpty()) failure = TOO_LARGE
            }
        }
    }

    BackHandler { if (opened != null) opened = null else onBack() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val first = opened
        if (first != null) {
            FramePager(bytes, thumbnails, total = maxOf(count ?: 0, thumbnails.size), first, onBack = { opened = null })
            return@Surface
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(BELOW_THE_BAR)) {
            ViewerBar(name, onBack, subtitle = count?.let { "$it frames" })
            when {
                failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(failure!!, Modifier.padding(24.dp))
                }
                thumbnails.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> FastLazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().testTag("gifFrames"), grid,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(thumbnails.size, key = { thumbnails[it].index }) { position ->
                        val frame = thumbnails[position]
                        Column(Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = "Frame ${frame.index + 1}") { opened = frame.index }) {
                            Image(frame.image, null, Modifier.fillMaxWidth().aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceContainer), contentScale = ContentScale.Fit)
                            Text("${frame.index + 1}  ·  ${frame.delayMillis} ms", Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-size frames. A frame is drawn over the ones before it, so the step forward decodes one more
 * frame, and anything earlier starts again from the first; the frames just behind the one asked for
 * are kept on the way, which makes several steps back cost one restart.
 */
private class FrameSource(private val bytes: ByteArray) {
    private val lock = Mutex()
    private var reader: GifReader? = null
    private var decoded = -1
    private val kept = LinkedHashMap<Int, ImageBitmap>()

    suspend fun frame(index: Int): ImageBitmap? = lock.withLock {
        kept[index]?.let { return@withLock it }
        withContext(Dispatchers.Default) {
            var current = reader
            if (current == null || index <= decoded) {
                // Let go of the old canvas before the new one is allocated.
                reader = null
                decoded = -1
                current = GifReader(bytes).also { reader = it }
            }
            while (decoded < index) {
                ensureActive()
                val frame = current.next() ?: return@withContext null
                decoded = frame.index
                if (decoded >= index - BEHIND) keep(decoded, bitmapOf(frame).asImageBitmap())
            }
            kept[index]
        }
    }

    private fun keep(index: Int, image: ImageBitmap) {
        kept[index] = image
        val each = image.width.toLong() * image.height * 4
        val room = (KEPT_BYTES / each.coerceAtLeast(1)).toInt().coerceIn(1, KEPT_FRAMES)
        while (kept.size > room) kept.remove(kept.keys.first())
    }

    private companion object {
        const val BEHIND = 3
        const val KEPT_FRAMES = 8
        const val KEPT_BYTES = 64L * 1024 * 1024
    }
}

@Composable
private fun FramePager(bytes: ByteArray, thumbnails: List<FrameThumbnail>, total: Int, first: Int, onBack: () -> Unit) {
    val source = remember(bytes) { FrameSource(bytes) }
    val pager = rememberPagerState(initialPage = first) { total }
    val scope = rememberCoroutineScope()
    var pageScale by remember { mutableFloatStateOf(1f) }
    val index = pager.currentPage
    LaunchedEffect(index) { pageScale = 1f }

    Column(Modifier.fillMaxSize().windowInsetsPadding(BELOW_THE_BAR)) {
        ViewerBar("Frame ${index + 1} of $total", onBack, subtitle = thumbnails.getOrNull(index)?.let { "${it.delayMillis} ms" }) {
            ToolIcon(Icons.AutoMirrored.Outlined.NavigateBefore, "Previous frame", enabled = index > 0) {
                scope.launch { pager.scrollToPage(index - 1) }
            }
            ToolIcon(Icons.AutoMirrored.Outlined.NavigateNext, "Next frame", enabled = index < total - 1) {
                scope.launch { pager.scrollToPage(index + 1) }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainer),
            userScrollEnabled = pageScale <= FITTED, beyondViewportPageCount = 1, key = { it }) { page ->
            var image by remember(page) { mutableStateOf<ImageBitmap?>(null) }
            var failure by remember(page) { mutableStateOf<String?>(null) }
            LaunchedEffect(page) {
                try {
                    image = source.frame(page)
                    if (image == null) failure = "This frame could not be decoded"
                } catch (error: IOException) {
                    failure = error.message ?: "This frame could not be decoded"
                } catch (_: OutOfMemoryError) {
                    failure = TOO_LARGE
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val shown = image
                when {
                    shown != null -> ZoomableImage(remember(shown) { BitmapPainter(shown) }, "Frame ${page + 1}", page,
                        Modifier.testTag("gifFrame"), resting = page != index,
                        onScaleChanged = { if (page == index) pageScale = it })
                    failure != null -> Text(failure!!, Modifier.padding(24.dp))
                    else -> CircularProgressIndicator()
                }
            }
        }
    }
}
