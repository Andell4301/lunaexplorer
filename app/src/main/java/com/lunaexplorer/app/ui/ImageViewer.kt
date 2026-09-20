@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.Gif
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** At or below this scale the image counts as fitted and horizontal drags go to the pager. */
internal const val FITTED = 1.01f

@Composable
internal fun ImageGallery(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val siblings = remember(entry.ref) {
        viewModel.siblingsOf(entry) { it.mimeType.startsWith("image/") }
    }
    val start = remember(siblings, entry.ref) {
        siblings.indexOfFirst { it.ref == entry.ref }.coerceAtLeast(0)
    }
    val pager = rememberPagerState(initialPage = start) { siblings.size }
    // Reset on page changes so a previous image's zoom cannot disable paging.
    var pageScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { pageScale = 1f } }
    val current = siblings.getOrNull(pager.currentPage) ?: entry
    // The bytes of each loaded GIF with more than one frame, so its frames open without a second read.
    val animated = remember { mutableStateMapOf<NodeRef, ByteArray>() }
    var frames by remember { mutableStateOf<Entry?>(null) }
    val framesOf = frames?.takeIf { it.ref in animated }
    var barShown by remember { mutableStateOf(true) }
    SystemBars(shown = barShown || framesOf != null)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().then(if (pageScale <= FITTED) Modifier.fastScroll(pager) else Modifier)
                .then(if (framesOf != null) Modifier.clearAndSetSemantics {} else Modifier),
            userScrollEnabled = pageScale <= FITTED,
            beyondViewportPageCount = 1,
        ) { page ->
            ImagePage(
                entry = siblings[page],
                viewModel = viewModel,
                isCurrent = page == pager.currentPage && framesOf == null,
                onScaleChanged = { if (page == pager.currentPage) pageScale = it },
                onFrames = { bytes -> if (bytes != null) animated[siblings[page].ref] = bytes else animated -= siblings[page].ref },
                onTap = { barShown = !barShown },
            )
        }
        if (framesOf != null) {
            GifFrames(framesOf.name, animated.getValue(framesOf.ref), onBack = { frames = null })
        } else {
            AnimatedVisibility(barShown, enter = fadeIn(), exit = fadeOut()) {
                // The bar lies over the picture in either theme, so it is white on a scrim.
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxWidth().background(SCRIM)) {
                        ViewerBar(
                            title = current.name,
                            onDismiss = onDismiss,
                            entry = current,
                            viewModel = viewModel,
                            subtitle = if (siblings.size > 1) "${pager.currentPage + 1} of ${siblings.size}" else null,
                            subtitleColor = Color.White.copy(alpha = 0.7f),
                        ) {
                            if (current.ref in animated) ToolIcon(Icons.Outlined.BurstMode, "Frames") { frames = current }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemBars(shown: Boolean) {
    val view = LocalView.current
    val window = remember(view) { (view.parent as? DialogWindowProvider)?.window ?: (view.context as? Activity)?.window }
    val bars = remember(window, view) { window?.let { WindowCompat.getInsetsController(it, view) } }
    SideEffect {
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (shown) bars?.show(WindowInsetsCompat.Type.systemBars()) else bars?.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(bars) { onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) } }
}

@Composable
private fun ImagePage(
    entry: Entry,
    viewModel: BrowserViewModel,
    isCurrent: Boolean,
    onScaleChanged: (Float) -> Unit,
    onFrames: (ByteArray?) -> Unit,
    onTap: () -> Unit,
) {
    var image by remember(entry.ref) { mutableStateOf<Painter?>(null) }
    var animation by remember(entry.ref) { mutableStateOf<Drawable?>(null) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }

    DisposableEffect(animation, isCurrent) {
        val moving = animation as? Animatable
        if (moving != null) { if (isCurrent) moving.start() else moving.stop() }
        onDispose { moving?.stop() }
    }
    DisposableEffect(entry.ref) { onDispose { onFrames(null) } }

    LaunchedEffect(entry.ref) {
        val result = viewModel.files.readBytes(entry, limit = 96L * 1024 * 1024)
        result.fold(
            onSuccess = { bytes ->
                // Animated decode first: a still decode of a GIF yields only its first frame.
                val moving = withContext(Dispatchers.Default) { decodeAnimated(bytes) }
                if (moving != null) {
                    animation = moving
                    image = DrawablePainter(moving)
                } else {
                    val decoded = withContext(Dispatchers.Default) { decodeOriented(bytes) }
                    if (decoded == null) failure = "This image could not be decoded"
                    else image = BitmapPainter(decoded)
                }
                val frames = withContext(Dispatchers.Default) {
                    if (Gif.isGif(bytes)) runCatching { Gif.frameCount(bytes) }.getOrDefault(0) else 0
                }
                if (frames > 1) onFrames(bytes)
            },
            onFailure = { failure = it.message ?: "This image could not be read" },
        )
    }

    val tapped by rememberUpdatedState(onTap)
    // A picture takes its own taps. One still loading, or refused, has nothing else to bring the bar back.
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onTap = { tapped() }) }) {
        image?.let { ZoomableImage(it, entry.name, entry.ref, resting = !isCurrent, onScaleChanged = onScaleChanged, onTap = onTap) }
        if (image == null && failure == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        failure?.let {
            Text(it, Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
        }
    }
}

/**
 * [picture] fitted to the space, with pinch, drag and double-tap zoom. A fitted image leaves one-finger
 * drags to whatever pages it. [subject] is what the zoom belongs to; [resting] puts it back to fitted.
 */
@Composable
internal fun ZoomableImage(
    picture: Painter,
    description: String?,
    subject: Any?,
    modifier: Modifier = Modifier,
    resting: Boolean = false,
    onScaleChanged: (Float) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val tapped by rememberUpdatedState(onTap)
    var scale by remember(subject) { mutableFloatStateOf(1f) }
    var offset by remember(subject) { mutableStateOf(Offset.Zero) }
    var container by remember(subject) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(resting) { if (resting) { scale = 1f; offset = Offset.Zero; onScaleChanged(1f) } }

    val fitted = remember(picture, container) {
        val size = picture.intrinsicSize
        if (size == Size.Unspecified || size.width <= 0f || size.height <= 0f ||
            container.width == 0 || container.height == 0
        ) {
            Size.Zero
        } else {
            val ratio = minOf(container.width / size.width, container.height / size.height)
            Size(size.width * ratio, size.height * ratio)
        }
    }

    fun bounded(value: Offset, atScale: Float): Offset {
        val slackX = ((fitted.width * atScale) - container.width).coerceAtLeast(0f) / 2f
        val slackY = ((fitted.height * atScale) - container.height).coerceAtLeast(0f) / 2f
        return Offset(value.x.coerceIn(-slackX, slackX), value.y.coerceIn(-slackY, slackY))
    }

    Image(picture, description, modifier.fillMaxSize()
        .onSizeChanged { container = it }
        // Read gestures before graphicsLayer so deltas use viewport pixels.
        .pointerInput(subject) {
            // Pinches are claimed at once, one-finger drags only while zoomed; a fitted image
            // leaves drags to the pager.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var claimed = false
                var travelled = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    val pinching = pressed >= 2
                    if (!pinching && scale <= FITTED) break
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (!claimed) {
                        if (pinching) {
                            claimed = true
                        } else {
                            travelled += pan.getDistance()
                            if (travelled < viewConfiguration.touchSlop) continue
                            claimed = true
                        }
                    }
                    val next = (scale * zoom).coerceIn(1f, 12f)
                    offset = if (next != scale) {
                        // Keep the image point under the pinch centroid stationary.
                        val focus = centroid - Offset(container.width / 2f, container.height / 2f)
                        val ratio = next / scale
                        bounded(offset * ratio + focus * (1f - ratio) + pan, next)
                    } else {
                        bounded(offset + pan, next)
                    }
                    scale = next
                    if (scale <= FITTED) offset = Offset.Zero
                    onScaleChanged(scale)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        .pointerInput(subject) {
            detectTapGestures(
                onTap = { tapped() },
                onDoubleTap = { position ->
                    if (scale > FITTED) { scale = 1f; offset = Offset.Zero } else {
                        val focus = position - Offset(container.width / 2f, container.height / 2f)
                        scale = 3f
                        offset = bounded(focus * (1f - 3f), 3f)
                    }
                    onScaleChanged(scale)
                },
            )
        }, contentScale = ContentScale.Fit)
}

private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= 3000) sample *= 2
    return sample
}

/** ImageDecoder (API 28+) applies EXIF orientation itself; the BitmapFactory path applies it by hand. */
private fun decodeOriented(bytes: ByteArray): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return@runCatching ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }.asImageBitmap()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
        ?: return@runCatching null
    val degrees = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) decoded.asImageBitmap()
    else Bitmap.createBitmap(
        decoded, 0, 0, decoded.width, decoded.height,
        Matrix().apply { postRotate(degrees) }, true,
    ).asImageBitmap()
}.getOrNull()

/** Null unless the platform can animate it: GIF and animated WebP, on API 28+. */
internal fun decodeAnimated(bytes: ByteArray): Drawable? = runCatching {
    if (Build.VERSION.SDK_INT < 28) return@runCatching null
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
        decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
    } as? AnimatedImageDrawable
}.getOrNull()

/**
 * An AnimatedImageDrawable requests each new frame through [Drawable.Callback]; without one it
 * stays on its first frame. The callback here turns those requests into Compose invalidations.
 */
private class DrawablePainter(private val drawable: Drawable) : Painter() {
    private var repaint by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())

    init {
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { repaint++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, who, `when`)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what, who)
            }
        }
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) Size.Unspecified
        else Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            // Reading the state makes this draw re-run when the callback bumps it.
            repaint
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
