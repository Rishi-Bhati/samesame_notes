package org.samesame.notes.feature.composer.panels

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.toolbar.ColorTarget
import kotlin.math.roundToInt

/** Floating eyedropper widget size (reference resources: 142 x 40 dp) and its default position (12 dp in, 45 dp down). */
internal val SpuitWidth = 142.dp
internal val SpuitHeight = 40.dp
internal val SpuitDefaultX = 12.dp
internal val SpuitDefaultY = 45.dp

/** What leaving the eyedropper does: [setTarget] applies the final colour to the target, [addRecent] adds it to the recent colours. */
internal data class SpuitCommit(val setTarget: Boolean, val addRecent: Boolean)

/** Pen and highlighter take each sample live (the reference's pen-panel eyedropper applies as it samples); text colours take the final colour on leaving. */
internal fun spuitLiveTarget(target: ColorTarget): Boolean = target == ColorTarget.Pen || target == ColorTarget.Highlighter

/**
 * Leaving the eyedropper with [final] (ARGB) after starting on [initial]: a changed colour is applied and recorded as recent.
 * A live target is always re-applied, because its samples may have moved the pen away from [initial] and back again.
 */
internal fun spuitCommit(target: ColorTarget, initial: Long, final: Long): SpuitCommit {
    val changed = final != initial
    return SpuitCommit(setTarget = changed || spuitLiveTarget(target), addRecent = changed)
}

/** Keeps the widget wholly inside the [width] x [height] area. */
internal fun clampSpuitOffset(x: Float, y: Float, width: Float, height: Float, boxW: Float, boxH: Float): Offset =
    Offset(x.coerceIn(0f, (width - boxW).coerceAtLeast(0f)), y.coerceIn(0f, (height - boxH).coerceAtLeast(0f)))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Reads one pixel of [window] at window pixel ([x], [y]) (what is on screen, ink included); [onColor] gets an opaque ARGB int. */
internal fun sampleWindowPixel(window: Window, x: Int, y: Int, onColor: (Int) -> Unit) {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    PixelCopy.request(window, Rect(x, y, x + 1, y + 1), bitmap, { result ->
        if (result == PixelCopy.SUCCESS) onColor(bitmap.getPixel(0, 0) or (0xFF shl 24))
    }, Handler(Looper.getMainLooper()))
}

/**
 * Eyedropper mode ("Eyedropper tool"): while it is on the panels are hidden; touching or dragging anywhere reads the colour on
 * screen under the finger into the widget's colour circle and reports each sample to [onSample] (live). Tapping the colour circle
 * applies the colour ([onApply]); the cross closes the mode ([onClose]). Both report the last sampled colour. Gestures that start
 * on the widget (grip, icon, circle, cross) do not sample. The 142 x 40 dp widget starts 12 dp / 45 dp from the top-left and its
 * grip drags it around.
 */
@Composable
internal fun EyedropperOverlay(
    initial: Color,
    onSample: (Color) -> Unit,
    onApply: (Color) -> Unit,
    onClose: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findActivity()
    var current by remember { mutableStateOf(initial) }
    var layerLeft by remember { mutableStateOf(0f) }
    var layerTop by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val boxW = with(density) { SpuitWidth.toPx() }
        val boxH = with(density) { SpuitHeight.toPx() }
        var pos by remember(areaW, areaH) {
            mutableStateOf(clampSpuitOffset(with(density) { SpuitDefaultX.toPx() }, with(density) { SpuitDefaultY.toPx() }, areaW, areaH, boxW, boxH))
        }
        // Touch layer: sample on down and while dragging.
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { val b = it.boundsInWindow(); layerLeft = b.left; layerTop = b.top }
                .pointerInput(activity) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var last: Offset = down.position
                        val onWidget = down.position.x in pos.x..(pos.x + boxW) && down.position.y in pos.y..(pos.y + boxH)
                        if (onWidget) {
                            // Touches on the widget are its own controls; the layer below must not sample the dialog pixels there.
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        fun sample(o: Offset) {
                            val w = activity?.window ?: return
                            if (busy) return
                            busy = true
                            sampleWindowPixel(w, (layerLeft + o.x).roundToInt(), (layerTop + o.y).roundToInt()) { argb ->
                                current = Color(argb); busy = false
                                onSample(current)
                            }
                            // PixelCopy always calls back; guard a failed copy so sampling can resume.
                            Handler(Looper.getMainLooper()).postDelayed({ busy = false }, 120)
                        }
                        sample(last)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            last = change.position
                            sample(last)
                        }
                    }
                },
        )
        val dark = SameSameTheme.colors.isDark
        Surface(
            modifier = Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }.size(SpuitWidth, SpuitHeight),
            shape = CircleShape,
            color = SameSameTheme.colors.dialog,
            border = BorderStroke(1.dp, if (dark) Color(0xFF454545) else Color(0xFFC7C7C7)),
            shadowElevation = 4.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Grip: two columns of three dots, 16 x 24 dp at 9 dp.
                Canvas(
                    Modifier.align(Alignment.CenterStart).offset(x = 9.dp).size(16.dp, 24.dp)
                        .pointerInput(areaW, areaH) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                pos = clampSpuitOffset(pos.x + drag.x, pos.y + drag.y, areaW, areaH, boxW, boxH)
                            }
                        },
                ) {
                    val c = Color(0xFF8E8E8E)
                    for (col in 0..1) for (row in 0..2) drawCircle(c, 1.5.dp.toPx(), Offset(size.width * (0.3f + 0.4f * col), size.height * (0.2f + 0.3f * row)))
                }
                Icon(AppIcons.Eyedropper, stringResource(R.string.feature_composer_eyedropper),
                    Modifier.align(Alignment.CenterStart).offset(x = 31.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = 67.dp).size(24.dp).clip(CircleShape).background(current)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape)
                        .clickable(role = Role.Button) { onApply(current) }
                        .semantics { contentDescription = "#%06X".format(current.toArgbInt() and 0xFFFFFF) },
                )
                Box(
                    Modifier.align(Alignment.CenterEnd).offset(x = (-15).dp).size(24.dp).clip(CircleShape).clickable(role = Role.Button) { onClose(current) },
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.Close, stringResource(R.string.feature_composer_close), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}
