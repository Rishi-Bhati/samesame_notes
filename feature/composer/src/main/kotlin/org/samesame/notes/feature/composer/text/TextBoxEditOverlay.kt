package org.samesame.notes.feature.composer.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.withTimeoutOrNull
import org.samesame.notes.core.richtext.input.richTextInput
import org.samesame.notes.core.richtext.layout.ViewTransform
import org.samesame.notes.core.richtext.ui.RichTextCaretBlink
import org.samesame.notes.core.richtext.ui.RichTextToolbarEffect
import org.samesame.notes.core.richtext.ui.richTextGestures
import org.samesame.notes.core.richtext.ui.richTextSelectionDecorations
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.feature.composer.canvas.Viewport
import org.samesame.notes.feature.composer.canvas.drawObjectChrome

/** The box rect of [editor] in view px, for a page whose top-left is [pageOrigin] (document space). */
internal fun TextBoxEditor.viewRect(viewport: Viewport, pageOrigin: Offset): androidx.compose.ui.geometry.Rect {
    val tl = viewport.docToView(Offset(pageOrigin.x + rect.left, pageOrigin.y + rect.top))
    return androidx.compose.ui.geometry.Rect(tl.x, tl.y, tl.x + rect.width * viewport.scale, tl.y + height * viewport.scale)
}

/**
 * The inline editor over a text box: draws the box outline and its text with a caret, takes the IME, caret and selection
 * gestures and the selection handles. A tap outside the box calls [onOutsideTap] (the host ends the edit); drags outside
 * are left to the canvas (scroll, zoom). Fills the canvas overlay; the text lives in the box's local space, mapped by
 * the viewport.
 */
@Composable
internal fun BoxScope.TextBoxEditOverlay(
    editor: TextBoxEditor,
    viewport: Viewport,
    pageOrigin: Offset,
    dark: Boolean,
    onOutsideTap: (Offset) -> Unit,
) {
    val state = editor.state
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val scale = viewport.scale
    val topLeft = viewport.docToView(Offset(pageOrigin.x + editor.rect.left, pageOrigin.y + editor.rect.top))
    SideEffect { state.transform = ViewTransform(scale, topLeft.x, topLeft.y) }
    RichTextCaretBlink(state)
    RichTextToolbarEffect(state) { coords }
    val outsideTap by rememberUpdatedState(onOutsideTap)
    val current by rememberUpdatedState(editor)
    val latestViewport by rememberUpdatedState(viewport)
    val latestOrigin by rememberUpdatedState(pageOrigin)
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { coords = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val e = current
                    if (e.state.handleAt(down.position) != null) return@awaitEachGesture
                    if (e.viewRect(latestViewport, latestOrigin).contains(down.position)) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.size > 1) return@withTimeoutOrNull false
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                            if (!ch.pressed) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    } == true
                    if (tapped) outsideTap(down.position)
                }
            }
            .richTextInput(state)
            .richTextGestures(state)
            .richTextSelectionDecorations(state),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val rev = state.revision
            val t = state.transform
            val h = editor.height
            // the reference's edit chrome: outline, 8 hollow handles and the rotate handle (not interactive while typing).
            val wv = editor.rect.width * t.scale; val hv = h * t.scale
            drawObjectChrome(ObjectFrame(t.translateX + wv / 2f, t.translateY + hv / 2f, wv, hv, 0f), rotatable = true, dark = dark)
            withTransform({
                translate(t.translateX, t.translateY)
                scale(t.scale, t.scale, Offset.Zero)
            }) {
                drawIntoCanvas { state.engine.drawPage(it.nativeCanvas, 0, state.decorations(), inPageSpace = true) }
            }
        }
    }
}

/**
 * Taps on text boxes while no box is edited (text mode): a tap calls [onTap], a long press [onLongPress]. Only gestures
 * that start where [hit] says "a box" are taken and consumed, so the body text under the overlay never sees them.
 * The long press fires a little before the body gesture's own, which is cancelled when the mode changes.
 */
@Composable
internal fun Modifier.textBoxTaps(
    hit: (Offset) -> Boolean,
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
): Modifier {
    val hitNow by rememberUpdatedState(hit)
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Consumed: the canvas gave the press to the selected object's frame (move / resize / rotate).
            if (down.isConsumed || !hitNow(down.position)) return@awaitEachGesture
            // The press is ours: the body text gestures later in the chain skip it (no caret, no word selection).
            down.consume()
            val slop = viewConfiguration.touchSlop
            // 0 = tap, 1 = cancelled (moved, second finger), null = long press.
            val result = withTimeoutOrNull<Int>(viewConfiguration.longPressTimeoutMillis * 9 / 10) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.size > 1) return@withTimeoutOrNull 1
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 1
                    if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull 1
                    if (!ch.pressed) {
                        ch.consume()
                        return@withTimeoutOrNull 0
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                1
            }
            when (result) {
                0 -> tapNow(down.position)
                null -> longNow(down.position)
                else -> Unit
            }
        }
    }
}

/**
 * Observes plain taps (press and release within the touch slop, before the long-press timeout) without consuming
 * anything, so the gestures after it still get the same tap. Presses already consumed by the canvas are skipped.
 */
@Composable
internal fun Modifier.plainTaps(onTap: (Offset) -> Unit): Modifier {
    val tapNow by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.size > 1) return@withTimeoutOrNull false
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                    if (!ch.pressed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (tapped == true) tapNow(down.position)
        }
    }
}
