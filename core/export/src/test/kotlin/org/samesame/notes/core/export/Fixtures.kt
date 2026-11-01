package org.samesame.notes.core.export

import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.model.richtext.TextRun
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 1x1 transparent PNG. */
val TINY_PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
)

class FakeAssets(
    override val pageCount: Int = 1,
    override val heightOverWidth: Float = 1.4142857f,
    private val media: Map<String, ByteArray> = emptyMap(),
    private val ink: Set<Int> = emptySet(),
    private val texts: List<RichText> = emptyList(),
) : ExportAssets {
    override fun media(path: String) = media[path]
    override fun inkLayer(pageIndex: Int) = if (pageIndex in ink) RenderedImage(TINY_PNG, 1080, 1527) else null
    override fun fullPage(pageIndex: Int) = RenderedImage(TINY_PNG, 1620, 2291)
    override fun floatingTexts(pageIndex: Int) = if (pageIndex == 0) texts else emptyList()
    override fun pageText(pageIndex: Int) = "page text $pageIndex <&>"
}

/** One paragraph of a fixture: its style and (text, style) runs. */
class Para(val style: ParagraphStyle = ParagraphStyle(), vararg val runs: Pair<String, CharStyle>)

fun rich(vararg ps: Para): RichText {
    val text = StringBuilder()
    val runs = ArrayList<TextRun>()
    val styles = ArrayList<ParagraphStyle>()
    ps.forEachIndexed { i, p ->
        if (i > 0) { text.append('\n'); runs += TextRun(1, CharStyle()) }
        for ((t, s) in p.runs) { text.append(t); runs += TextRun(t.length, s) }
        styles += p.style
    }
    return RichText(text.toString(), runs, styles)
}

val BOLD = CharStyle(bold = true)
val ITALIC = CharStyle(italic = true)
val PLAIN = CharStyle()

fun sampleBody(): RichText = rich(
    Para(ParagraphStyle(), "Hello " to PLAIN, "bold" to BOLD, " and " to PLAIN, "italic" to ITALIC),
    Para(ParagraphStyle(list = ListKind.Bullet), "apples" to PLAIN),
    Para(ParagraphStyle(list = ListKind.Numbered), "first" to PLAIN),
    Para(ParagraphStyle(list = ListKind.Numbered), "second" to CharStyle(strikethrough = true, underline = true)),
    Para(ParagraphStyle(list = ListKind.Checklist), "todo" to PLAIN),
    Para(ParagraphStyle(list = ListKind.Checklist, checked = true), "done" to CharStyle(color = 0xFFFF0000, highlight = 0xFFFFFF00, fontSize = 24f)),
    Para(ParagraphStyle(align = TextAlign.Center, indent = 1), "centered <tag> & 'q'" to PLAIN),
)

fun sampleDoc(body: RichText = sampleBody(), title: String = "My Note", objects: List<ImageObject> = emptyList()) =
    NoteDocument(id = "n1", title = title, body = body, inlineObjects = objects, pages = listOf(PageSpec("p1")))

fun docWithImage(): NoteDocument {
    val body = rich(Para(ParagraphStyle(), "before " to PLAIN, OBJECT_CHAR.toString() to CharStyle(objectId = "img1"), " after" to PLAIN))
    return sampleDoc(body, objects = listOf(ImageObject("img1", "media/abc.png", 200f, 100f)))
}

fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() }
    }
    return out
}

fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
