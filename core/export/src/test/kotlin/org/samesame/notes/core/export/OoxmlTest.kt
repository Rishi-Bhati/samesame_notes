package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.ooxml.DocxExport
import org.samesame.notes.core.export.ooxml.PptxExport
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream

class OoxmlTest {
    private val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun docx(doc: org.samesame.notes.core.model.document.NoteDocument, assets: ExportAssets) =
        unzip(ByteArrayOutputStream().also { DocxExport.write(doc, assets, it) }.toByteArray())

    @Test fun docxHasRequiredPartsAndWellFormedXml() {
        val parts = docx(docWithImage(), FakeAssets(media = mapOf("media/abc.png" to TINY_PNG), ink = setOf(0)))
        assertThat(parts.keys.first()).isEqualTo("[Content_Types].xml")
        assertThat(parts.keys).containsAtLeast(
            "[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels",
            "word/styles.xml", "word/numbering.xml", "word/media/image1.png", "word/media/image2.png",
        )
        for ((name, bytes) in parts) if (name.endsWith(".xml") || name.endsWith(".rels")) parseXml(bytes)
        val rels = String(parts.getValue("word/_rels/document.xml.rels"))
        assertThat(rels).contains("Target=\"media/image1.png\"")
        val doc = parseXml(parts.getValue("word/document.xml"))
        assertThat(doc.getElementsByTagNameNS(W, "drawing").length).isEqualTo(2)
    }

    @Test fun docxRunPropertiesFollowSchemaOrder() {
        val parts = docx(sampleDoc(), FakeAssets())
        val doc = parseXml(parts.getValue("word/document.xml"))
        val order = listOf("b", "i", "strike", "color", "sz", "szCs", "highlight", "u", "shd")
        val rprs = doc.getElementsByTagNameNS(W, "rPr")
        var sawAll = setOf<String>()
        for (i in 0 until rprs.length) {
            val names = (0 until rprs.item(i).childNodes.length).map { rprs.item(i).childNodes.item(it).localName }
            val idx = names.map { order.indexOf(it) }
            assertThat(idx).doesNotContain(-1)
            assertThat(idx).isInOrder()
            sawAll = sawAll + names
        }
        assertThat(sawAll).containsAtLeast("b", "i", "strike", "color", "sz", "u", "shd")
        val text = String(parts.getValue("word/document.xml"))
        assertThat(text).contains("☐ ")
        assertThat(text).contains("☑ ")
        // two bullet/number groups: numbered list gets its own w:num restarting at 1
        assertThat(String(parts.getValue("word/numbering.xml"))).contains("w:numId=\"2\"")
        // paragraph property order: pStyle, numPr, ind, jc
        val pprs = doc.getElementsByTagNameNS(W, "pPr")
        val pOrder = listOf("pStyle", "numPr", "ind", "jc")
        for (i in 0 until pprs.length) {
            val idx = (0 until pprs.item(i).childNodes.length).map { pOrder.indexOf((pprs.item(i).childNodes.item(it) as Element).localName) }
            assertThat(idx).isInOrder()
        }
    }

    @Test fun docxEscapesAndSurvivesEmptyNote() {
        val weird = rich(Para(org.samesame.notes.core.model.richtext.ParagraphStyle(), "a\u0001<b>&\"" to PLAIN))
        val parts = docx(sampleDoc(weird, title = ""), FakeAssets())
        assertThat(String(parts.getValue("word/document.xml"))).contains("a&lt;b&gt;&amp;&quot;")
        parseXml(docx(sampleDoc(org.samesame.notes.core.model.richtext.RichText(), title = ""), FakeAssets()).getValue("word/document.xml"))
    }

    @Test fun pptxHasOnePerPageAndConsistentRelationships() {
        val out = ByteArrayOutputStream().also { PptxExport.write(sampleDoc(), FakeAssets(pageCount = 3), it) }.toByteArray()
        val parts = unzip(out)
        assertThat(parts.keys.first()).isEqualTo("[Content_Types].xml")
        for (n in 1..3) {
            assertThat(parts.keys).containsAtLeast("ppt/slides/slide$n.xml", "ppt/slides/_rels/slide$n.xml.rels", "ppt/media/image$n.png")
        }
        assertThat(parts.keys).containsAtLeast(
            "_rels/.rels", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml",
        )
        for ((name, bytes) in parts) if (name.endsWith(".xml") || name.endsWith(".rels")) parseXml(bytes)
        val pres = parseXml(parts.getValue("ppt/presentation.xml"))
        assertThat(pres.getElementsByTagNameNS("*", "sldId").length).isEqualTo(3)
        // every relationship target exists
        for ((name, bytes) in parts.filterKeys { it.endsWith(".rels") }) {
            val base = name.substringBefore("_rels/")
            val rels = parseXml(bytes).getElementsByTagName("Relationship")
            for (i in 0 until rels.length) {
                val target = (rels.item(i) as Element).getAttribute("Target")
                val resolved = java.net.URI(base).resolve(target).normalize().path
                assertThat(parts.keys).contains(resolved)
            }
        }
        assertThat(String(parts.getValue("ppt/slides/slide1.xml"))).contains("page text 0 &lt;&amp;&gt;")
        assertThat(String(parts.getValue("ppt/slides/slide1.xml"))).contains("My Note")
    }
}
