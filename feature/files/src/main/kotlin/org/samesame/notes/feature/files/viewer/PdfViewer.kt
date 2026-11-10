package org.samesame.notes.feature.files.viewer

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.R

/**
 * Read-only PDF viewer: pages in a vertical list, pinch or double-tap to zoom (re-rendered sharper once the zoom
 * settles), drag to pan. A password-protected or damaged file shows a message with "Open with".
 */
@Composable
internal fun PdfViewer(
    file: LibraryFile,
    uri: Uri,
    bar: @Composable (BarSlots, ChromeColors) -> Unit,
    onPageCount: (Int) -> Unit,
    onOpenWith: () -> Unit,
    onAnnotate: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val colors = SameSameTheme.colors
    val open by produceState<PdfOpen?>(null, file.sha256) {
        value = withContext(Dispatchers.IO) { PdfPages.open(context, uri) }
    }
    val document = (open as? PdfOpen.Opened)?.document
    DisposableEffect(document) { onDispose { document?.close() } }
    LaunchedEffect(document) { document?.let { onPageCount(it.pageCount) } }

    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        bar(
            BarSlots(
                subtitle = document?.takeIf { it.pageCount > 0 }?.let { stringResource(R.string.feature_files_viewer_page, page.coerceAtMost(it.pageCount), it.pageCount) },
                menu = { dismiss ->
                    if (document != null) AppMenuItem(stringResource(R.string.feature_files_viewer_annotate), { dismiss(); onAnnotate() })
                },
            ),
            themeChrome(),
        )
        when (val o = open) {
            null -> Unit
            PdfOpen.Protected -> ViewerMessage(stringResource(R.string.feature_files_viewer_pdf_protected), onOpenWith, Modifier.fillMaxSize())
            PdfOpen.Failed -> ViewerMessage(stringResource(R.string.feature_files_viewer_pdf_failed), onOpenWith, Modifier.fillMaxSize())
            is PdfOpen.Opened ->
                if (o.document.pageCount == 0) ViewerMessage(stringResource(R.string.feature_files_viewer_pdf_failed), onOpenWith, Modifier.fillMaxSize())
                else PdfPageList(o.document, listState)
        }
    }
}

@Composable
private fun PdfPageList(document: PdfPages, listState: androidx.compose.foundation.lazy.LazyListState) {
    val density = LocalDensity.current
    val cache = remember(document) {
        object : LruCache<Long, Bitmap>(96 * 1024 * 1024) {
            override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
        }
    }
    val sizes = remember(document) { mutableStateMapOf<Int, Pair<Int, Int>>() }
    // Page sizes arrive in the background; until a page's own size is known it borrows the first page's shape.
    LaunchedEffect(document) {
        for (i in 0 until document.pageCount) document.pageSize(i)?.let { sizes[i] = it }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var step by remember { mutableIntStateOf(1) }
    LaunchedEffect(scale) { delay(250); step = PdfLayout.renderStep(scale) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val widthPx = constraints.maxWidth

        fun zoomTo(newScale: Float, cx: Float, cy: Float) {
            val target = newScale.coerceIn(PdfLayout.MIN_SCALE, PdfLayout.MAX_SCALE)
            if (target == scale) return
            offsetX = PdfLayout.zoomOffset(offsetX, cx, scale, target, width)
            // Keep the content under the fingers at the same height: the list scrolls in unscaled pixels.
            listState.dispatchRawDelta(cy / scale - cy / target)
            scale = target
        }

        // The list is scaled from its top-left corner, so only the first 1/scale of its height is on screen: pad the
        // end by the rest, or the last page could not be scrolled into view.
        val hidden = with(density) { (height - height / scale).toDp() }
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(document, width) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            if (pressed > 1) {
                                val c = event.calculateCentroid(useCurrent = false)
                                zoomTo(scale * event.calculateZoom(), c.x, c.y)
                                offsetX = PdfLayout.clampOffset(offsetX + pan.x, width, scale)
                                listState.dispatchRawDelta(-pan.y / scale)
                                // Two fingers are a pinch: the list must not also fling.
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (scale > 1f) {
                                // One finger: the list scrolls vertically by itself; sideways movement pans the zoomed page.
                                offsetX = PdfLayout.clampOffset(offsetX + pan.x, width, scale)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(document, width) {
                    detectTapGestures(onDoubleTap = { at ->
                        zoomTo(if (scale > 1.01f) 1f else PdfLayout.DOUBLE_TAP_SCALE, at.x, at.y)
                    })
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + navBar + hidden),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(document.pageCount, key = { it }) { index ->
                val size = sizes[index] ?: sizes[0]
                val aspect = size?.let { it.first.toFloat() / it.second }?.takeIf { it > 0f } ?: (1f / 1.414f)
                PdfPage(document, index, widthPx, step, cache, Modifier.fillMaxWidth().aspectRatio(aspect))
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfPages, index: Int, widthPx: Int, step: Int, cache: LruCache<Long, Bitmap>, modifier: Modifier) {
    fun key(s: Int) = index.toLong() * 8 + s
    // The 1x render stays up while the sharper one is made.
    val bitmap by produceState(cache.get(key(step)) ?: cache.get(key(1)), index, step, widthPx) {
        if (cache.get(key(step)) == null) {
            document.render(index, widthPx * step)?.let { cache.put(key(step), it) }
        }
        value = cache.get(key(step)) ?: value
    }
    Box(modifier.background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}
