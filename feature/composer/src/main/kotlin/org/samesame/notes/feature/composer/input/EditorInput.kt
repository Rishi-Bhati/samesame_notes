package org.samesame.notes.feature.composer.input

import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.samesame.notes.feature.composer.canvas.Viewport

private const val FLAG_CANCELED = 0x20 // MotionEvent.FLAG_CANCELED (API 33)
private const val STYLUS_LINGER_MS = 500L

/**
 * Wraps the canvas and inspects every pointer event at the Initial pass, before the `InProgressStrokes`
 * child (docs section 3). Consuming an event steers ink: the child only starts strokes for unconsumed
 * downs and cancels a stroke whose pointer was consumed. Pans, pinches and flings go to [viewport];
 * eraser/selection gestures go to [toolHandler].
 */
fun Modifier.editorInput(
    router: InputRouter,
    viewport: Viewport,
    toolHandler: (InputTool) -> ToolHandler? = { null },
    onStylusSeen: () -> Unit = {},
    /**
     * Pull past the end to add a page: non-null while it is allowed (Pages mode), with the scroll axis (true = vertical).
     * One-finger pans that hit the end feed the pull; lifting releases it (no fling then).
     */
    pullToAddPage: () -> Pair<org.samesame.notes.feature.composer.canvas.PullToAddPageState, Boolean>? = { null },
): Modifier = pointerInput(router, viewport) {
    coroutineScope {
        var flingJob: Job? = null
        val tracker = VelocityTracker()
        var virtual = Offset.Zero
        var stylusInRange = false
        var lastStylusUptime = -STYLUS_LINGER_MS * 4
        var activeTool = InputTool.Eraser
        // Persisting "a stylus was seen" is a one-shot: reporting it for every hover/move event would
        // write DataStore (and re-emit tool prefs) at pointer rate.
        var stylusReported = false
        awaitEachGestureLoop { event ->
            val now = event.changes.firstOrNull()?.uptimeMillis ?: 0L
            for (c in event.changes) {
                if (c.type == PointerType.Stylus || c.type == PointerType.Eraser) {
                    if (!stylusReported) {
                        stylusReported = true
                        onStylusSeen()
                    }
                    lastStylusUptime = now
                    stylusInRange = event.type != PointerEventType.Exit
                }
            }
            val hovering = stylusInRange || (lastStylusUptime >= 0 && now - lastStylusUptime < STYLUS_LINGER_MS)
            val frame = event.toFrame(hovering)
            if (event.changes.any { it.pressed && !it.previousPressed }) flingJob?.cancel()

            val d = router.route(frame)
            if (d.consumeIds.isNotEmpty()) {
                for (c in event.changes) if (c.id.value in d.consumeIds) c.consume()
            }
            when (val t = d.tool) {
                is ToolEvent.Down -> {
                    activeTool = t.tool
                    toolHandler(t.tool)?.onDown(DocPoint(viewport.viewToDoc(t.position)), t.type)
                }
                is ToolEvent.Move -> toolHandler(activeTool)?.onMove(listOf(DocPoint(viewport.viewToDoc(t.position))))
                ToolEvent.Up -> toolHandler(activeTool)?.onUp()
                ToolEvent.Cancel -> toolHandler(activeTool)?.onCancel()
                null -> Unit
            }
            when (val pe = d.pinch) {
                is PinchEvent.Start ->
                    toolHandler(InputTool.Selection)?.onPinchStart(DocPoint(viewport.viewToDoc(pe.a)), DocPoint(viewport.viewToDoc(pe.b)))
                is PinchEvent.Move ->
                    toolHandler(InputTool.Selection)?.onPinchMove(DocPoint(viewport.viewToDoc(pe.a)), DocPoint(viewport.viewToDoc(pe.b)))
                PinchEvent.End -> toolHandler(InputTool.Selection)?.onPinchEnd()
                null -> Unit
            }
            if (d.kind == GestureKind.Pan || d.kind == GestureKind.PanZoom) {
                val pull = pullToAddPage()
                if (!d.ended) {
                    if (pull != null && d.kind == GestureKind.Pan && d.pan != Offset.Zero) {
                        panWithPull(viewport, d.pan, d.focus, pull.first, pull.second)
                        virtual += d.pan
                        tracker.addPosition(now, virtual)
                    } else if (d.pan != Offset.Zero || d.zoom != 1f) {
                        // A pinch never adds a page: a pull in progress springs back.
                        if (pull != null && pull.first.raw > 0f) launch { pull.first.release(allowCommit = false) }
                        viewport.transformBy(d.pan, d.zoom, d.focus)
                        virtual += d.pan
                        tracker.addPosition(now, virtual)
                    }
                } else if (pull != null && pull.first.raw > 0f) {
                    // Pulled past the end: commit or spring back instead of flinging into the end.
                    launch { pull.first.release() }
                    tracker.resetTracking()
                    virtual = Offset.Zero
                } else {
                    val v = tracker.calculateVelocity()
                    flingJob = launch { viewport.fling(Offset(v.x, v.y)) }
                    tracker.resetTracking()
                    virtual = Offset.Zero
                }
            } else if (d.ended) {
                tracker.resetTracking()
                virtual = Offset.Zero
            }
        }
    }
}

/**
 * One-finger pan with the pull: a pull being shown is undone first when the finger comes back, then the content scrolls,
 * and whatever the scroll could not take at the end (along the scroll axis) grows the pull.
 */
private fun panWithPull(
    viewport: Viewport,
    pan: Offset,
    focus: Offset,
    pull: org.samesame.notes.feature.composer.canvas.PullToAddPageState,
    vertical: Boolean,
) {
    val along = if (vertical) pan.y else pan.x
    val (pullChange, rest) = org.samesame.notes.feature.composer.canvas.PullToAddPageMath.takeBack(along, pull.raw)
    if (pullChange != 0f) pull.dragBy(pullChange)
    val scrolled = if (vertical) Offset(pan.x, rest) else Offset(rest, pan.y)
    val before = viewport.scroll
    viewport.transformBy(scrolled, 1f, focus)
    val moved = (viewport.scroll - before) * viewport.scale // px the content actually scrolled (towards the end = +)
    val leftover = -(if (vertical) scrolled.y else scrolled.x) - (if (vertical) moved.y else moved.x)
    if (leftover > 0.5f) pull.dragBy(leftover)
}

private suspend inline fun androidx.compose.ui.input.pointer.PointerInputScope.awaitEachGestureLoop(
    crossinline onEvent: (PointerEvent) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            onEvent(awaitPointerEvent(PointerEventPass.Initial))
        }
    }
}

internal fun PointerEvent.toFrame(stylusHovering: Boolean): InputFrame {
    val me: MotionEvent? = motionEvent
    val canceledFlag = me != null && (me.flags and FLAG_CANCELED) != 0
    val stylusButton = me != null &&
        (me.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
    return InputFrame(
        pointers = changes.map { c ->
            val index = me?.findPointerIndex(c.id.value.toInt()) ?: -1
            PointerState(
                id = c.id.value,
                kind = when (c.type) {
                    PointerType.Stylus -> PointerKind.Stylus
                    PointerType.Eraser -> PointerKind.StylusEraser
                    else -> PointerKind.Finger
                },
                pressed = c.pressed && !c.changedToUp(),
                position = c.position,
                touchMajorPx = if (me != null && index >= 0) me.getTouchMajor(index) else 0f,
                canceled = canceledFlag,
                button = stylusButton && c.type == PointerType.Stylus,
            )
        },
        stylusHovering = stylusHovering,
    )
}
