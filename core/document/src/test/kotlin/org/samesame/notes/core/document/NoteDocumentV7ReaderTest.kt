package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSize
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.TextWrap
import org.samesame.notes.core.model.document.VoiceTimelineEvent
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.model.richtext.TextRun
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Release gate for 0.2.0 (owner priority: upgrades never lose data): a note written by the v7 app (0.1.x) opens as the
 * same note under format v8, from the working copy, from its packed `.ssnote` container and from an exported file, and
 * saving it again keeps every field. The fixture `fixtures/v7/document.json` is byte-for-byte what the v7 writer
 * produces (every default written, no `link` / `libraryFileId` keys).
 */
class NoteDocumentV7ReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v7Json: String = javaClass.getResource("/fixtures/v7/document.json")!!.readText().trim()

    /** A v7 page file: one floating image whose JSON has no `libraryFileId` key. */
    private val v7FloatingJson = """{"obj":{"type":"image","id":"img2","media":"media/b.png","widthPu":10.0,"heightPu":20.0,""" +
        """"crop":null},"rect":{"left":1.0,"top":2.0,"right":11.0,"bottom":22.0},"rotationDeg":0.0,"wrap":"Behind"}"""

    private val pngBytes = ByteArray(300) { (it * 7).toByte() }

    /** What the v7 fixture must read as. */
    private val expected = NoteDocument(
        formatVersion = NOTE_DOCUMENT_FORMAT_VERSION,
        id = "n7",
        title = "Trip plan",
        layout = PageLayout(PageMode.Pages, PageRatio.A4Portrait),
        style = NoteStyle(),
        body = RichText(
            text = "Trip plan\nBuy milk\nDone item\nSee ￼ map",
            runs = listOf(
                TextRun(4, CharStyle(bold = true)),
                TextRun(29, CharStyle()),
                TextRun(1, CharStyle(objectId = "img1")),
                TextRun(4, CharStyle(italic = true, underline = true, color = 0xFFFF0000, highlight = 0xFFFFFF00, fontSize = 24f)),
            ),
            paragraphs = listOf(
                ParagraphStyle(),
                ParagraphStyle(indent = 1, list = ListKind.Bullet),
                ParagraphStyle(list = ListKind.Checklist, checked = true),
                ParagraphStyle(align = TextAlign.Center),
            ),
        ),
        inlineObjects = listOf(
            ImageObject("img1", "media/3f2a.png", 320f, 180f, RectPu(0f, 0f, 0.5f, 1f)),
            PaintingObject("d1", "media/t.png", "media/s.drw", RectPu(10f, 20f, 110f, 220f)),
        ),
        recordings = listOf(
            NoteRecording("r1", "media/r.m4a", "Voice recording 1", 1_700_000_000_000L, 18_000L, "00:18", null,
                listOf(VoiceTimelineEvent(1, 0L), VoiceTimelineEvent(4, 18_000L))),
        ),
        pages = listOf(
            PageSpec("p1", TemplateRef.Builtin(PageTemplate.GridMedium), 0xFF00FF00, isBookmarked = true),
            PageSpec("p2", TemplateRef.Image("media/bg.png"), pdf = PdfBackground("media/s.pdf", 1), size = PageSize(842f, 595f)),
        ),
    )

    private val expectedFloating = FloatingObject(ImageObject("img2", "media/b.png", 10f, 20f), RectPu(1f, 2f, 11f, 22f), wrap = TextWrap.Behind)

    private fun store(root: File) = NoteDocumentStore(root, File(tmp.root, "working"))

    private fun pageBytes(): ByteArray = ByteArrayOutputStream().also {
        PageLayerCodec.encode(PageContent("p1", listOf(ObjectElement(ElementId(7), v7FloatingJson, box(1f, 2f, 11f, 22f))), 0), it)
    }.toByteArray()

    /** A packed v7 note, as 0.1.x writes `notes/<id>.ssnote` and its Save as file. */
    private fun v7Container(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            put("manifest.json", """{"format":"samesame-note","formatVersion":7,"containerVersion":2,"noteId":"n7"}""".encodeToByteArray())
            put("document.json", v7Json.encodeToByteArray())
            put("ink/p1.ink", pageBytes())
            put("media/3f2a.png", pngBytes)
        }
    }.toByteArray()

    @Test
    fun fixtureIsAV7Document() {
        assertThat(v7Json).contains("\"formatVersion\":7")
        assertThat(v7Json).doesNotContain("\"link\"")
        assertThat(v7Json).doesNotContain("libraryFileId")
    }

    @Test
    fun v7ToV8IsTheIdentityApartFromTheVersion() {
        val v7 = NoteDocument.fromJson(v7Json)
        assertThat(v7.formatVersion).isEqualTo(7)
        val v8 = NoteDocumentMigrations.v7ToV8(v7)
        assertThat(v8).isEqualTo(v7.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION))
        assertThat(v8).isEqualTo(expected)
        assertThat(v8.body.runs.all { it.style.link == null }).isTrue()
        assertThat(v8.inlineObjects.filterIsInstance<ImageObject>().all { it.libraryFileId == null }).isTrue()
    }

    @Test
    fun v7WorkingCopyOpensUnchangedAndSavesAsV8KeepingEverything() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n7/ink").mkdirs()
        File(root, "n7/document.json").writeText(v7Json)
        File(root, "n7/ink/p1.ink").writeBytes(pageBytes())
        val store = store(root)
        val (doc, legacyViewState) = store.readDocumentWithLegacyViewState("n7")
        assertThat(doc).isEqualTo(expected)
        assertThat(legacyViewState).isNull()
        assertThat(FloatingObject.fromJson((store.readPage("n7", "p1").elements.single() as ObjectElement).json)).isEqualTo(expectedFloating)

        store.commit("n7", NoteSnapshot(doc))
        store.flush("n7", close = true)
        val reread = store(root).readDocument("n7")
        assertThat(reread).isEqualTo(expected)
        val onDisk = java.util.zip.ZipFile(File(root, "n7.ssnote")).use { zf ->
            zf.getInputStream(zf.getEntry("document.json")).readBytes().decodeToString()
        }
        assertThat(NoteDocument.fromJson(onDisk).formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(store(root).readPage("n7", "p1").elements.single()).isInstanceOf(ObjectElement::class.java)
    }

    @Test
    fun v7ContainerOpensUnchangedWithPagesAndMedia() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n7.ssnote").writeBytes(v7Container())
        val store = store(root)
        assertThat(store.readDocument("n7")).isEqualTo(expected)
        val floating = store.readPage("n7", "p1").elements.single() as ObjectElement
        assertThat(FloatingObject.fromJson(floating.json)).isEqualTo(expectedFloating)
        assertThat(store.readMedia("n7", "media/3f2a.png")).isEqualTo(pngBytes)
    }

    @Test
    fun exportedV7NoteImportsUnchanged() = runBlocking<Unit> {
        val store = store(tmp.newFolder("notes"))
        val imported = store.import(v7Container().inputStream())
        assertThat(imported.id).isNotEqualTo("n7")
        assertThat(imported).isEqualTo(expected.copy(id = imported.id))
        assertThat(store.readDocument(imported.id)).isEqualTo(expected.copy(id = imported.id))
        assertThat(store.readMedia(imported.id, "media/3f2a.png")).isEqualTo(pngBytes)
    }

    @Test
    fun v8LinkRunsAndLibraryIdsSurviveTheStore() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val created = store.create("n8", PageMode.Pages, "links")
        val uri = "samesame://file/0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60?n=photo.png"
        val doc = created.copy(
            body = RichText("photo.png ￼", listOf(TextRun(9, CharStyle(link = uri)), TextRun(1, CharStyle()), TextRun(1, CharStyle(objectId = "i")))),
            inlineObjects = listOf(ImageObject("i", "media/a.png", 10f, 10f, libraryFileId = "0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60")),
        )
        store.commit("n8", NoteSnapshot(doc))
        store.flush("n8", close = true)
        assertThat(store(root).readDocument("n8")).isEqualTo(doc)
    }
}
