package org.samesame.notes.feature.composer.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.document.FrameHandle
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.core.model.document.Pt
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.session.CropFraction
import org.samesame.notes.feature.composer.session.ImageCrop
import org.samesame.notes.feature.composer.session.LassoCrop
import kotlin.math.abs

/**
 * Crop mode of a selected image (object menu "Crop"): the image's frame ([frame], page space, rotated with the image)
 * shows the crop rectangle with 8 handles; outside it the picture is dimmed. Handles move edges, the inside moves the
 * whole crop. Modal over the canvas: it takes every touch until Cancel or Done.
 */
@Composable
internal fun BoxScope.ImageCropOverlay(
    frame: ObjectFrame,
    pageOrigin: Offset,
    viewport: Viewport,
    crop: CropFraction,
    onCropChange: (CropFraction) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestFrame by rememberUpdatedState(frame)
    val latestOrigin by rememberUpdatedState(pageOrigin)
    val change by rememberUpdatedState(onCropChange)
    val accent = ObjectChrome.accent
    // Crop corner in fractions -> view px, through the (rotated) frame and the viewport.
    fun toView(f: ObjectFrame, origin: Offset, fx: Float, fy: Float): Offset {
        val p = f.toWorld((fx - 0.5f) * f.w, (fy - 0.5f) * f.h)
        return viewport.docToView(Offset(origin.x + p.x, origin.y + p.y))
    }
    val path = remember { Path() }
    // The top bar is a sibling above the gesture layer: siblings don't share touches, so Done / Cancel never reach
    // the crop gesture (a tap on the bar would otherwise move or restart the crop).
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val f = latestFrame
                val scale = viewport.scale
                fun fraction(view: Offset): Pt {
                    val d = viewport.viewToDoc(view) - latestOrigin
                    val l = f.toLocal(Pt(d.x, d.y))
                    return Pt(l.x / f.w + 0.5f, l.y / f.h + 0.5f)
                }
                val start = fraction(down.position)
                val c0 = latestCrop
                // 24 dp grab radius, in fractions of each side.
                val rx = 24.dp.toPx() / (f.w * scale).coerceAtLeast(1f)
                val ry = 24.dp.toPx() / (f.h * scale).coerceAtLeast(1f)
                val handle = FrameHandle.entries.sortedByDescending { it.isCorner }.firstOrNull { h ->
                    val hx = if (h.nx < 0) c0.left else if (h.nx > 0) c0.right else (c0.left + c0.right) / 2f
                    val hy = if (h.ny < 0) c0.top else if (h.ny > 0) c0.bottom else (c0.top + c0.bottom) / 2f
                    abs(start.x - hx) <= rx && abs(start.y - hy) <= ry
                }
                val inside = start.x in c0.left..c0.right && start.y in c0.top..c0.bottom
                if (handle == null && !inside) return@awaitEachGesture
                // At least 48 dp on screen each way (and never above the whole image).
                val minW = (48.dp.toPx() / (f.w * scale)).coerceIn(0.02f, 1f)
                val minH = (48.dp.toPx() / (f.h * scale)).coerceIn(0.02f, 1f)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    val now = fraction(ch.position)
                    change(ImageCrop.drag(c0, handle, now.x - start.x, now.y - start.y, minW, minH))
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val f = frame
            val o = pageOrigin
            val img = listOf(toView(f, o, 0f, 0f), toView(f, o, 1f, 0f), toView(f, o, 1f, 1f), toView(f, o, 0f, 1f))
            val c = listOf(
                toView(f, o, crop.left, crop.top), toView(f, o, crop.right, crop.top),
                toView(f, o, crop.right, crop.bottom), toView(f, o, crop.left, crop.bottom),
            )
            path.reset()
            path.fillType = PathFillType.EvenOdd
            path.moveTo(img[0].x, img[0].y); for (i in 1..3) path.lineTo(img[i].x, img[i].y); path.close()
            path.moveTo(c[0].x, c[0].y); for (i in 1..3) path.lineTo(c[i].x, c[i].y); path.close()
            drawPath(path, Color.Black.copy(alpha = 0.55f))
            path.reset()
            path.fillType = PathFillType.NonZero
            path.moveTo(c[0].x, c[0].y); for (i in 1..3) path.lineTo(c[i].x, c[i].y); path.close()
            drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
            val r = 6.dp.toPx()
            for (h in FrameHandle.entries) {
                val fx = if (h.nx < 0) crop.left else if (h.nx > 0) crop.right else (crop.left + crop.right) / 2f
                val fy = if (h.ny < 0) crop.top else if (h.ny > 0) crop.bottom else (crop.top + crop.bottom) / 2f
                val p = toView(f, o, fx, fy)
                drawCircle(Color.White, r, p)
                drawCircle(accent, r, p, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
    CropTopBar(stringResource(R.string.feature_composer_crop_title), onCancel, onDone)
    }
}

/** Cancel / title / Done bar on top of a crop mode. */
@Composable
private fun BoxScope.CropTopBar(title: String, onCancel: () -> Unit, onDone: () -> Unit, doneEnabled: Boolean = true) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().background(colors.elevated).height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CropBarButton(stringResource(android.R.string.cancel), true, onCancel)
        Text(
            title, color = colors.textPrimary, fontSize = 17.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        CropBarButton(stringResource(R.string.feature_composer_done), doneEnabled, onDone)
    }
}

@Composable
private fun CropBarButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = SameSameTheme.colors.accent.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 16.sp)
    }
    Spacer(Modifier.height(0.dp))
}

/**
 * Lasso crop mode of a selected image (object menu "Lasso crop"): a finger drag draws a free outline over the picture
 * ([outline], fractions of the image; a new drag starts a new outline). Outside a finished outline the picture is dimmed.
 * Done keeps the inside ([LassoCrop]); modal over the canvas like [ImageCropOverlay].
 */
@Composable
internal fun BoxScope.LassoCropOverlay(
    frame: ObjectFrame,
    pageOrigin: Offset,
    viewport: Viewport,
    outline: List<Pt>,
    drawing: Boolean,
    canFinish: Boolean,
    onOutlineChange: (List<Pt>, drawing: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val latestFrame by rememberUpdatedState(frame)
    val latestOrigin by rememberUpdatedState(pageOrigin)
    val change by rememberUpdatedState(onOutlineChange)
    val accent = ObjectChrome.accent
    fun toView(f: ObjectFrame, origin: Offset, fx: Float, fy: Float): Offset {
        val p = f.toWorld((fx - 0.5f) * f.w, (fy - 0.5f) * f.h)
        return viewport.docToView(Offset(origin.x + p.x, origin.y + p.y))
    }
    val path = remember { Path() }
    // The top bar is a sibling above the gesture layer: siblings don't share touches, so Done / Cancel never reach the
    // lasso. A press that does not move (the bar's buttons, a stray tap) never replaces the outline either.
    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val f = latestFrame
                fun fraction(view: Offset): Pt {
                    val d = viewport.viewToDoc(view) - latestOrigin
                    val l = f.toLocal(Pt(d.x, d.y))
                    return LassoCrop.clamp(Pt(l.x / f.w + 0.5f, l.y / f.h + 0.5f))
                }
                val slop = viewConfiguration.touchSlop
                val start = fraction(down.position)
                val pts = ArrayList<Pt>()
                var started = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!started) {
                        if ((ch.position - down.position).getDistance() < slop) {
                            if (!ch.pressed) break
                            continue
                        }
                        started = true
                        down.consume()
                        pts += start
                        change(pts.toList(), true)
                    }
                    ch.consume()
                    if (!ch.pressed) break
                    val p = fraction(ch.position)
                    if (LassoCrop.accepts(pts.lastOrNull(), p)) {
                        pts += p
                        change(pts.toList(), true)
                    }
                }
                if (started) change(pts.toList(), false)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val f = frame
            val o = pageOrigin
            if (!drawing && canFinish) {
                val img = listOf(toView(f, o, 0f, 0f), toView(f, o, 1f, 0f), toView(f, o, 1f, 1f), toView(f, o, 0f, 1f))
                path.reset()
                path.fillType = PathFillType.EvenOdd
                path.moveTo(img[0].x, img[0].y); for (i in 1..3) path.lineTo(img[i].x, img[i].y); path.close()
                outline.forEachIndexed { i, p ->
                    val v = toView(f, o, p.x, p.y)
                    if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
                }
                path.close()
                drawPath(path, Color.Black.copy(alpha = 0.55f))
            }
            if (outline.size >= 2) {
                path.reset()
                path.fillType = PathFillType.NonZero
                outline.forEachIndexed { i, p ->
                    val v = toView(f, o, p.x, p.y)
                    if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
                }
                if (!drawing) path.close()
                drawPath(path, Color.White, style = Stroke(3.dp.toPx()))
                drawPath(path, accent, style = Stroke(1.5.dp.toPx()))
            }
        }
        if (outline.isEmpty()) {
            Text(
                stringResource(R.string.feature_composer_lasso_crop_hint), color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    CropTopBar(stringResource(R.string.feature_composer_lasso_crop_title), onCancel, onDone, doneEnabled = canFinish && !drawing)
    }
}
