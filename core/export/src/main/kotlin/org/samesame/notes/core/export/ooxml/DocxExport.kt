package org.samesame.notes.core.export.ooxml

import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.export.text.Para
import org.samesame.notes.core.export.text.Span
import org.samesame.notes.core.export.text.rgbHex
import org.samesame.notes.core.export.text.sniffImageMime
import org.samesame.notes.core.export.text.toParagraphs
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import java.io.OutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Minimal hand-written WordprocessingML package.
 *
 * Element order follows ECMA-376: `pPr` = pStyle, numPr, ind, jc; `rPr` = b, i, strike, color, sz, szCs,
 * u, shd (so `highlight` is written as `shd`, which allows arbitrary colours).
 */
object DocxExport {
    private const val NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val PAGE_W = 11906 // twips, A4
    private const val PAGE_H = 16838
    private const val MARGIN = 1134
    private const val HALF_POINTS_PER_SIZE = 24f / Limits.DEFAULT_FONT_SIZE // default size 17 -> 12 pt
    private const val BULLET_ABSTRACT = 0
    private const val DECIMAL_ABSTRACT = 1

    fun write(doc: NoteDocument, assets: ExportAssets, out: OutputStream) {
        val landscape = assets.heightOverWidth < 1f
        val pageW = if (landscape) PAGE_H else PAGE_W
        val pageH = if (landscape) PAGE_W else PAGE_H
        val textW = pageW - 2 * MARGIN
        val textH = pageH - 2 * MARGIN

        val rels = ArrayList<Pair<String, String>>() // id -> media target
        val hyperlinks = LinkedHashMap<String, String>() // uri -> relationship id
        val media = LinkedHashMap<String, ByteArray>() // word/media/x -> bytes
        var docPrId = 1
        val mediaIdByPath = HashMap<String, String>()
        val usedExtensions = linkedSetOf("png")

        fun addMedia(key: String, bytes: ByteArray, mime: String): String =
            mediaIdByPath.getOrPut(key) {
                val ext = imageExtension(mime)
                usedExtensions += ext
                val name = "image${media.size + 1}.$ext"
                media["word/media/$name"] = bytes
                val id = "rIdImg${rels.size + 1}"
                rels += id to "media/$name"
                id
            }

        fun picture(rid: String, cxEmu: Long, cyEmu: Long, descr: String): String {
            val id = docPrId++
            return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                "<wp:extent cx=\"$cxEmu\" cy=\"$cyEmu\"/>" +
                "<wp:docPr id=\"$id\" name=\"Picture $id\" descr=\"${xmlEscape(descr)}\"/>" +
                "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Picture $id\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cxEmu\" cy=\"$cyEmu\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                "</a:graphicData></a:graphic></wp:inline></w:drawing>"
        }

        val objects = doc.inlineObjects.associateBy { it.id }
        val body = StringBuilder()

        if (doc.title.isNotBlank()) {
            body.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>")
                .append(run(doc.title.trim(), null))
                .append("</w:p>")
        }

        val numIds = ArrayList<Int>() // one w:num per numbered list, restarting at 1
        var previousNumbered = false
        fun paragraphXml(p: Para): String {
            val st = p.style
            val ppr = StringBuilder()
            var prefix = ""
            when (st.list) {
                ListKind.Bullet, ListKind.Numbered -> {
                    if (st.list == ListKind.Numbered) {
                        if (!previousNumbered) numIds += numIds.size + 2 // numId 1 = bullets
                    }
                    val numId = if (st.list == ListKind.Bullet) 1 else numIds.last()
                    ppr.append("<w:pStyle w:val=\"ListParagraph\"/>")
                        .append("<w:numPr><w:ilvl w:val=\"${min(st.indent, 8)}\"/><w:numId w:val=\"$numId\"/></w:numPr>")
                }
                ListKind.Checklist -> {
                    prefix = if (st.checked) "☑ " else "☐ "
                    ppr.append("<w:ind w:left=\"${st.indent * 720 + 360}\" w:hanging=\"360\"/>")
                }
                ListKind.None -> if (st.indent > 0) ppr.append("<w:ind w:left=\"${st.indent * 720}\"/>")
            }
            previousNumbered = st.list == ListKind.Numbered
            when (st.align) {
                TextAlign.Left -> Unit
                TextAlign.Center -> ppr.append("<w:jc w:val=\"center\"/>")
                TextAlign.Right -> ppr.append("<w:jc w:val=\"right\"/>")
            }
            val sb = StringBuilder("<w:p>")
            if (ppr.isNotEmpty()) sb.append("<w:pPr>").append(ppr).append("</w:pPr>")
            if (prefix.isNotEmpty()) sb.append(run(prefix, null))
            for (span in p.spans) {
                if (span.isObject) {
                    when (val obj = objects[span.style.objectId]) {
                        is ImageObject -> {
                            val bytes = assets.media(obj.media)
                            val mime = bytes?.let(::sniffImageMime)
                            if (bytes != null && mime != null) {
                                val rid = addMedia(obj.media, bytes, mime)
                                var w = obj.widthPu / Limits.PAGE_WIDTH_PU * pageW // twips: pu share of the page width
                                var h = obj.heightPu / Limits.PAGE_WIDTH_PU * pageW
                                val k = min(1f, min(textW / w, textH / h))
                                w *= k; h *= k
                                sb.append("<w:r>").append(picture(rid, (w * EMU_PER_TWIP).roundToLong(), (h * EMU_PER_TWIP).roundToLong(), "image")).append("</w:r>")
                            }
                        }
                        is VoiceObject -> sb.append(run("[Voice recording]", null))
                        else -> Unit
                    }
                } else if (span.text.isNotEmpty()) {
                    val uri = span.style.link?.takeIf { it.isNotEmpty() }
                    if (uri == null) sb.append(run(span.text, span))
                    else {
                        val rid = hyperlinks.getOrPut(uri) { "rIdLink${hyperlinks.size + 1}" }
                        sb.append("<w:hyperlink r:id=\"$rid\" w:history=\"1\">").append(run(span.text, span)).append("</w:hyperlink>")
                    }
                }
            }
            return sb.append("</w:p>").toString()
        }

        fun appendRichText(rt: RichText) {
            previousNumbered = false
            for (p in rt.toParagraphs()) body.append(paragraphXml(p))
        }
        appendRichText(doc.body)
        for (page in 0 until assets.pageCount) {
            for (box in assets.floatingTexts(page)) appendRichText(box)
            val ink = assets.inkLayer(page) ?: continue
            val rid = addMedia("ink-$page", ink.png, "image/png")
            val k = min(textW.toFloat() / ink.widthPx, textH.toFloat() / ink.heightPx)
            val cx = (ink.widthPx * k * EMU_PER_TWIP).roundToLong()
            val cy = (ink.heightPx * k * EMU_PER_TWIP).roundToLong()
            body.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r>")
                .append(picture(rid, cx, cy, "Handwriting, page ${page + 1}")).append("</w:r></w:p>")
        }
        if (body.isEmpty()) body.append("<w:p/>")

        val orient = if (landscape) " w:orient=\"landscape\"" else ""
        val sect = "<w:sectPr><w:pgSz w:w=\"$pageW\" w:h=\"$pageH\"$orient/>" +
            "<w:pgMar w:top=\"$MARGIN\" w:right=\"$MARGIN\" w:bottom=\"$MARGIN\" w:left=\"$MARGIN\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>"
        val documentXml = XML_DECL + "<w:document $NS><w:body>$body$sect</w:body></w:document>"

        val pkg = OpcPackage(out)
        val defaults = usedExtensions.joinToString("") { "<Default Extension=\"$it\" ContentType=\"image/${if (it == "jpeg") "jpeg" else it}\"/>" }
        pkg.part(
            "[Content_Types].xml",
            XML_DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>$defaults" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
                "</Types>",
        )
        pkg.part(
            "_rels/.rels",
            XML_DECL + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        )
        val relXml = StringBuilder(XML_DECL + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        relXml.append("<Relationship Id=\"rIdStyles\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>")
        relXml.append("<Relationship Id=\"rIdNumbering\" Type=\"$REL_NS/numbering\" Target=\"numbering.xml\"/>")
        for ((uri, id) in hyperlinks) {
            relXml.append("<Relationship Id=\"$id\" Type=\"$REL_NS/hyperlink\" Target=\"${xmlEscape(uri)}\" TargetMode=\"External\"/>")
        }
        for ((id, target) in rels) relXml.append("<Relationship Id=\"$id\" Type=\"$REL_NS/image\" Target=\"$target\"/>")
        relXml.append("</Relationships>")
        pkg.part("word/_rels/document.xml.rels", relXml.toString())
        pkg.part("word/document.xml", documentXml)
        pkg.part("word/styles.xml", stylesXml())
        pkg.part("word/numbering.xml", numberingXml(numIds))
        for ((name, bytes) in media) pkg.part(name, bytes)
        pkg.finish()
    }

    private fun run(text: String, span: Span?): String {
        val s = span?.style
        val rpr = StringBuilder()
        if (s != null) {
            if (s.bold) rpr.append("<w:b/>")
            if (s.italic) rpr.append("<w:i/>")
            if (s.strikethrough) rpr.append("<w:strike/>")
            if (s.link != null) rpr.append("<w:color w:val=\"0563C1\"/>")
            else s.color?.let { rpr.append("<w:color w:val=\"${it.rgbHex()}\"/>") }
            s.fontSize?.let {
                val hp = (it * HALF_POINTS_PER_SIZE).roundToInt().coerceIn(2, 3276)
                rpr.append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>")
            }
            if (s.underline || s.link != null) rpr.append("<w:u w:val=\"single\"/>")
            s.highlight?.let { rpr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${it.rgbHex()}\"/>") }
        }
        val pr = if (rpr.isEmpty()) "" else "<w:rPr>$rpr</w:rPr>"
        return "<w:r>$pr<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
    }

    private fun stylesXml(): String = XML_DECL +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"80\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"44\"/><w:szCs w:val=\"44\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"40\"/></w:pPr></w:style>" +
        "</w:styles>"

    /** numId 1 = bullets; numId 2.. = one decimal list each, restarting at 1. */
    private fun numberingXml(decimalNumIds: List<Int>): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        val bullets = listOf("•", "o", "▪")
        sb.append("<w:abstractNum w:abstractNumId=\"$BULLET_ABSTRACT\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            val ch = bullets[l % 3]
            val font = if (l % 3 == 1) "<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>" else ""
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$ch\"/><w:lvlJc w:val=\"left\"/>")
                .append("<w:pPr><w:ind w:left=\"${720 * (l + 1)}\" w:hanging=\"360\"/></w:pPr>")
            if (font.isNotEmpty()) sb.append("<w:rPr>$font</w:rPr>")
            sb.append("</w:lvl>")
        }
        sb.append("</w:abstractNum>")
        val fmts = listOf("decimal", "lowerLetter", "lowerRoman")
        sb.append("<w:abstractNum w:abstractNumId=\"$DECIMAL_ABSTRACT\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${fmts[l % 3]}\"/><w:lvlText w:val=\"%${l + 1}.\"/><w:lvlJc w:val=\"left\"/>")
                .append("<w:pPr><w:ind w:left=\"${720 * (l + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        sb.append("</w:abstractNum>")
        sb.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"$BULLET_ABSTRACT\"/></w:num>")
        for (id in decimalNumIds) {
            sb.append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"$DECIMAL_ABSTRACT\"/>")
            sb.append("<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        }
        return sb.append("</w:numbering>").toString()
    }
}
