package org.samesame.notes.feature.composer.input

import androidx.compose.ui.geometry.Offset
import kotlin.math.max

/**
 * How a pointer gesture is handled, decided once at pen-down and upgraded on a second finger (docs section 3).
 * [ObjectPinch]: two fingers that both landed on the selected object scale / rotate it instead of zooming the page.
 */
enum class GestureKind { Ink, Tool, Pan, PanZoom, TextTouch, ObjectPinch, Ignored }

enum class InputMode { Text, Pen, Reading }
enum class InputTool { Pen, Highlighter, Eraser, Selection }
enum class PointerKind { Stylus, StylusEraser, Finger }

/** Snapshot of the editor state the router depends on. */
data class InputSettings(
    val mode: InputMode = InputMode.Pen,
    val tool: InputTool = InputTool.Pen,
    /** Defaults to true until a stylus has been seen (doc section 3); the caller persists it. */
    val fingerDrawing: Boolean = true,
    val zoomLock: Boolean = false,
    /** Contacts with a larger major axis than this are palms. */
    val palmTouchMajorPx: Float = 200f,
    /** Text/reading mode: movement before a touch becomes a scroll. */
    val touchSlopPx: Float = 16f,
)

/** One pointer of an event, platform-independent so decisions are testable on the JVM. */
data class PointerState(
    val id: Long,
    val kind: PointerKind,
    val pressed: Boolean,
    val position: Offset,
    val touchMajorPx: Float = 0f,
    val canceled: Boolean = false,
    /** Stylus barrel button held. */
    val button: Boolean = false,
)

/** [stylusHovering]: a stylus is in range (hover) or down; touches are then palms. */
data class InputFrame(val pointers: List<PointerState>, val stylusHovering: Boolean = false)

sealed interface ToolEvent {
    data class Down(val position: Offset, val type: InputToolType, val tool: InputTool) : ToolEvent
    data class Move(val position: Offset) : ToolEvent
    data object Up : ToolEvent
    data object Cancel : ToolEvent
}

/** The two fingers of a [GestureKind.ObjectPinch] (view px). */
sealed interface PinchEvent {
    data class Start(val a: Offset, val b: Offset) : PinchEvent
    data class Move(val a: Offset, val b: Offset) : PinchEvent
    data object End : PinchEvent
}

/**
 * What to do with an event. [consumeIds] are consumed at the Initial pass: ink (the child) never sees
 * them, and a stroke whose pointer gets consumed is cancelled. [pan] is in view px, [zoom] a factor
 * around [focus].
 */
data class RouteDecision(
    val kind: GestureKind,
    val consumeIds: Set<Long> = emptySet(),
    val cancelInk: Boolean = false,
    val pan: Offset = Offset.Zero,
    val zoom: Float = 1f,
    val focus: Offset = Offset.Zero,
    val tool: ToolEvent? = null,
    val ended: Boolean = false,
    val pinch: PinchEvent? = null,
)

/**
 * Pure state machine behind `Modifier.editorInput`. Feed it one [InputFrame] per pointer event.
 * [pageHit] tells whether a view position is on a page (pen-down outside a page is ignored in Pages mode);
 * [startsTextInteraction] lets the text layer keep drags that begin on a selection handle. [startsObjectGesture]: text
 * mode, true when a touch lands on the selected object's frame (body, handles, rotate knob); that gesture goes to the
 * selection tool (move / resize / rotate) without leaving text mode. It is also asked (text and pen mode) when a second
 * finger lands: if both fingers started on the selected object the gesture becomes [GestureKind.ObjectPinch].
 */
class InputRouter(
    private val settings: () -> InputSettings,
    private val pageHit: (Offset) -> Boolean = { true },
    private val startsTextInteraction: (Offset) -> Boolean = { false },
    private val startsObjectGesture: (Offset) -> Boolean = { false },
) {
    private class Gesture(
        var kind: GestureKind,
        var owner: Long,
        var ownerKind: PointerKind,
        var tool: InputTool?,
        val startPos: Offset,
    ) {
        val ignored = HashSet<Long>()
        val known = HashSet<Long>()
        val last = HashMap<Long, Offset>()
        var slopPassed = false
        /** Where the current owner went down (follows owner changes, unlike [startPos]). */
        var ownerStart: Offset = startPos
        /** The second finger of an [GestureKind.ObjectPinch] (the owner is the first). */
        var pinchPartner: Long = -1
    }

    private var g: Gesture? = null

    val activeKind: GestureKind? get() = g?.kind

    fun reset() { g = null }

    fun route(frame: InputFrame): RouteDecision {
        val s = settings()
        val pressed = frame.pointers.filter { it.pressed }
        if (pressed.isEmpty()) return end()

        var cur = g
        var cancelInk = false
        var toolEvent: ToolEvent? = null

        if (cur == null) {
            // Gesture start. A stylus beats any finger that landed in the same frame.
            val first = pressed.firstOrNull { it.kind != PointerKind.Finger } ?: pressed.first()
            cur = newGesture(first, frame, s)
            g = cur
            cur.known += pressed.map { it.id }
            for (p in pressed) if (p.id != first.id) {
                if (!cur.ignored.contains(p.id)) classifyExtra(cur, p, s) // simultaneous extra pointers behave like later ones
            }
            if (cur.kind == GestureKind.Tool) toolEvent = ToolEvent.Down(first.position, first.kind.toolType(), cur.tool!!)
            for (p in pressed) cur.last[p.id] = p.position
            return RouteDecision(
                cur.kind, consume(cur, pressed, includeOwner = cur.kind != GestureKind.Ink && cur.kind != GestureKind.TextTouch),
                tool = toolEvent, cancelInk = false, pinch = pinchPositions(cur, pressed)?.let { PinchEvent.Start(it.first, it.second) },
            )
        }

        // Existing gesture: newly arrived pointers.
        val newOnes = pressed.filter { it.id !in cur.known }
        cur.known += newOnes.map { it.id }
        var pinchEvent: PinchEvent? = null
        for (p in newOnes) {
            val before = cur.kind
            val taken = classifyExtra(cur, p, s)
            if (taken) {
                if (before == GestureKind.Ink) cancelInk = true
                if (before == GestureKind.Tool) toolEvent = ToolEvent.Cancel
                if (cur.kind == GestureKind.Tool && before != GestureKind.Tool) {
                    toolEvent = ToolEvent.Down(p.position, p.kind.toolType(), cur.tool!!)
                }
                if (cur.kind == GestureKind.ObjectPinch && before != GestureKind.ObjectPinch) {
                    pinchEvent = pinchPositions(cur, pressed)?.let { PinchEvent.Start(it.first, it.second) }
                }
            }
        }
        // The owner lifted but others remain (e.g. one of two fingers): promote a remaining one.
        val ownerPressed = pressed.any { it.id == cur.owner }
        if (!ownerPressed && cur.kind in setOf(GestureKind.Ink, GestureKind.Tool)) {
            // The drawing pointer is gone: the gesture ended for ink/tool; remaining pointers stay ignored until all lift.
            if (cur.kind == GestureKind.Tool) toolEvent = ToolEvent.Up
            cur.kind = GestureKind.Ignored
        }
        if (cur.kind == GestureKind.ObjectPinch && pinchEvent == null) {
            // Either finger lifting ends (commits) the pinch; the other one is ignored until it lifts too.
            val both = pinchPositions(cur, pressed)
            if (both == null) {
                pinchEvent = PinchEvent.End
                cur.kind = GestureKind.Ignored
            } else {
                pinchEvent = PinchEvent.Move(both.first, both.second)
            }
        }

        var decision = when (cur.kind) {
            GestureKind.Pan, GestureKind.PanZoom -> transform(cur, pressed, s)
            GestureKind.TextTouch -> textTouch(cur, pressed, s)
            GestureKind.Tool -> {
                val o = pressed.firstOrNull { it.id == cur.owner }
                if (toolEvent == null && o != null) toolEvent = ToolEvent.Move(o.position)
                RouteDecision(GestureKind.Tool)
            }
            else -> RouteDecision(cur.kind)
        }
        for (p in pressed) cur.last[p.id] = p.position
        val consumeIds = consume(cur, pressed, includeOwner = cancelInk || (decision.kind != GestureKind.Ink && decision.kind != GestureKind.TextTouch))
        decision = decision.copy(consumeIds = consumeIds, cancelInk = cancelInk, tool = toolEvent ?: decision.tool, pinch = pinchEvent)
        return decision
    }

    private fun end(): RouteDecision {
        val ended = g
        g = null
        return when {
            ended == null -> RouteDecision(GestureKind.Ignored, ended = true)
            ended.kind == GestureKind.Tool -> RouteDecision(GestureKind.Tool, tool = ToolEvent.Up, ended = true)
            ended.kind == GestureKind.ObjectPinch -> RouteDecision(GestureKind.ObjectPinch, pinch = PinchEvent.End, ended = true)
            else -> RouteDecision(ended.kind, ended = true)
        }
    }

    /** The owner's and the partner's positions while both are down. */
    private fun pinchPositions(cur: Gesture, pressed: List<PointerState>): Pair<Offset, Offset>? {
        if (cur.kind != GestureKind.ObjectPinch) return null
        val a = pressed.firstOrNull { it.id == cur.owner } ?: return null
        val b = pressed.firstOrNull { it.id == cur.pinchPartner } ?: return null
        return a.position to b.position
    }

    private fun newGesture(p: PointerState, frame: InputFrame, s: InputSettings): Gesture {
        fun gesture(kind: GestureKind, tool: InputTool? = null) = Gesture(kind, p.id, p.kind, tool, p.position)
        val stylus = p.kind != PointerKind.Finger
        if (!stylus && isPalm(p, frame, s)) return gesture(GestureKind.Ignored).also { it.ignored += p.id }

        return when (s.mode) {
            InputMode.Reading -> gesture(GestureKind.TextTouch)
            InputMode.Text ->
                if (startsObjectGesture(p.position)) gesture(GestureKind.Tool, InputTool.Selection) else gesture(GestureKind.TextTouch)
            InputMode.Pen -> when {
                !stylus && !s.fingerDrawing ->
                    // Zoom lock: the canvas is locked, so only two fingers scroll (docs 3, #68).
                    if (s.zoomLock) gesture(GestureKind.Ignored) else gesture(GestureKind.Pan)
                else -> {
                    val tool = if (p.kind == PointerKind.StylusEraser || (stylus && p.button)) InputTool.Eraser else s.tool
                    val drawing = tool == InputTool.Pen || tool == InputTool.Highlighter
                    when {
                        !pageHit(p.position) -> gesture(GestureKind.Ignored)
                        drawing -> gesture(GestureKind.Ink, tool)
                        else -> gesture(GestureKind.Tool, tool)
                    }
                }
            }
        }
    }

    /**
     * Handles a pointer that arrives while a gesture is running. Returns true when the gesture was
     * changed by it (so ink must be cancelled etc.).
     */
    private fun classifyExtra(cur: Gesture, p: PointerState, s: InputSettings): Boolean {
        val stylus = p.kind != PointerKind.Finger
        val ownerIsFinger = cur.ownerKind == PointerKind.Finger
        if (!stylus) {
            val ownerStylusActive = !ownerIsFinger && cur.kind in setOf(GestureKind.Ink, GestureKind.Tool)
            if (ownerStylusActive || p.canceled || p.touchMajorPx > s.palmTouchMajorPx || (!ownerIsFinger && s.mode == InputMode.Pen)) {
                cur.ignored += p.id // palm while the pen is writing
                return false
            }
            if (cur.owner in cur.ignored) {
                // The first contact was a palm; this finger is a fresh gesture.
                val ng = newGesture(p, InputFrame(emptyList()), s)
                cur.kind = ng.kind; cur.owner = p.id; cur.ownerKind = p.kind; cur.tool = ng.tool; cur.ownerStart = p.position
                return true
            }
            if (ownerIsFinger && s.mode != InputMode.Reading &&
                cur.kind in ONE_FINGER_KINDS && startsObjectGesture(cur.ownerStart) && startsObjectGesture(p.position)
            ) {
                // Both fingers on the selected object: scale / rotate it (the page does not zoom).
                cur.kind = GestureKind.ObjectPinch
                cur.tool = null
                cur.pinchPartner = p.id
                cur.slopPassed = true
                return true
            }
            if (cur.kind == GestureKind.ObjectPinch) {
                cur.ignored += p.id // a third finger
                return false
            }
            // Second finger: stop whatever the first one did and scroll (+ pinch unless zoom lock).
            cur.kind = GestureKind.PanZoom
            cur.tool = null
            cur.slopPassed = true
            return true
        }
        // A stylus arriving while fingers pan/ink/are ignored takes over (palm down first, then the pen).
        if (s.mode == InputMode.Pen && cur.ownerKind == PointerKind.Finger) {
            val dummy = InputFrame(emptyList(), true)
            val ng = newGesture(p, dummy, s)
            val prev = cur.kind
            cur.kind = ng.kind
            cur.owner = p.id
            cur.ownerKind = p.kind
            cur.ownerStart = p.position
            cur.tool = ng.tool
            cur.ignored.addAll(cur.known.filter { it != p.id })
            return prev != cur.kind || prev == GestureKind.Ink
        }
        cur.ignored += p.id
        return false
    }

    private fun isPalm(p: PointerState, frame: InputFrame, s: InputSettings) =
        frame.stylusHovering || p.canceled || p.touchMajorPx > s.palmTouchMajorPx

    private fun consume(cur: Gesture, pressed: List<PointerState>, includeOwner: Boolean): Set<Long> {
        val ids = HashSet<Long>()
        for (p in pressed) {
            if (p.id in cur.ignored) ids += p.id
            else if (includeOwner) ids += p.id
        }
        return ids
    }

    private fun transform(cur: Gesture, pressed: List<PointerState>, s: InputSettings): RouteDecision {
        val fingers = pressed.filter { it.id !in cur.ignored && it.id in cur.last }
        val used = if (cur.kind == GestureKind.PanZoom) fingers else fingers.take(1)
        if (used.isEmpty()) return RouteDecision(cur.kind)
        val prev = used.map { cur.last.getValue(it.id) }
        val now = used.map { it.position }
        val c0 = centroid(prev)
        val c1 = centroid(now)
        val zoomAllowed = !(s.zoomLock && s.mode == InputMode.Pen)
        var factor = 1f
        if (cur.kind == GestureKind.PanZoom && used.size >= 2 && zoomAllowed) {
            val d0 = spread(prev, c0)
            val d1 = spread(now, c1)
            if (d0 > 1f) factor = d1 / d0
        }
        return RouteDecision(cur.kind, pan = c1 - c0, zoom = factor, focus = c1)
    }

    private fun textTouch(cur: Gesture, pressed: List<PointerState>, s: InputSettings): RouteDecision {
        val o = pressed.firstOrNull { it.id == cur.owner } ?: return RouteDecision(GestureKind.TextTouch)
        if (!cur.slopPassed) {
            if ((o.position - cur.startPos).getDistance() < s.touchSlopPx) return RouteDecision(GestureKind.TextTouch)
            if (s.mode == InputMode.Text && startsTextInteraction(cur.startPos)) return RouteDecision(GestureKind.TextTouch)
            cur.slopPassed = true
            cur.kind = GestureKind.Pan
            // Baseline at the slop crossing, so the content does not jump.
            cur.last[o.id] = o.position
            return RouteDecision(GestureKind.Pan, focus = o.position)
        }
        return RouteDecision(GestureKind.Pan)
    }

    private fun centroid(ps: List<Offset>): Offset {
        var x = 0f; var y = 0f
        for (p in ps) { x += p.x; y += p.y }
        return Offset(x / max(1, ps.size), y / max(1, ps.size))
    }

    private fun spread(ps: List<Offset>, c: Offset): Float = ps.map { (it - c).getDistance() }.average().toFloat()

    private companion object {
        /** Kinds a single finger can be in when the second one lands (a pinch on the object can start from them). */
        val ONE_FINGER_KINDS = setOf(GestureKind.Ink, GestureKind.Tool, GestureKind.Pan, GestureKind.TextTouch)
    }

    private fun PointerKind.toolType() = when (this) {
        PointerKind.Stylus -> InputToolType.Stylus
        PointerKind.StylusEraser -> InputToolType.StylusEraser
        PointerKind.Finger -> InputToolType.Finger
    }
}
