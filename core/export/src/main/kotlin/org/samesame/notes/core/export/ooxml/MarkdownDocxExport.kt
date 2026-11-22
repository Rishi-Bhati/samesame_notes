package org.samesame.notes.core.export.ooxml

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import java.io.OutputStream
import org.samesame.notes.core.export.text.MarkdownTrees
import org.samesame.notes.core.model.richtext.LinkSchemes

/**
 * Word DOCX export for Markdown notes.
 * Parses CommonMark AST with GFM extensions and maps to WordprocessingML:
 * - Headings 1..6 -> <w:pStyle w:val="Heading$level"/>
 * - Tables -> <w:tbl> with border grid and header shading <w:shd w:fill="F2F2F2"/>
 * - Code blocks -> shaded Consolas monospace text
 * - Task lists & bullet/ordered lists -> <w:numPr> with ☐ / ☑ markers
 * - Block quotes -> indented with left border
 */
object MarkdownDocxExport {

    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val BULLET_ABSTRACT = 0
    private const val DECIMAL_ABSTRACT = 1

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )

    private val parser = Parser.builder().extensions(extensions).build()

    fun write(markdown: String, title: String, out: OutputStream) {
        val documentNode = MarkdownTrees.parse(parser, markdown)
        val hyperlinks = LinkedHashMap<String, String>() // destination -> rId
        val decimalNumIds = ArrayList<Int>() // allocated numIds for ordered lists
        val bodyXml = StringBuilder()

        val displayTitle = title.trim()
        if (displayTitle.isNotEmpty() && !markdown.trimStart().startsWith("#")) {
            bodyXml.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>")
                .append("<w:r><w:t xml:space=\"preserve\">").append(xmlEscape(displayTitle)).append("</w:t></w:r>")
                .append("</w:p>")
        }

        val context = DocxContext(hyperlinks, decimalNumIds)
        var child = documentNode.firstChild
        while (child != null) {
            renderBlock(child, bodyXml, context)
            child = child.next
        }

        bodyXml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>")

        val documentXml = XML_DECL +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<w:body>" + bodyXml.toString() + "</w:body></w:document>"

        val pkg = OpcPackage(out)
        pkg.part(
            "[Content_Types].xml",
            XML_DECL +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
                "</Types>",
        )

        pkg.part(
            "_rels/.rels",
            XML_DECL +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>",
        )

        val relXml = StringBuilder(XML_DECL)
        relXml.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        relXml.append("<Relationship Id=\"rIdStyles\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>")
        relXml.append("<Relationship Id=\"rIdNumbering\" Type=\"$REL_NS/numbering\" Target=\"numbering.xml\"/>")
        for ((uri, id) in hyperlinks) {
            relXml.append("<Relationship Id=\"$id\" Type=\"$REL_NS/hyperlink\" Target=\"${xmlEscape(uri)}\" TargetMode=\"External\"/>")
        }
        relXml.append("</Relationships>")

        pkg.part("word/_rels/document.xml.rels", relXml.toString())
        pkg.part("word/document.xml", documentXml)
        pkg.part("word/styles.xml", stylesXml())
        pkg.part("word/numbering.xml", numberingXml(decimalNumIds))
        pkg.finish()
    }

    private class DocxContext(
        val hyperlinks: LinkedHashMap<String, String>,
        val decimalNumIds: ArrayList<Int>,
        var listNestingLevel: Int = 0,
        var currentNumId: Int = 1,
        var inBlockQuote: Boolean = false,
    ) {
        fun hyperlinkId(url: String): String =
            hyperlinks.getOrPut(url) { "rIdHlink${hyperlinks.size + 1}" }
    }

    private data class InlineStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val code: Boolean = false,
    )

    private fun renderBlock(node: Node, out: StringBuilder, ctx: DocxContext) {
        when (node) {
            is Heading -> {
                val level = node.level.coerceIn(1, 6)
                out.append("<w:p><w:pPr><w:pStyle w:val=\"Heading$level\"/></w:pPr>")
                renderInlines(node, out, ctx, InlineStyle())
                out.append("</w:p>")
            }
            is Paragraph -> {
                out.append("<w:p><w:pPr>")
                if (ctx.inBlockQuote) {
                    out.append("<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"CCCCCC\"/></w:pBdr><w:ind w:left=\"360\"/>")
                }
                out.append("</w:pPr>")
                renderInlines(node, out, ctx, InlineStyle())
                out.append("</w:p>")
            }
            is BlockQuote -> {
                val oldQuote = ctx.inBlockQuote
                ctx.inBlockQuote = true
                var c = node.firstChild
                while (c != null) {
                    renderBlock(c, out, ctx)
                    c = c.next
                }
                ctx.inBlockQuote = oldQuote
            }
            is FencedCodeBlock -> renderCodeBlock(node.literal, out)
            is IndentedCodeBlock -> renderCodeBlock(node.literal, out)
            is TableBlock -> renderTable(node, out, ctx)
            is BulletList -> {
                val oldNumId = ctx.currentNumId
                val oldLevel = ctx.listNestingLevel
                ctx.currentNumId = 1 // abstract 0 = bullets
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) renderListItem(item, out, ctx)
                    item = item.next
                }
                ctx.currentNumId = oldNumId
                ctx.listNestingLevel = oldLevel
            }
            is OrderedList -> {
                val oldNumId = ctx.currentNumId
                val oldLevel = ctx.listNestingLevel
                val newNumId = ctx.decimalNumIds.size + 2 // 1 is bullets, 2+ are numbered
                ctx.decimalNumIds += newNumId
                ctx.currentNumId = newNumId
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) renderListItem(item, out, ctx)
                    item = item.next
                }
                ctx.currentNumId = oldNumId
                ctx.listNestingLevel = oldLevel
            }
            else -> {
                var c = node.firstChild
                while (c != null) {
                    renderBlock(c, out, ctx)
                    c = c.next
                }
            }
        }
    }

    private fun renderListItem(item: ListItem, out: StringBuilder, ctx: DocxContext) {
        val ilvl = ctx.listNestingLevel.coerceIn(0, 8)
        val taskMarker = findTaskMarker(item)
        val boxPrefix = taskMarker?.let { if (it.isChecked) "☑ " else "☐ " }

        var child = item.firstChild
        var firstPara = true
        while (child != null) {
            when (child) {
                is Paragraph -> {
                    out.append("<w:p><w:pPr>")
                    out.append("<w:pStyle w:val=\"ListParagraph\"/>")
                    out.append("<w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"${ctx.currentNumId}\"/></w:numPr>")
                    out.append("</w:pPr>")
                    if (firstPara && boxPrefix != null) {
                        out.append("<w:r><w:t xml:space=\"preserve\">").append(xmlEscape(boxPrefix)).append("</w:t></w:r>")
                    }
                    renderInlines(child, out, ctx, InlineStyle(), skipTaskMarker = true)
                    out.append("</w:p>")
                    firstPara = false
                }
                is BulletList, is OrderedList -> {
                    ctx.listNestingLevel++
                    renderBlock(child, out, ctx)
                    ctx.listNestingLevel--
                }
                else -> renderBlock(child, out, ctx)
            }
            child = child.next
        }
    }

    private fun findTaskMarker(item: ListItem): TaskListItemMarker? {
        var node: Node? = item.firstChild
        while (node != null) {
            if (node is TaskListItemMarker) return node
            if (node is Paragraph) {
                var pNode = node.firstChild
                while (pNode != null) {
                    if (pNode is TaskListItemMarker) return pNode
                    pNode = pNode.next
                }
            }
            node = node.next
        }
        return null
    }

    private fun renderCodeBlock(codeText: String, out: StringBuilder) {
        val lines = codeText.trimEnd('\n').lines()
        for (line in lines) {
            out.append("<w:p><w:pPr>")
            out.append("<w:shd w:fill=\"F5F5F5\"/>")
            out.append("<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>")
            out.append("</w:pPr>")
            out.append("<w:r><w:rPr>")
            out.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
            out.append("<w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/>")
            out.append("</w:rPr>")
            out.append("<w:t xml:space=\"preserve\">").append(xmlEscape(line)).append("</w:t>")
            out.append("</w:r></w:p>")
        }
    }

    private fun renderTable(table: TableBlock, out: StringBuilder, ctx: DocxContext) {
        out.append("<w:tbl>")
        out.append("<w:tblPr>")
        out.append("<w:tblW w:w=\"0\" w:type=\"auto\"/>")
        out.append("<w:tblBorders>")
        out.append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D3D3D3\"/>")
        out.append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D3D3D3\"/>")
        out.append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D3D3D3\"/>")
        out.append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D3D3D3\"/>")
        out.append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D3D3D3\"/>")
        out.append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D3D3D3\"/>")
        out.append("</w:tblBorders>")
        out.append("</w:tblPr>")

        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> renderTableRows(child, isHeader = true, out, ctx)
                is TableBody -> renderTableRows(child, isHeader = false, out, ctx)
                is TableRow -> renderTableRow(child, isHeader = false, out, ctx)
            }
            child = child.next
        }
        out.append("</w:tbl>")
    }

    private fun renderTableRows(container: Node, isHeader: Boolean, out: StringBuilder, ctx: DocxContext) {
        var row = container.firstChild
        while (row != null) {
            if (row is TableRow) renderTableRow(row, isHeader, out, ctx)
            row = row.next
        }
    }

    private fun renderTableRow(row: TableRow, isHeader: Boolean, out: StringBuilder, ctx: DocxContext) {
        out.append("<w:tr>")
        if (isHeader) {
            out.append("<w:trPr><w:tblHeader/></w:trPr>")
        }
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                out.append("<w:tc>")
                out.append("<w:tcPr>")
                if (isHeader || cell.isHeader) {
                    out.append("<w:shd w:fill=\"F2F2F2\"/>")
                }
                out.append("</w:tcPr>")
                out.append("<w:p>")
                renderInlines(cell, out, ctx, InlineStyle(bold = isHeader || cell.isHeader))
                out.append("</w:p>")
                out.append("</w:tc>")
            }
            cell = cell.next
        }
        out.append("</w:tr>")
    }

    private fun renderInlines(node: Node, out: StringBuilder, ctx: DocxContext, style: InlineStyle, skipTaskMarker: Boolean = false) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is TaskListItemMarker -> {
                    if (!skipTaskMarker) {
                        val mark = if (child.isChecked) "☑ " else "☐ "
                        renderRun(mark, style, out)
                    }
                }
                is Text -> renderRun(child.literal, style, out)
                is Code -> renderRun(child.literal, style.copy(code = true), out)
                is Emphasis -> renderInlines(child, out, ctx, style.copy(italic = true))
                is StrongEmphasis -> renderInlines(child, out, ctx, style.copy(bold = true))
                is Strikethrough -> renderInlines(child, out, ctx, style.copy(strikethrough = true))
                is SoftLineBreak -> renderRun(" ", style, out)
                is HardLineBreak -> out.append("<w:r><w:br/></w:r>")
                // A target Word would open as a file or UNC path (file:, \\host) or a script stays plain text.
                is Link -> if (!LinkSchemes.isExportable(child.destination, allowRelative = false)) {
                    renderInlines(child, out, ctx, style)
                } else {
                    val rId = ctx.hyperlinkId(child.destination)
                    out.append("<w:hyperlink r:id=\"$rId\">")
                    renderInlines(child, out, ctx, style)
                    out.append("</w:hyperlink>")
                }
                else -> renderInlines(child, out, ctx, style)
            }
            child = child.next
        }
    }

    private fun renderRun(text: String, style: InlineStyle, out: StringBuilder) {
        val rpr = StringBuilder()
        if (style.bold) rpr.append("<w:b/>")
        if (style.italic) rpr.append("<w:i/>")
        if (style.strikethrough) rpr.append("<w:strike/>")
        if (style.code) {
            rpr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
            rpr.append("<w:shd w:fill=\"F5F5F5\"/>")
        }
        val pr = if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else ""
        out.append("<w:r>").append(pr)
            .append("<w:t xml:space=\"preserve\">").append(xmlEscape(text)).append("</w:t>")
            .append("</w:r>")
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
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:before=\"200\" w:after=\"80\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:before=\"160\" w:after=\"40\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading4\"><w:name w:val=\"heading 4\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:before=\"120\" w:after=\"40\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading5\"><w:name w:val=\"heading 5\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:before=\"80\" w:after=\"20\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading6\"><w:name w:val=\"heading 6\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:before=\"60\" w:after=\"20\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/></w:rPr></w:style>" +
        "</w:styles>"

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
