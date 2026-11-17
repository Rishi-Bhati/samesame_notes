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
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Release gate for 0.2.2: a note written by the v8 app (0.2.1) opens as v9 with the same data, including the
 * old `list = "Checklist"` shape in the body AND in floating text boxes stored in page files. The fixture is the
 * v7 document with its version stamped 8 (v8 only added defaulted fields), so its third paragraph is `Checklist`.
 */
class NoteDocumentV8ReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v8Json: String = javaClass.getResource("/fixtures/v7/document.json")!!.readText().trim()
        .replace("\"formatVersion\":7", "\"formatVersion\":8")

    /** A v8 page file: a floating text box whose single paragraph is the old `list:"Checklist"` shape. */
    private val v8TextBoxJson =
        """{"obj":{"type":"textbox","id":"tb1","text":{"text":"Box item","runs":[{"length":8,"style":{}}],""" +
            """"paragraphs":[{"align":"Left","indent":0,"list":"Checklist","checked":true}]},"border":"None"},""" +
            """"rect":{"left":10.0,"top":20.0,"right":550.0,"bottom":220.0},"rotationDeg":0.0,"wrap":"InFront"}"""

    private fun store(root: File) = NoteDocumentStore(root, File(tmp.root, "working-v8"))

    private fun pageBytes(): ByteArray = ByteArrayOutputStream().also {
        PageLayerCodec.encode(
            PageContent("p1", listOf(ObjectElement(ElementId(7), v8TextBoxJson, box(10f, 20f, 550f, 220f))), 0),
            it,
        )
    }.toByteArray()

    @Test fun fixtureIsAV8Document() {
        val doc = NoteDocument.fromJson(v8Json)
        assertThat(doc.formatVersion).isEqualTo(8)
        assertThat(v8Json).contains("\"list\":\"Checklist\"")
        assertThat(v8Json).doesNotContain("\"checkbox\"")
    }

    @Test fun v8ToV9IsTheIdentityApartFromTheVersion() {
        val v8 = NoteDocument.fromJson(v8Json)
        val v9 = NoteDocumentMigrations.v8ToV9(v8)
        assertThat(v9).isEqualTo(v8.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION))
        // The checklist is read as a checkbox at decode time, wherever a paragraph style is stored.
        assertThat(v9.body.paragraphs[2]).isEqualTo(ParagraphStyle(checkbox = true, checked = true))
    }

    @Test fun v8WorkingCopyOpensAsV9AndSavesKeepingEveryCheckbox() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n7/ink").mkdirs()
        File(root, "n7/document.json").writeText(v8Json)
        File(root, "n7/ink/p1.ink").writeBytes(pageBytes())
        val store = store(root)

        val doc = store.readDocument("n7")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(doc.body.paragraphs[2]).isEqualTo(ParagraphStyle(checkbox = true, checked = true))
        assertThat(doc.body.paragraphs[2].list).isEqualTo(ListKind.None)
        val floating = store.readPage("n7", "p1").elements.single() as ObjectElement
        val textBox = FloatingObject.fromJson(floating.json).obj as TextBoxObject
        assertThat(textBox.text.paragraphs.single()).isEqualTo(ParagraphStyle(checkbox = true, checked = true))
        assertThat(textBox.text.paragraphs.single().list).isEqualTo(ListKind.None)

        store.commit("n7", NoteSnapshot(doc))
        store.flush("n7", close = true)
        val reread = store(root).readDocument("n7")
        assertThat(reread).isEqualTo(doc)
        val onDisk = java.util.zip.ZipFile(File(root, "n7.ssnote")).use { zf ->
            zf.getInputStream(zf.getEntry("document.json")).readBytes().decodeToString()
        }
        assertThat(NoteDocument.fromJson(onDisk).formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(onDisk).contains("\"checkbox\":true")
        assertThat(NoteDocument.fromJson(onDisk).body.paragraphs[2].list).isEqualTo(ListKind.None)
    }

    @Test fun v9CombinedCheckboxAndNumberSurviveTheStore() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val created = store.create("n9", org.samesame.notes.core.model.PageMode.Pages, "combined")
        val combined = ParagraphStyle(list = ListKind.Numbered, checkbox = true, checked = false)
        val doc = created.copy(
            body = created.body.copy(paragraphs = listOf(combined, combined.copy(checked = true))),
        )
        store.commit("n9", NoteSnapshot(doc))
        store.flush("n9", close = true)
        assertThat(store(root).readDocument("n9").body.paragraphs)
            .containsExactly(combined, combined.copy(checked = true)).inOrder()
    }
}
