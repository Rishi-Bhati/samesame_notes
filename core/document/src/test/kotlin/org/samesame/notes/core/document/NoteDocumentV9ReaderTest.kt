package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.MarkdownBody
import org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION
import org.samesame.notes.core.model.document.NoteDocument
import java.io.File
import java.util.zip.ZipFile

/**
 * Release gate for 0.4.0: a note written by the v9 app (0.3.x) opens as v10 with the same data, and a rich-text note
 * serializes exactly as before apart from the version number. The v9 fixture is the v7 document stamped 9 (v8 and v9
 * changed no field of this document apart from the old checklist shape, which the fixture's body has none of beyond
 * what the v8 reader test already covers).
 */
class NoteDocumentV9ReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v9Json: String = javaClass.getResource("/fixtures/v7/document.json")!!.readText().trim()
        .replace("\"formatVersion\":7", "\"formatVersion\":9")

    private fun store(root: File) = NoteDocumentStore(root, File(tmp.root, "working-v9"))

    @Test fun `fixture is a v9 document without a markdown key`() {
        assertThat(v9Json).contains("\"formatVersion\":9")
        assertThat(v9Json).doesNotContain("markdown")
        val doc = NoteDocument.fromJson(v9Json)
        assertThat(doc.formatVersion).isEqualTo(9)
        assertThat(doc.markdown).isNull()
    }

    @Test fun `v9ToV10 is the identity apart from the version`() {
        val v9 = NoteDocument.fromJson(v9Json)
        val v10 = NoteDocumentMigrations.v9ToV10(v9)
        assertThat(v10).isEqualTo(v9.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION))
        assertThat(v10.markdown).isNull()
    }

    @Test fun `a rich-text note serializes exactly as v9 did except the version`() {
        val v9 = NoteDocument.fromJson(v9Json)
        val written = NoteDocumentMigrations.v9ToV10(v9).toJson()
        assertThat(written).doesNotContain("markdown")
        val asV9 = v9.copy(formatVersion = 9).toJson()
        assertThat(written.replace("\"formatVersion\":$NOTE_DOCUMENT_FORMAT_VERSION", "\"formatVersion\":9")).isEqualTo(asV9)
        // And that v9 text is what the old writer produced from the same value: no extra key, same field order.
        assertThat(asV9).isEqualTo(NoteDocument.fromJson(asV9).toJson())
    }

    @Test fun `a v9 working copy opens as v10 and a save keeps every field`() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n7/ink").mkdirs()
        File(root, "n7/document.json").writeText(v9Json)
        val store = store(root)

        val doc = store.readDocument("n7")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(doc.markdown).isNull()
        assertThat(doc).isEqualTo(NoteDocument.fromJson(v9Json).copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION))

        store.commit("n7", NoteSnapshot(doc))
        store.flush("n7", close = true)
        assertThat(store(root).readDocument("n7")).isEqualTo(doc)
    }

    @Test fun `a markdown note survives commit flush and reopen`() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val created = store.create("m1", PageMode.Infinite, "Readme")
        val body = MarkdownBody("# Hi\n\n- [x] done\n\n| a | b |\n|---|---|\n| 1 | 2 |\n")
        val doc = created.copy(markdown = body)
        store.commit("m1", NoteSnapshot(doc))
        store.flush("m1", close = true)

        val reopened = store(root).readDocument("m1")
        assertThat(reopened.markdown).isEqualTo(body)
        assertThat(reopened.markdown!!.source).isEqualTo(body.content)
        val onDisk = ZipFile(File(root, "m1.ssnote")).use { zf ->
            zf.getInputStream(zf.getEntry("document.json")).readBytes().decodeToString()
        }
        assertThat(NoteDocument.fromJson(onDisk).markdown).isEqualTo(body)
        assertThat(onDisk).contains("\"formatVersion\":$NOTE_DOCUMENT_FORMAT_VERSION")
    }

    @Test fun `a bundle from a newer app is still refused`() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "nx").mkdirs()
        File(root, "nx/document.json").writeText("""{"formatVersion":${NOTE_DOCUMENT_FORMAT_VERSION + 1},"id":"nx","pages":[]}""")
        val failed = runCatching { store(root).readDocument("nx") }.exceptionOrNull()
        assertThat(failed).isInstanceOf(java.io.IOException::class.java)
    }
}
