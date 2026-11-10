package org.samesame.notes.feature.files.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.R

/**
 * Full-screen image viewer: swipe between [images], pinch or double-tap to zoom, drag to pan, tap to show or hide the bars.
 * Swiping is off while the page is zoomed, so a drag always pans.
 */
@Composable
internal fun ImageViewer(
    images: List<LibraryFile>,
    index: Int,
    uriOf: (LibraryFile) -> Uri,
    onPage: (FileId) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = index.coerceAtLeast(0)) { images.size }
    val current by rememberUpdatedState(images)
    var zoomed by remember { mutableStateOf(false) }

    // The pager settled on a page: the bars and actions follow that image.
    LaunchedEffect(pager.settledPage) { current.getOrNull(pager.settledPage)?.let { onPage(it.id) } }
    // The set changed under the viewer (rename, another import): stay on the same image.
    LaunchedEffect(index) {
        if (index >= 0 && index != pager.currentPage && !pager.isScrollInProgress) pager.scrollToPage(index)
    }

    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxSize(),
        key = { images.getOrNull(it)?.id?.value ?: it },
        userScrollEnabled = !zoomed,
        pageSpacing = 16.dp,
    ) { page ->
        val file = images.getOrNull(page) ?: return@HorizontalPager
        ZoomableImage(
            file = file,
            uri = remember(file.id, file.displayName) { uriOf(file) },
            active = page == pager.currentPage,
            onZoomed = { if (page == pager.currentPage) zoomed = it },
            onTap = onTap,
        )
    }
}

private sealed interface Loaded {
    data object Pending : Loaded
    data object Failed : Loaded
    data class Done(val bitmap: Bitmap) : Loaded
}

@Composable
private fun ZoomableImage(file: LibraryFile, uri: Uri, active: Boolean, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val loaded by produceState<Loaded>(Loaded.Pending, file.sha256) {
        val bitmap = withContext(Dispatchers.IO) { ImageLoading.decode(context, uri) }
        value = if (bitmap != null) Loaded.Done(bitmap) else Loaded.Failed
    }
    var zoom by remember(file.id) { mutableStateOf(Zoom()) }
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)

    LaunchedEffect(zoom.zoomed, active) { if (active) onZoomed(zoom.zoomed) }
    // A page that was swiped away comes back fitted.
    LaunchedEffect(active) { if (!active) zoom = Zoom() }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        when (val l = loaded) {
            Loaded.Pending -> Unit
            Loaded.Failed -> Text(
                stringResource(R.string.feature_files_viewer_image_failed),
                style = SameSameText.EmptyTitle, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
            )
            is Loaded.Done -> {
                val (fw, fh) = ZoomMath.fit(cw, ch, l.bitmap.width.toFloat(), l.bitmap.height.toFloat())
                val image = remember(l.bitmap) { l.bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = file.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(cw, ch, fw, fh) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    // One finger on a fitted image belongs to the pager (swipe) and the tap detector.
                                    if (pressed > 1 || zoom.zoomed) {
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        val pan = event.calculatePan()
                                        zoom = ZoomMath.transform(
                                            zoom, event.calculateZoom(), centroid.x - cw / 2f, centroid.y - ch / 2f,
                                            pan.x, pan.y, cw, ch, fw, fh,
                                        )
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(cw, ch, fw, fh) {
                            detectTapGestures(
                                onTap = { tap() },
                                onDoubleTap = { at ->
                                    val from = zoom
                                    val to = ZoomMath.doubleTap(from, at.x - cw / 2f, at.y - ch / 2f, cw, ch, fw, fh)
                                    scope.launch {
                                        Animatable(0f).animateTo(1f, tween(220)) {
                                            zoom = Zoom(
                                                from.scale + (to.scale - from.scale) * value,
                                                from.x + (to.x - from.x) * value,
                                                from.y + (to.y - from.y) * value,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.x
                            translationY = zoom.y
                        },
                )
            }
        }
    }
}
