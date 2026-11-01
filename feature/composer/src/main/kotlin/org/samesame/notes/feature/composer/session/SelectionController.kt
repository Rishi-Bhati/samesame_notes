package org.samesame.notes.feature.composer.session

import androidx.compose.ui.geometry.Rect
import androidx.ink.geometry.AffineTransform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.core.model.document.frame
import org.samesame.notes.core.model.document.isRotatable

/**
 * The current ink selection: elements of one page (NOTE(WP-H): single-page; infinite-mode multi-segment
 * selection is not implemented yet). [bounds] is in that page's space; [liveTransform] is set while a transform
 * drag is in flight (the canvas draws the selected items from their own layer with it, doc 2.5).
 */
data class SelectionState(
    val pageId: String? = null,
    val ids: Set<ElementId> = emptySet(),
    val bounds: Rect? = null,
    val liveTransform: AffineTransform? = null,
    /** The frame (rect + rotation) when the selection is exactly one floating object: it gets rotated chrome and a rotate handle. */
    val frame: ObjectFrame? = null,
    val rotatable: Boolean = false,
) {
    val isEmpty: Boolean get() = ids.isEmpty()
}

class SelectionController {
    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    val current: SelectionState get() = _state.value

    fun select(pageId: String, elements: List<PageElement>) {
        _state.value = if (elements.isEmpty()) SelectionState()
        else frameOf(elements).let { (f, r) -> SelectionState(pageId, elements.mapTo(LinkedHashSet()) { it.id }, unionBounds(elements), frame = f, rotatable = r) }
    }

    fun clear() {
        if (!_state.value.isEmpty || _state.value.liveTransform != null) _state.value = SelectionState()
    }

    fun setLiveTransform(m: AffineTransform?) {
        _state.value = _state.value.copy(liveTransform = m)
    }

    /** Re-derives bounds from the page after an edit/undo; drops ids that no longer exist. */
    fun refresh(pageElements: List<PageElement>?) {
        val s = _state.value
        if (s.isEmpty) return
        val alive = pageElements?.filter { it.id in s.ids }.orEmpty()
        _state.value = if (alive.isEmpty()) SelectionState()
        else frameOf(alive).let { (f, r) -> s.copy(ids = alive.mapTo(LinkedHashSet()) { it.id }, bounds = unionBounds(alive), frame = f, rotatable = r) }
    }

    companion object {
        /** The frame of a single-object selection and whether it can be rotated. */
        fun frameOf(elements: List<PageElement>): Pair<ObjectFrame?, Boolean> {
            val e = elements.singleOrNull() as? ObjectElement ?: return null to false
            val f = e.floating() ?: return null to false
            return f.frame to f.isRotatable
        }

        fun unionBounds(elements: List<PageElement>): Rect? {
            if (elements.isEmpty()) return null
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (e in elements) {
                val b = e.bounds
                x0 = minOf(x0, b.xMin); y0 = minOf(y0, b.yMin); x1 = maxOf(x1, b.xMax); y1 = maxOf(y1, b.yMax)
            }
            return Rect(x0, y0, x1, y1)
        }
    }
}
