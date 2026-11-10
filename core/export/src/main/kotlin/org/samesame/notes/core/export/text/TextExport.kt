package org.samesame.notes.core.export.text

import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.listMarkerPrefix

/** Plain-text export: title first, checklists `[ ] `/`[x] `, bullets `• `, numbering `1. `. */
object TextExport {
    const val INDENT = "  "

    fun render(doc: NoteDocument, assets: ExportAssets?): String {
        val out = StringBuilder()
        if (doc.title.isNotBlank()) out.append(doc.title.trim()).append("\n\n")
        out.append(renderBody(doc.body))
        if (assets != null) {
            for (page in 0 until assets.pageCount) {
                for (box in assets.floatingTexts(page)) {
                    val t = renderBody(box).trimEnd()
                    if (t.isNotBlank()) out.append("\n\n").append(t)
                }
            }
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    fun renderBody(body: RichText): String = body.toParagraphs().joinToString("\n") { p ->
        listMarkerPrefix(p.style, p.number) + p.plainWithLinks
    }
}
