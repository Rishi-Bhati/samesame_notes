package org.samesame.notes.core.export.text

import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.RichText

class MarkdownResult(val markdown: String, /** path inside the zip (`media/..`) to bytes; empty unless media were written. */ val media: Map<String, ByteArray>)

/**
 * Markdown export. Bold, italic, strikethrough, underline (`<u>`), lists and `- [ ]` checklists.
 * With [writeMedia] inline images and ink pages are written to `media/` and linked; otherwise they
 * become a `[Image]` / `[Handwriting]` placeholder.
 */
object MarkdownExport {
    fun render(doc: NoteDocument, assets: ExportAssets, writeMedia: Boolean): MarkdownResult {
        val media = LinkedHashMap<String, ByteArray>()
        val out = StringBuilder()
        if (doc.title.isNotBlank()) out.append("# ").append(escapeInline(doc.title.trim())).append("\n\n")

        val objects = doc.inlineObjects.associateBy { it.id }
        var previousList = false
        var first = true
        for (p in doc.body.toParagraphs()) {
            val isList = p.style.list != ListKind.None
            if (!first) out.append(if (isList && previousList) "\n" else "\n\n")
            first = false
            val prefix = when (p.style.list) {
                ListKind.None -> ""
                ListKind.Bullet -> "- "
                ListKind.Numbered -> "${p.number ?: 1}. "
                ListKind.Checklist -> if (p.style.checked) "- [x] " else "- [ ] "
            }
            out.append("    ".repeat(p.style.indent)).append(prefix)
            val body = renderSpans(p.spans) { span ->
                when (val obj = objects[span.style.objectId]) {
                    is ImageObject -> {
                        val bytes = assets.media(obj.media)
                        if (writeMedia && bytes != null) {
                            val name = obj.media.substringAfterLast('/')
                            media["media/$name"] = bytes
                            "![image](media/$name)"
                        } else "[Image]"
                    }
                    is VoiceObject -> "[Voice recording]"
                    else -> ""
                }
            }
            out.append(if (p.style.list == ListKind.None) escapeBlockStart(body) else body)
            previousList = isList
        }
        for (page in 0 until assets.pageCount) {
            for (box in assets.floatingTexts(page)) {
                val t = TextExport.renderBody(box).trim()
                if (t.isNotEmpty()) out.append("\n\n").append(escapeInline(t).replace("\n", "  \n"))
            }
            val ink = assets.inkLayer(page) ?: continue
            out.append("\n\n")
            if (writeMedia) {
                val name = "page-${page + 1}-ink.png"
                media["media/$name"] = ink.png
                out.append("![Handwriting, page ${page + 1}](media/$name)")
            } else {
                out.append("[Handwriting, page ${page + 1}]")
            }
        }
        return MarkdownResult(out.toString().trimEnd('\n') + "\n", media)
    }

    private fun renderSpans(spans: List<Span>, obj: (Span) -> String): String {
        // group consecutive text spans with the same emphasis so markers are not repeated
        data class Key(val b: Boolean, val i: Boolean, val s: Boolean, val u: Boolean, val link: String?)
        val sb = StringBuilder()
        var key: Key? = null
        val buf = StringBuilder()
        fun flush() {
            val k = key ?: return
            if (buf.isEmpty()) return
            val raw = buf.toString()
            val core = raw.trim()
            if (core.isEmpty()) { sb.append(raw); buf.setLength(0); return }
            val lead = raw.substring(0, raw.indexOf(core))
            val trail = raw.substring(lead.length + core.length)
            var text = escapeInline(core)
            if (k.u) text = "<u>$text</u>"
            if (k.s) text = "~~$text~~"
            text = when {
                k.b && k.i -> "***$text***"
                k.b -> "**$text**"
                k.i -> "*$text*"
                else -> text
            }
            k.link?.takeIf { it.isNotEmpty() }?.let { text = "[$text](${mdUrl(it)})" }
            sb.append(lead).append(text).append(trail)
            buf.setLength(0)
        }
        for (span in spans) {
            if (span.isObject) { flush(); key = null; sb.append(obj(span)); continue }
            val k = Key(span.style.bold, span.style.italic, span.style.strikethrough, span.style.underline, span.style.link)
            if (k != key) { flush(); key = k }
            buf.append(span.text)
        }
        flush()
        return sb.toString()
    }

    /** Link destination: spaces and parentheses percent-encoded so the markdown link stays well formed. */
    private fun mdUrl(uri: String): String = uri.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
        .replace("<", "%3C").replace(">", "%3E")

    private fun escapeInline(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c in "\\`*_[]<>~|") sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Characters that would turn a plain paragraph into a heading, quote, list... at line start. */
    private fun escapeBlockStart(s: String): String = when {
        s.startsWith("#") || s.startsWith("+") || s.startsWith("-") || s.startsWith("=") -> "\\$s"
        Regex("^\\d+[.)] ").containsMatchIn(s) -> s.replaceFirst(Regex("^(\\d+)([.)])"), "$1\\\\$2")
        else -> s
    }
}
