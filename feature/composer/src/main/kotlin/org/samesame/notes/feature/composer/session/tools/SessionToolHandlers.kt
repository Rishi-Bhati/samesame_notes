package org.samesame.notes.feature.composer.session.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.ink.geometry.AffineTransform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.ink.tools.AreaEraser
import org.samesame.notes.core.ink.tools.SelectionHitTester
import org.samesame.notes.core.ink.tools.StrokeEraser
import org.samesame.notes.core.ink.tools.StrokeTransforms
import org.samesame.notes.core.model.document.Affine2
import org.samesame.notes.core.model.document.FrameGestures
import org.samesame.notes.core.model.document.FrameHandle
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.core.model.document.Pt
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.feature.composer.input.DocPoint
import org.samesame.notes.feature.composer.input.InputTool
import org.samesame.notes.feature.composer.input.InputToolType
import org.samesame.notes.feature.composer.input.ToolHandler
import org.samesame.notes.feature.composer.input.ToolOverlay
import org.samesame.notes.feature.composer.panels.EraserMode
import org.samesame.notes.feature.composer.panels.SelectionShape
import org.samesame.notes.feature.composer.session.EditorSession
import kotlin.math.max
import kotlin.math.min

/** The page under a document-space point; page space = doc - [origin]. Implemented with `DocumentLayout` by the screen. */
class PageHit(val pageId: String, val origin: Offset)

fun interface PageLocator {
    fun locate(doc: Offset): PageHit?
}

/** A [ToolHandler] that can show an overlay (document space) while a gesture is in flight. */
abstract class SessionToolHandler : ToolHandler {
    protected val _overlay = MutableStateFlow<ToolOverlay?>(null)
    val overlay: StateFlow<ToolOverlay?> = _overlay.asStateFlow()
}

/**
 * Tap-vs-drag for object gestures in the selection tool. the reference reopens the drawing on a
 * tap of the *selected* painting and selects an object on a tap of an
 * *unselected* one. A real finger tap always jitters a little, so a press-release
 * whose pointer stayed within a fraction of the handle-zone radius counts as a tap; anything more is a drag.
 */
object TapReopen {
    /** Movement below this fraction of the handle-zone radius is a tap, not a drag. */
    const val SLOP_FRACTION = 0.5f

    fun isTap(maxDisplacementPu: Float, handleRadiusPu: Float): Boolean =
        maxDisplacementPu <= handleRadiusPu * SLOP_FRACTION

    /** The painting to reopen on tap, or null when the gesture was a drag or not a painting. */
    fun paintingToReopen(paintingTap: org.samesame.notes.core.ink.page.ElementId?, maxDisplacementPu: Float, handleRadiusPu: Float): org.samesame.notes.core.ink.page.ElementId? =
        paintingTap?.takeIf { isTap(maxDisplacementPu, handleRadiusPu) }
}

/**
 * Builds the handlers for `NoteCanvas(toolHandler = handlers::forTool)`. Mode (stroke/area, lasso/rect) and
 * settings are read at pointer-down so a panel change applies to the next gesture.
 */
class SessionToolHandlers(
    private val session: EditorSession,
    private val locator: PageLocator,
    /** Half-size of the transform handles, in page units. */
    private val handleRadiusPu: () -> Float = { 24f },
) {
    val eraser = EraserHandler(session, locator)
    val selection = SelectionGestureHandler(session, locator, handleRadiusPu)

    fun forTool(tool: InputTool): ToolHandler? = when (tool) {
        InputTool.Eraser -> eraser
        InputTool.Selection -> selection
        else -> null
    }

    /** Overlay of whichever handler is active, for `NoteCanvas(overlay = ...)`. */
    val overlays: List<StateFlow<ToolOverlay?>> get() = listOf(eraser.overlay, selection.overlay)
}

/** Stroke or area eraser per the eraser panel; one drag = one history entry. */
class EraserHandler(private val session: EditorSession, private val locator: PageLocator) : SessionToolHandler() {
    private var hit: PageHit? = null
    private var original: List<PageElement> = emptyList()
    private var stroke: StrokeEraser? = null
    private var area: AreaEraser? = null
    private var last: Offset = Offset.Zero
    private var current: List<PageElement> = emptyList()

    override fun onDown(p: DocPoint, tool: InputToolType) {
        val h = locator.locate(p.position) ?: return
        original = session.content(h.pageId)?.elements ?: return
        val t = session.tools.value
        hit = h
        current = original
        if (t.eraserMode == EraserMode.Stroke) {
            stroke = StrokeEraser(original, t.eraserRadiusPu, t.prefs.eraserHighlighterOnly)
        } else {
            area = AreaEraser(original, t.eraserRadiusPu, t.prefs.eraserHighlighterOnly)
        }
        last = p.position - h.origin
        sweep(last, last)
        _overlay.value = ToolOverlay.Circle(p.position, t.eraserRadiusPu)
    }

    override fun onMove(points: List<DocPoint>) {
        val h = hit ?: return
        for (p in points) {
            val now = p.position - h.origin
            sweep(last, now)
            last = now
            _overlay.value = ToolOverlay.Circle(p.position, session.tools.value.eraserRadiusPu)
        }
    }

    private fun sweep(a: Offset, b: Offset) {
        val h = hit ?: return
        val changed = stroke?.let { it.erase(a.x, a.y, b.x, b.y).isNotEmpty() } ?: area?.erase(a.x, a.y, b.x, b.y) ?: false
        if (!changed) return
        current = (stroke?.result() ?: area!!.result()).elements
        session.previewElements(h.pageId, current)
    }

    override fun onUp() {
        val h = hit
        if (h != null) session.recordApplied(h.pageId, original, current)
        reset()
    }

    override fun onCancel() {
        val h = hit
        if (h != null && current !== original) session.previewElements(h.pageId, original)
        reset()
    }

    private fun reset() {
        hit = null; stroke = null; area = null; original = emptyList(); current = emptyList(); _overlay.value = null
    }
}

/**
 * Pointer-down inside the current selection's box starts a transform (move, or scale from a handle); anywhere else
 * starts a new lasso/rectangle selection.
 */
class SelectionGestureHandler(
    private val session: EditorSession,
    private val locator: PageLocator,
    private val handleRadiusPu: () -> Float,
) : SessionToolHandler() {
    private var hit: PageHit? = null
    private val path = ArrayList<Offset>()

    // transform state
    private var handle: ResizeHandle? = null
    private var startBounds: Rect = Rect.Zero
    private var startPoint: Offset = Offset.Zero
    private var original: List<PageElement> = emptyList()
    private var ids: Set<org.samesame.notes.core.ink.page.ElementId> = emptySet()
    private var matrix: AffineTransform? = null
    private var lockAspect = false
    private var minSpan = 0f

    // single-object frame gestures (rotated chrome): which part was grabbed
    private var frame: ObjectFrame? = null
    private var frameHandle: FrameHandle? = null
    private var rotating = false

    /** Set when a press on the selected painting's body began; a tap (release within slop) on it re-opens the sheet. */
    private var paintingTap: org.samesame.notes.core.ink.page.ElementId? = null

    /** Set when a press on the selected text box's body began; a tap on it enters text editing. */
    private var textBoxTap: org.samesame.notes.core.ink.page.ElementId? = null

    /** Where the press landed (page space) and how far the pointer strayed from it; a small stray is still a tap. */
    private var downPage: Offset = Offset.Zero
    private var maxDisp: Float = 0f

    /** the reference: tapping a selected painting in edit mode reopens the brush on it. Called with the page id and the element id. */
    var onSelectedPaintingTap: ((String, org.samesame.notes.core.ink.page.ElementId) -> Unit)? = null

    /**
     * A second tap on an already-selected text box (the same tap the lasso selection shows its menu for) enters
     * text editing. Called with the page id and the element id.
     */
    var onSelectedTextBoxTap: ((String, org.samesame.notes.core.ink.page.ElementId) -> Unit)? = null

    /**
     * False while the handler only transforms a selected object (text mode routes presses on the object's frame here):
     * a press that misses the selection then does nothing instead of starting a lasso.
     */
    var allowLasso: () -> Boolean = { true }

    private fun Affine2.toInk() = androidx.ink.geometry.ImmutableAffineTransform(m00, m10, m20, m01, m11, m21)

    override fun onDown(p: DocPoint, tool: InputToolType) {
        val h = locator.locate(p.position) ?: return
        hit = h
        val sel = session.selection.current
        val page = p.position - h.origin
        downPage = page
        maxDisp = 0f
        val b = sel.bounds
        val fr = sel.frame
        if (fr != null && sel.pageId == h.pageId) {
            val kind = grabFrame(fr, sel.rotatable, page)
            if (kind != null) {
                handle = ResizeHandle.Move // "a transform is in flight"; the frame fields say which
                frame = fr
                frameHandle = (kind as? FrameGrab.Resize)?.handle
                rotating = kind is FrameGrab.Rotate
                startBounds = b ?: Rect.Zero
                startPoint = page
                ids = sel.ids
                original = session.content(h.pageId)?.elements ?: return
                val isImage = original.firstOrNull { it.id in ids }
                    ?.let { (it as? org.samesame.notes.core.ink.page.ObjectElement)?.floating()?.obj }.let { it is ImageObject || it is org.samesame.notes.core.model.document.PaintingObject }
                paintingTap = if (kind is FrameGrab.Move) original.firstOrNull { it.id in ids }
                    ?.takeIf { ((it as? org.samesame.notes.core.ink.page.ObjectElement)?.floating()?.obj) is org.samesame.notes.core.model.document.PaintingObject }?.id else null
                textBoxTap = if (kind is FrameGrab.Move) original.firstOrNull { it.id in ids }
                    ?.takeIf { ((it as? org.samesame.notes.core.ink.page.ObjectElement)?.floating()?.obj) is TextBoxObject }?.id else null
                lockAspect = isImage // text boxes resize freely: the dragged corner must track the finger
                minSpan = org.samesame.notes.core.model.document.ObjectLimits.MIN_SIZE_PU
                return
            }
        } else if (b != null && sel.pageId == h.pageId) {
            val grabbed = grab(b, page)
            if (grabbed != null) {
                handle = grabbed
                startBounds = b
                startPoint = page
                ids = sel.ids
                original = session.content(h.pageId)?.elements ?: return
                val picked = original.filter { it.id in ids }
                val onlyObjects = picked.isNotEmpty() && picked.all { it is org.samesame.notes.core.ink.page.ObjectElement }
                lockAspect = onlyObjects
                minSpan = if (onlyObjects) org.samesame.notes.core.model.document.ObjectLimits.MIN_SIZE_PU else 0f
                return
            }
        }
        if (!allowLasso()) {
            hit = null
            return
        }
        session.clearSelection()
        path.clear()
        path += page
        updateOverlay()
    }

    override fun onMove(points: List<DocPoint>) {
        val h = hit ?: return
        for (pt in points) maxDisp = max(maxDisp, (pt.position - h.origin - downPage).getDistance())
        if (handle != null) {
            val p = points.lastOrNull() ?: return
            val now = p.position - h.origin
            val fr = frame
            val m = when {
                fr != null && rotating ->
                    FrameGestures.rotation(fr, FrameGestures.rotatedAngle(fr, Pt(startPoint.x, startPoint.y), Pt(now.x, now.y))).toInk()
                fr != null && frameHandle != null ->
                    FrameGestures.resize(fr, frameHandle!!, Pt(now.x, now.y), lockAspect, minSpan).toInk()
                else -> SelectionResize.matrix(handle!!, startBounds, startPoint, now, lockAspect, minSpan)
            }
            matrix = m
            session.selection.setLiveTransform(m)
        } else {
            points.forEach { path += it.position - h.origin }
            updateOverlay()
        }
    }

    override fun onUp() {
        val h = hit ?: return reset()
        val m = matrix
        if (handle != null) {
            val reopen = TapReopen.paintingToReopen(paintingTap, maxDisp, handleRadiusPu())
            val enterText = TapReopen.paintingToReopen(textBoxTap, maxDisp, handleRadiusPu())
            when {
                reopen != null -> {
                    // A tap on the selected painting's body (the reference onSelectedItemClicked): reopen the sheet, no nudge.
                    session.selection.setLiveTransform(null)
                    onSelectedPaintingTap?.invoke(h.pageId, reopen)
                }
                enterText != null -> {
                    // A second tap on the selected text box enters editing (the first tap only selected it).
                    session.selection.setLiveTransform(null)
                    onSelectedTextBoxTap?.invoke(h.pageId, enterText)
                }
                TapReopen.isTap(maxDisp, handleRadiusPu()) -> session.selection.setLiveTransform(null)
                else -> if (m != null) session.transformSelection(m) else session.selection.setLiveTransform(null)
            }
        } else {
            val picked = if (TapReopen.isTap(maxDisp, handleRadiusPu())) {
                // A tap (the reference onItemClicked): select the topmost object under it, or clear on empty space.
                listOfNotNull(session.objectAt(h.pageId, downPage))
            } else {
                val elements = session.content(h.pageId)?.elements.orEmpty()
                val partial = session.tools.value.prefs.selectionIncludePartial
                if (session.tools.value.selectionShape == SelectionShape.Lasso) {
                    SelectionHitTester.lasso(elements, FloatArray(path.size * 2) { if (it % 2 == 0) path[it / 2].x else path[it / 2].y }, partial)
                } else if (path.size >= 2) {
                    val a = path.first(); val b = path.last()
                    SelectionHitTester.rectangle(elements, min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), partial)
                } else emptyList()
            }
            session.select(h.pageId, picked)
        }
        reset()
    }

    override fun onCancel() {
        if (handle != null) session.selection.setLiveTransform(null)
        reset()
    }

    /** Two fingers on the selected object: live scale / rotation about its centre ([ObjectPinch]). */
    private var pinch: ObjectPinch? = null

    override fun onPinchStart(a: DocPoint, b: DocPoint) {
        if (handle != null) session.selection.setLiveTransform(null)
        reset()
        val h = locator.locate(a.position) ?: return
        val sel = session.selection.current
        val fr = sel.frame ?: return
        if (sel.pageId != h.pageId) return
        hit = h
        val pa = a.position - h.origin
        val pb = b.position - h.origin
        pinch = ObjectPinch(fr, Pt(pa.x, pa.y), Pt(pb.x, pb.y), sel.rotatable, org.samesame.notes.core.model.document.ObjectLimits.MIN_SIZE_PU)
    }

    override fun onPinchMove(a: DocPoint, b: DocPoint) {
        val h = hit ?: return
        val p = pinch ?: return
        val pa = a.position - h.origin
        val pb = b.position - h.origin
        val m = p.update(Pt(pa.x, pa.y), Pt(pb.x, pb.y)).toInk()
        matrix = m
        session.selection.setLiveTransform(m)
    }

    override fun onPinchEnd() {
        val p = pinch
        val m = matrix
        if (p != null && m != null && p.changed) session.transformSelection(m) // one undo step per gesture
        else if (p != null) session.selection.setLiveTransform(null)
        reset()
    }

    override fun onPinchCancel() {
        if (pinch != null) session.selection.setLiveTransform(null)
        reset()
    }

    private fun reset() {
        hit = null; handle = null; paintingTap = null; textBoxTap = null; frame = null; frameHandle = null; rotating = false; matrix = null; path.clear(); _overlay.value = null
        pinch = null
    }

    private fun updateOverlay() {
        val h = hit ?: return
        val pts = path.map { it + h.origin }
        _overlay.value = if (session.tools.value.selectionShape == SelectionShape.Lasso) ToolOverlay.Path(pts, closed = false)
        else if (pts.size >= 2) ToolOverlay.Rect(pts.first(), pts.last()) else null
    }

    /** True when a press at [p] (page space) on the current selection would start a move/resize rather than a new selection. */
    fun grabs(p: Offset): Boolean {
        val sel = session.selection.current
        sel.frame?.let { return grabFrame(it, sel.rotatable, p) != null }
        return sel.bounds?.let { grab(it, p) != null } ?: false
    }

    private sealed interface FrameGrab {
        data object Rotate : FrameGrab
        data object Move : FrameGrab
        data class Resize(val handle: FrameHandle) : FrameGrab
    }

    /** Hit test of a single object's (rotated) chrome: rotate handle, the 8 handles, then the body (move). */
    private fun grabFrame(fr: ObjectFrame, rotatable: Boolean, page: Offset): FrameGrab? {
        val slop = handleRadiusPu()
        val p = Pt(page.x, page.y)
        if (rotatable) {
            val c = fr.rotateHandlePoint(slop * ROTATE_GAP_DP / HANDLE_ZONE_DP)
            if (kotlin.math.hypot(p.x - c.x, p.y - c.y) <= slop) return FrameGrab.Rotate
        }
        val r = min(slop, min(fr.w, fr.h) / 3f)
        val l = fr.toLocal(p)
        // Corners before edges, like the old axis-aligned test.
        for (hd in FrameHandle.entries.sortedByDescending { it.isCorner }) {
            if (kotlin.math.abs(l.x - hd.nx * fr.w / 2f) <= r && kotlin.math.abs(l.y - hd.ny * fr.h / 2f) <= r) return FrameGrab.Resize(hd)
        }
        return if (fr.contains(p, slop / 2f)) FrameGrab.Move else null
    }

    private companion object {
        /** Handle zone radius is 24 dp; the rotate handle sits 25.5 dp beyond the bottom edge (the reference reference shot). */
        const val HANDLE_ZONE_DP = 24f
        const val ROTATE_GAP_DP = 25.5f
    }

    private fun grab(b: Rect, p: Offset): ResizeHandle? {
        // The handle zones must leave the middle of the box free for Move, so shrink them on small boxes.
        val slop = handleRadiusPu()
        val r = min(slop, min(b.width, b.height) / 3f)
        fun near(x: Float, y: Float) = kotlin.math.abs(p.x - x) <= r && kotlin.math.abs(p.y - y) <= r
        val cx = (b.left + b.right) / 2; val cy = (b.top + b.bottom) / 2
        return when {
            near(b.left, b.top) -> ResizeHandle.TL
            near(b.right, b.top) -> ResizeHandle.TR
            near(b.right, b.bottom) -> ResizeHandle.BR
            near(b.left, b.bottom) -> ResizeHandle.BL
            near(cx, b.top) -> ResizeHandle.T
            near(cx, b.bottom) -> ResizeHandle.B
            near(b.left, cy) -> ResizeHandle.L
            near(b.right, cy) -> ResizeHandle.R
            b.inflate(slop / 2).contains(p) -> ResizeHandle.Move
            else -> null
        }
    }
}
