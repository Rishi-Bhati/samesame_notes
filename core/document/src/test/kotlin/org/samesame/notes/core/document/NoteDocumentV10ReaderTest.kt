package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.PdfTextMarkup
import org.samesame.notes.core.model.document.QuadPu
import org.samesame.notes.core.model.document.RectPu
import java.io.File

/** Release gate for 0.5.0: a note written by the v10 app (0.4.x) opens as v11 unchanged, and plain notes serialize as before. */
class NoteDocumentV10ReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v10Json: String = javaClass.getResource("/fixtures/v7/document.json")!!.readText().trim()
        .replace("\"formatVersion\":7", "\"formatVersion\":10")

    @Test fun `v10ToV11 is the identity apart from the version`() {
        val v10 = NoteDocument.fromJson(v10Json)
        assertThat(v10.formatVersion).isEqualTo(10)
        val v11 = NoteDocumentMigrations.v10ToV11(v10)
        assertThat(v11).isEqualTo(v10.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION))
        assertThat(v11.pages.all { it.textMarkup.isEmpty() && it.rotation == 0 }).isTrue()
    }

    @Test fun `a plain note serializes exactly as v10 did except the version`() {
        val written = NoteDocumentMigrations.v10ToV11(NoteDocument.fromJson(v10Json)).toJson()
        assertThat(written).doesNotContain("textMarkup")
        assertThat(written).doesNotContain("rotation")
        val asV10 = NoteDocument.fromJson(v10Json).toJson()
        assertThat(written.replace("\"formatVersion\":$NOTE_DOCUMENT_FORMAT_VERSION", "\"formatVersion\":10")).isEqualTo(asV10)
    }

    @Test fun `a v10 working copy opens as v11, saves and reopens with markup intact`() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n7/ink").mkdirs()
        File(root, "n7/document.json").writeText(v10Json)
        fun store() = NoteDocumentStore(root, File(tmp.root, "working-v10"))
        val doc = store().readDocument("n7")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(doc).isEqualTo(NoteDocument.fromJson(v10Json).copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION))

        val markup = PdfTextMarkup("m", PdfMarkupKind.Underline, 0xFF0000FFL, listOf(QuadPu.fromRect(RectPu(1f, 2f, 30f, 12f))), "x")
        val edited = doc.copy(pages = doc.pages.mapIndexed { i, p -> if (i == 0) p.copy(textMarkup = listOf(markup), rotation = 270) else p })
        val s = store()
        s.commit("n7", NoteSnapshot(edited))
        s.flush("n7", close = true)
        assertThat(store().readDocument("n7")).isEqualTo(edited)
    }

    @Test fun `a note from a newer app is still refused`() {
        assertThat(PageSpec("p").rotation).isEqualTo(0)
        val root = tmp.newFolder("notes2")
        File(root, "nx/ink").mkdirs()
        File(root, "nx/document.json").writeText("""{"formatVersion":${NOTE_DOCUMENT_FORMAT_VERSION + 1},"id":"nx","pages":[]}""")
        val failed = runCatching { runBlocking { NoteDocumentStore(root, File(tmp.root, "working-x")).readDocument("nx") } }
        assertThat(failed.isFailure).isTrue()
    }
}
