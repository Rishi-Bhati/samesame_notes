package org.samesame.notes.core.richtext.layout

import org.samesame.notes.core.model.document.ObjectLimits

/**
 * One page of the given size whose content rect is inset by [padding]: the geometry of a text box's text. Shared by the
 * editor's text box painter and the export renderer so both wrap a box's text the same way.
 */
data class TextBoxGeometry(val width: Float, val height: Float, val padding: Float = ObjectLimits.TEXT_BOX_PADDING_PU) : TextPageGeometry {
    override val pageCount: Int get() = 1
    override val continuous: Boolean get() = false
    override fun pageRect(index: Int) = PuRect(0f, 0f, width, height)
    override fun contentRect(index: Int) = PuRect(padding, padding, width - padding, height - padding)
    override val nextPageContentHeight: Float get() = height - 2 * padding

    companion object {
        /** Text of a box never paginates: the engine lays it out on one very tall page and the box clips it. */
        const val UNBOUNDED_HEIGHT = 100_000f
    }
}
