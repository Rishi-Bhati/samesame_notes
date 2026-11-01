package org.samesame.notes.core.richtext

import android.graphics.Canvas

/**
 * Body text of one page split for layer caching (the editor canvas): [drawStatic] paints what only changes with the text,
 * its layout, colours, links or the hint, so the canvas records it once into a page layer and replays it while scrolling;
 * [drawUnder] (selection and search highlights, under the glyphs) and [drawOver] (empty-list marker and caret) are cheap
 * and drawn live every frame. Under + static + over paint exactly what `BodyTextEngine.drawPage` paints.
 * All three draw in page space (origin = the page's top-left, pu) and clip themselves to the page. Main thread only.
 */
interface PageTextPainter {
    /**
     * Called once per canvas frame before any page is drawn: snapshots the decorations (reading the observable state
     * the frame depends on) and returns the static version. When it differs from the version a page layer was recorded
     * with, that layer must be re-recorded.
     */
    fun beginFrame(): Long

    fun drawUnder(canvas: Canvas, pageIndex: Int)

    fun drawStatic(canvas: Canvas, pageIndex: Int)

    fun drawOver(canvas: Canvas, pageIndex: Int)
}

/**
 * A monotonic version of everything the static text pass depends on. [update] bumps it when any input differs from the
 * previous call; comparing inputs (instead of hashing them) means two different states can never share a version.
 */
class StaticTextVersion {
    var version: Long = 0L
        private set
    private var layoutVersion = Long.MIN_VALUE
    private var drawRevision = Long.MIN_VALUE
    private var layout: Any? = null
    private var links = false
    private var hint: String? = null

    fun update(layoutVersion: Long, drawRevision: Long, layout: Any?, links: Boolean, hint: String?): Long {
        if (layoutVersion != this.layoutVersion || drawRevision != this.drawRevision || layout !== this.layout ||
            links != this.links || hint != this.hint
        ) {
            this.layoutVersion = layoutVersion
            this.drawRevision = drawRevision
            this.layout = layout
            this.links = links
            this.hint = hint
            version++
        }
        return version
    }
}
