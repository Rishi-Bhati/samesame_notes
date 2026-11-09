package org.samesame.notes.core.richtext.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.magnifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.samesame.notes.core.richtext.input.richTextInput
import org.samesame.notes.core.richtext.layout.PuRect
import org.samesame.notes.core.richtext.layout.ViewTransform

private const val CARET_BLINK_MS = 500L

/**
 * Text-mode touch handling (doc §3): tap places the caret (or toggles a checkbox), double tap and
 * long press select a word, long-press-drag extends by words, handles drag the selection ends.
 * Drags that start elsewhere are left to the host (scroll/zoom): this never consumes them.
 * WP-G's InputRouter can call the same `RichTextEditorState.on…` methods directly instead.
 */
fun Modifier.richTextGestures(state: RichTextEditorState): Modifier = pointerInput(state) {
    var lastTapTime = 0L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // A press taken by an overlay before us (an object on the page, the canvas' object frame) is not a text gesture.
        if (!state.acceptsGestures || down.isConsumed) return@awaitEachGesture
        val h = state.handleAt(down.position)
        if (h != null) {
            down.consume()
            state.beginHandleDrag(h, down.position)
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    state.dragHandle(ch.position)
                }
            } finally {
                state.endHandleDrag()
            }
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        // 0 = up (tap), 1 = cancelled (moved / consumed / second finger), null = long press.
        val result = withTimeoutOrNull<Int>(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.size > 1) return@withTimeoutOrNull 1
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 1
                if (ch.isConsumed) return@withTimeoutOrNull 1
                if (!ch.pressed) return@withTimeoutOrNull 0
                if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull 1
            }
            @Suppress("UNREACHABLE_CODE")
            1
        }
        when (result) {
            0 -> {
                val now = System.currentTimeMillis()
                val isDouble = now - lastTapTime < viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - lastTapPos).getDistance() < slop * 4
                if (isDouble) state.onDoubleTap(down.position) else state.onTap(down.position)
                lastTapTime = if (isDouble) 0L else now
                lastTapPos = down.position
            }
            null -> {
                state.onLongPress(down.position)
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        state.onLongPressDrag(ch.position)
                    }
                } finally {
                    state.onLongPressEnd()
                }
            }
            else -> Unit
        }
    }
}

/** Reading mode: a plain tap opens the link under the finger; nothing is consumed so scroll and zoom keep working. */
fun Modifier.richTextReadingTaps(state: RichTextEditorState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val up = withTimeoutOrNull<Boolean>(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.size > 1) return@withTimeoutOrNull false
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!ch.pressed) return@withTimeoutOrNull true
                if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (up == true) state.onReadTap(down.position)
    }
}

/**
 * Draws selection handles over the content and shows the magnifier while a handle is dragged.
 * Apply on the same node as [richTextGestures] (view px coordinates = the state's transform space).
 */
fun Modifier.richTextSelectionDecorations(state: RichTextEditorState): Modifier = composed {
    val density = LocalDensity.current
    state.handleRadius = with(density) { 11.dp.toPx() }
    this
        .magnifier(sourceCenter = { state.magnifierCenter ?: Offset.Unspecified })
        .drawWithHandles(state)
}

private fun Modifier.drawWithHandles(state: RichTextEditorState): Modifier = drawWithContent {
    drawContent()
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    drawHandles(state)
}

private fun DrawScope.drawHandles(state: RichTextEditorState) {
    val handles = state.visibleHandles()
    if (handles.isEmpty()) return
    val r = state.handleRadius
    val color = Color(state.colors.handle)
    val sel = state.body.selection
    for (h in handles) {
        val anchor = state.caretInView(if (h == SelectionHandle.End) sel.max else sel.min).second
        when (h) {
            SelectionHandle.End -> drawPath(teardrop(anchor, r, sharpTopLeft = true), color)
            SelectionHandle.Start -> drawPath(teardrop(anchor, r, sharpTopLeft = false), color)
            SelectionHandle.Insertion -> rotate(45f, pivot = anchor) { drawPath(teardrop(anchor, r, sharpTopLeft = true), color) }
        }
    }
}

/** A rounded square with one sharp corner at [anchor] (Android-style text handle). */
private fun teardrop(anchor: Offset, r: Float, sharpTopLeft: Boolean): Path = Path().apply {
    val rr = CornerRadius(r, r)
    if (sharpTopLeft) {
        addRoundRect(RoundRect(Rect(anchor.x, anchor.y, anchor.x + 2 * r, anchor.y + 2 * r), CornerRadius.Zero, rr, rr, rr))
    } else {
        addRoundRect(RoundRect(Rect(anchor.x - 2 * r, anchor.y, anchor.x, anchor.y + 2 * r), rr, CornerRadius.Zero, rr, rr))
    }
}

/**
 * Shows the platform floating text toolbar (Cut/Copy/Paste/Select all) when the state asks for it.
 * [coordinates] maps the editor's view px to the root (from `onGloballyPositioned`).
 */
@Composable
fun RichTextToolbarEffect(state: RichTextEditorState, coordinates: () -> LayoutCoordinates?) {
    val toolbar = LocalTextToolbar.current
    val readOnly = !state.enabled && state.readOnlySelect
    val show = state.toolbarVisible && state.handle == null &&
        ((state.isFocused && state.enabled) || (readOnly && state.hasSelection()))
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    SideEffect {
        if (!show) {
            if (toolbar.status == TextToolbarStatus.Shown) toolbar.hide()
            return@SideEffect
        }
        val local = state.selectionRectInView()
        val origin = coordinates()?.takeIf { it.isAttached }?.localToRoot(Offset.Zero) ?: Offset.Zero
        val rect = local.translate(origin).let { if (it.height <= 0f) it.copy(bottom = it.top + 1f) else it }
        val hasSel = state.hasSelection()
        // E1 (owner decision): the view state offers Cut next to Copy. The host enters edit mode at the selection and
        // cuts; the library never edits a body it shows read-only, so no handler means no Cut.
        val cut = when {
            !hasSel -> null
            readOnly -> state.onReadOnlyCut
            else -> ({ state.cut() })
        }
        toolbar.showMenu(
            rect = rect,
            onCopyRequested = if (hasSel) ({ state.copy() }) else null,
            onPasteRequested = if (!readOnly && state.canPaste()) ({ state.paste() }) else null,
            onCutRequested = cut,
            onSelectAllRequested = if (state.body.length > 0) ({ state.selectAll() }) else null,
        )
    }
}

/** Caret blink: on for 500 ms, off for 500 ms, restarted by every change. */
@Composable
fun RichTextCaretBlink(state: RichTextEditorState) {
    val rev = state.revision
    LaunchedEffect(rev, state.isFocused) {
        state.caretBlinkOn = true
        if (!state.isFocused) return@LaunchedEffect
        while (true) {
            delay(CARET_BLINK_MS)
            state.caretBlinkOn = !state.caretBlinkOn
        }
    }
}

/** Draws the body (all visible pages) into this DrawScope using the state's transform. */
fun DrawScope.drawRichText(state: RichTextEditorState) {
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    @Suppress("UNUSED_VARIABLE") val blink = state.caretBlinkOn
    val t = state.transform
    val visible = PuRect(t.viewToDocX(0f), t.viewToDocY(0f), t.viewToDocX(size.width), t.viewToDocY(size.height))
    withTransform({
        translate(t.translateX, t.translateY)
        scale(t.scale, t.scale, Offset.Zero)
    }) {
        drawIntoCanvas { state.engine.drawDocument(it.nativeCanvas, state.decorations(), visible) }
    }
}

/**
 * Self-contained editor surface: draws [pageBackground] and the body for every visible page with
 * the state's transform, and wires IME, gestures, handles, magnifier, toolbar and caret blink.
 * Scrolling/zooming is the host's job (it sets `state.transform`). The composer (WP-J) draws pages
 * itself and uses the pieces instead: [richTextInput], [richTextGestures],
 * [richTextSelectionDecorations], [RichTextToolbarEffect], [RichTextCaretBlink] and
 * `engine.drawPage`.
 */
@Composable
fun RichTextEditor(
    state: RichTextEditorState,
    modifier: Modifier = Modifier,
    pageBackground: DrawScope.(pageIndex: Int, pageRect: Rect) -> Unit = { _, _ -> },
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    RichTextCaretBlink(state)
    RichTextToolbarEffect(state) { coords }
    Box(
        modifier
            .onGloballyPositioned { coords = it }
            .richTextInput(state)
            .richTextGestures(state)
            .richTextSelectionDecorations(state),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val rev = state.revision
            val t: ViewTransform = state.transform
            val pages = state.engine.pageSlices().size
            for (i in 0 until pages) {
                val r = state.geometry.pageRect(i)
                val vr = Rect(t.docToViewX(r.left), t.docToViewY(r.top), t.docToViewX(r.right), t.docToViewY(r.bottom))
                if (vr.bottom < 0f || vr.top > size.height || vr.right < 0f || vr.left > size.width) continue
                pageBackground(i, vr)
            }
            drawRichText(state)
        }
    }
}
