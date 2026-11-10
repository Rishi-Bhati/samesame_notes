package org.samesame.notes.core.export.text

import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import java.util.Base64

/**
 * One self-contained HTML file. Spans become inline `style`s (colour, highlight, size, underline,
 * strikethrough); images and ink pages are embedded as base64 PNG/JPEG/GIF data URIs.
 */
object HtmlExport {
    fun render(doc: NoteDocument, assets: ExportAssets): String {
        val objects = doc.inlineObjects.associateBy { it.id }
        val b = StringBuilder()
        b.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        b.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        b.append("<title>").append(escape(doc.title.ifBlank { "Note" })).append("</title>\n")
        b.append(
            "<style>body{font-family:sans-serif;font-size:17px;line-height:1.5;max-width:46em;margin:2em auto;padding:0 1em;color:#000;background:#fff}" +
                "p{margin:.25em 0}ul,ol{margin:.25em 0;padding-left:1.5em}ul.check{list-style:none;padding-left:0}" +
                "img{max-width:100%;height:auto}figure.ink{margin:1.5em 0;border:1px solid #ddd}</style>\n",
        )
        b.append("</head>\n<body>\n")
        if (doc.title.isNotBlank()) b.append("<h1>").append(escape(doc.title.trim())).append("</h1>\n")
        body(b, doc.body, assets) { span ->
            when (val obj = objects[span.style.objectId]) {
                is ImageObject -> assets.media(obj.media)?.let { imgTag(it, obj.widthPu) } ?: ""
                is VoiceObject -> "<em>[Voice recording]</em>"
                else -> ""
            }
        }
        for (page in 0 until assets.pageCount) {
            for (box in assets.floatingTexts(page)) body(b, box, assets) { "" }
            val ink = assets.inkLayer(page) ?: continue
            b.append("<figure class=\"ink\"><img alt=\"Handwriting, page ${page + 1}\" src=\"data:image/png;base64,")
                .append(Base64.getEncoder().encodeToString(ink.png)).append("\"></figure>\n")
        }
        b.append("</body>\n</html>\n")
        return b.toString()
    }

    private fun body(b: StringBuilder, rt: RichText, assets: ExportAssets, obj: (Span) -> String) {
        var openTag: String? = null // "ul" / "ol"
        var openKey: Triple<ListKind, Int, Boolean>? = null
        fun close() { openTag?.let { b.append("</$it>\n") }; openTag = null; openKey = null }
        for (p in rt.toParagraphs()) {
            val style = p.style
            val align = when (style.align) {
                TextAlign.Left -> ""
                TextAlign.Center -> "text-align:center;"
                TextAlign.Right -> "text-align:right;"
            }
            val inner = p.spans.joinToString("") { span -> if (span.isObject) obj(span) else spanHtml(span) }
            if (style.list == ListKind.None) {
                close()
                val margin = if (style.indent > 0) "margin-left:${style.indent * 1.5}em;" else ""
                val css = align + margin
                b.append("<p").append(if (css.isEmpty()) "" else " style=\"$css\"").append(">")
                    .append(inner.ifEmpty { "<br>" }).append("</p>\n")
                continue
            }
            val key = Triple(style.list, style.indent, false)
            if (openKey != key) {
                close()
                val margin = if (style.indent > 0) "margin-left:${style.indent * 1.5}em;" else ""
                when (style.list) {
                    ListKind.Numbered -> {
                        b.append("<ol start=\"${p.number ?: 1}\"").append(if (margin.isEmpty()) "" else " style=\"$margin\"").append(">\n")
                        openTag = "ol"
                    }
                    ListKind.Checklist -> {
                        b.append("<ul class=\"check\"").append(if (margin.isEmpty()) "" else " style=\"$margin\"").append(">\n")
                        openTag = "ul"
                    }
                    else -> {
                        b.append("<ul").append(if (margin.isEmpty()) "" else " style=\"$margin\"").append(">\n")
                        openTag = "ul"
                    }
                }
                openKey = key
            }
            val box = when (style.list) {
                ListKind.Checklist -> if (style.checked) "☑ " else "☐ "
                else -> ""
            }
            b.append("<li").append(if (align.isEmpty()) "" else " style=\"$align\"").append(">").append(box).append(inner).append("</li>\n")
        }
        close()
    }

    private fun spanHtml(span: Span): String {
        val s = span.style
        val css = StringBuilder()
        s.color?.let { css.append("color:#").append(it.rgbHex()).append(';') }
        s.highlight?.let { css.append("background-color:#").append(it.rgbHex()).append(';') }
        s.fontSize?.let { css.append("font-size:").append(trim(it / Limits.DEFAULT_FONT_SIZE)).append("em;") }
        if (s.bold) css.append("font-weight:bold;")
        if (s.italic) css.append("font-style:italic;")
        val deco = listOfNotNull("underline".takeIf { s.underline }, "line-through".takeIf { s.strikethrough })
        if (deco.isNotEmpty()) css.append("text-decoration:").append(deco.joinToString(" ")).append(';')
        val text = escape(span.text)
        val styled = if (css.isEmpty()) text else "<span style=\"$css\">$text</span>"
        val link = s.link
        return if (link.isNullOrEmpty()) styled else "<a href=\"${escape(link)}\">$styled</a>"
    }

    private fun trim(f: Float): String = "%.3f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.')

    private fun imgTag(bytes: ByteArray, widthPu: Float): String {
        val mime = sniffImageMime(bytes) ?: return ""
        val width = if (widthPu > 0) " width=\"${widthPu.toInt()}\"" else ""
        return "<img alt=\"image\"$width src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\">"
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}

/** "image/png" etc. from magic bytes; null for formats Office/browsers may not handle. */
fun sniffImageMime(b: ByteArray): String? = when {
    b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
    b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
    b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
    else -> null
}
