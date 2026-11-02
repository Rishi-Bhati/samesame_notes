package org.samesame.notes.feature.composer.text

import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.BodyTextEngine
import org.samesame.notes.core.richtext.PreparedTextLayout
import org.samesame.notes.core.richtext.layout.TextPageGeometry
import org.samesame.notes.core.richtext.spans.TextRenderEnv
import org.samesame.notes.feature.composer.perf.EditorTrace

/** The editor's body text and its shaped layout, built before the editor composes (see [prepareEditorText]). */
class PreparedEditorText(val body: BodyText, val layout: PreparedTextLayout)

/**
 * Builds the body text and shapes the whole note body for [geometry]. Span building plus the one `DynamicLayout` that
 * shapes every line (and loads the first glyph faces) is the most expensive editor-owned work of an open; run this on
 * Dispatchers.Default while the editor still shows its blank page, and the first frame only draws.
 *
 * The returned body must be edited on the main thread only (as before); [BodyTextEngine] adopts the layout as is.
 */
fun prepareEditorText(document: NoteDocument, geometry: TextPageGeometry): PreparedEditorText {
    val body = EditorTrace.section("open:prewarmBody") { BodyText(document.body, TextRenderEnv()) }
    val layout = EditorTrace.section("open:prewarmLayout") { BodyTextEngine.prepare(body, geometry) }
    return PreparedEditorText(body, layout)
}
