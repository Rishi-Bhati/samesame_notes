package org.samesame.notes.core.export.ooxml

import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.model.document.NoteDocument
import java.io.OutputStream
import kotlin.math.roundToLong

/**
 * Minimal PresentationML package, one slide per page. The page render is a PNG picture filling the
 * slide; the page text sits in a text box just below the slide edge (invisible when presenting,
 * still searchable and editable in the outline) and the picture's alt text carries the first line.
 */
object PptxExport {
    private const val P = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT = "application/vnd.openxmlformats-officedocument.presentationml"
    private const val GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    fun write(doc: NoteDocument, assets: ExportAssets, out: OutputStream) {
        val ratio = assets.heightOverWidth
        val cx: Long = if (ratio >= 1f) 6858000L else 9144000L
        val cy: Long = (cx * ratio).roundToLong()
        val n = assets.pageCount.coerceAtLeast(1)

        val pkg = OpcPackage(out)
        val slideOverrides = (1..n).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"$CT.slide+xml\"/>" }
        pkg.part(
            "[Content_Types].xml",
            XML_DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$CT.presentation.main+xml\"/>" +
                "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"$CT.slideMaster+xml\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"$CT.slideLayout+xml\"/>" +
                "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                slideOverrides + "</Types>",
        )
        pkg.part(
            "_rels/.rels",
            XML_DECL + "<Relationships xmlns=\"$RELS_NS\"><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>",
        )
        val sldIds = (1..n).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 1}\"/>" }
        pkg.part(
            "ppt/presentation.xml",
            XML_DECL + "<p:presentation $P saveSubsetFonts=\"1\">" +
                "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
                "<p:sldIdLst>$sldIds</p:sldIdLst><p:sldSz cx=\"$cx\" cy=\"$cy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>",
        )
        val presRels = StringBuilder(XML_DECL + "<Relationships xmlns=\"$RELS_NS\">")
        presRels.append("<Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (i in 1..n) presRels.append("<Relationship Id=\"rId${i + 1}\" Type=\"$REL/slide\" Target=\"slides/slide$i.xml\"/>")
        presRels.append("<Relationship Id=\"rId${n + 2}\" Type=\"$REL/theme\" Target=\"theme/theme1.xml\"/></Relationships>")
        pkg.part("ppt/_rels/presentation.xml.rels", presRels.toString())

        pkg.part(
            "ppt/slideMasters/slideMaster1.xml",
            XML_DECL + "<p:sldMaster $P><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GROUP</p:spTree></p:cSld>" +
                "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
                "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>",
        )
        pkg.part(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            XML_DECL + "<Relationships xmlns=\"$RELS_NS\"><Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$REL/theme\" Target=\"../theme/theme1.xml\"/></Relationships>",
        )
        pkg.part(
            "ppt/slideLayouts/slideLayout1.xml",
            XML_DECL + "<p:sldLayout $P type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$GROUP</p:spTree></p:cSld>" +
                "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>",
        )
        pkg.part(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            XML_DECL + "<Relationships xmlns=\"$RELS_NS\"><Relationship Id=\"rId1\" Type=\"$REL/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>",
        )
        pkg.part("ppt/theme/theme1.xml", themeXml())

        for (i in 1..n) {
            val page = i - 1
            val image = if (assets.pageCount > 0) assets.fullPage(page) else null
            val text = (if (i == 1 && doc.title.isNotBlank()) doc.title.trim() + "\n" else "") +
                (if (assets.pageCount > 0) assets.pageText(page) else doc.body.plainText())
            val alt = "Page $i" + doc.title.takeIf { it.isNotBlank() }?.let { " of $it" }.orEmpty()
            val pic = if (image == null) "" else
                "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $i\" descr=\"${xmlEscape(alt)}\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                    "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                    "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
            val paragraphs = text.split('\n').joinToString("") { line ->
                if (line.isEmpty()) "<a:p><a:endParaRPr lang=\"en-US\" sz=\"1200\"/></a:p>"
                else "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1200\"/><a:t>${xmlEscape(line)}</a:t></a:r></a:p>"
            }
            val tbHeight = 3000000L
            val tb = "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Page text\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
                "<p:spPr><a:xfrm><a:off x=\"0\" y=\"${cy + 100000L}\"/><a:ext cx=\"$cx\" cy=\"$tbHeight\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\" rtlCol=\"0\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"
            pkg.part(
                "ppt/slides/slide$i.xml",
                XML_DECL + "<p:sld $P><p:cSld><p:spTree>$GROUP$pic$tb</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>",
            )
            val imgRel = if (image == null) "" else "<Relationship Id=\"rId2\" Type=\"$REL/image\" Target=\"../media/image$i.png\"/>"
            pkg.part(
                "ppt/slides/_rels/slide$i.xml.rels",
                XML_DECL + "<Relationships xmlns=\"$RELS_NS\"><Relationship Id=\"rId1\" Type=\"$REL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>$imgRel</Relationships>",
            )
            if (image != null) pkg.part("ppt/media/image$i.png", image.png)
        }
        pkg.finish()
    }

    private fun themeXml(): String {
        fun fills(): String = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3)
        val lines = "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>".repeat(3)
        val effects = "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3)
        return XML_DECL + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"SameSame\"><a:themeElements>" +
            "<a:clrScheme name=\"SameSame\">" +
            "<a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
            "<a:fontScheme name=\"SameSame\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"SameSame\"><a:fillStyleLst>${fills()}</a:fillStyleLst><a:lnStyleLst>$lines</a:lnStyleLst>" +
            "<a:effectStyleLst>$effects</a:effectStyleLst><a:bgFillStyleLst>${fills()}</a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements></a:theme>"
    }
}
