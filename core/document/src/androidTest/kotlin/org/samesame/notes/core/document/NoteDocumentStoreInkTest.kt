package org.samesame.notes.core.document

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.document.legacy.NoteDocumentV1
import org.samesame.notes.core.document.legacy.PageSpecV1
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.StrokeCodec
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NoteDocumentStoreInkTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bundle-test-${System.nanoTime()}")
        root.mkdirs()
    }

    private fun stroke(type: PenType, pressure: Boolean, x: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x = x, y = 0f, elapsedTimeMillis = 0, pressure = 0.5f)
            .add(InputToolType.STYLUS, x = x + 10f, y = 12f, elapsedTimeMillis = 16, pressure = 0.7f)
        return Stroke(PenBrushes.brushFor(PenSettings(type, 0xFF1A73E8, 6f, pressure)), inputs)
    }

    private fun writeV1Bundle() {
        val v1 = NoteDocumentV1(id = "n1", pageMode = PageMode.Pages, body = "hello", pages = listOf(PageSpecV1("p1"), PageSpecV1("p2")))
        File(root, "n1/ink").mkdirs()
        File(root, "n1/document.json").writeText(NoteDocumentV1.toJson(v1))
        File(root, "n1/ink/p1.ink").outputStream().use {
            StrokeCodec.encode(listOf(stroke(PenType.Fountain, true, 0f), stroke(PenType.Calligraphy, false, 5f)), it)
        }
    }

    @Test
    fun v1BundleMigratesWithInkAndStableIds() = runBlocking<Unit> {
        writeV1Bundle()
        val store = NoteDocumentStore(root, File(root, "w"))
        val doc = store.readDocument("n1", "Title")
        assertThat(doc.title).isEqualTo("Title")
        assertThat(doc.body.text).isEqualTo("hello")
        val first = store.readPage("n1", "p1").elements
        val second = store.readPage("n1", "p1").elements
        assertThat(first.map { it.id }).containsExactlyElementsIn(second.map { it.id }).inOrder()
        assertThat(first.map { (it as InkElement).stroke.brush.family.developerComment })
            .containsExactly("samesame/Fountain/pressure", "samesame/Calligraphy/fixed").inOrder()
        assertThat(store.readPage("n1", "p2").elements).isEmpty()
        // File on disk is v2 now (version u16 at offset 4).
        val bytes = File(root, "w/n1/ink/p1.ink").readBytes()
        assertThat(((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)).isEqualTo(2)
    }

    @Test
    fun interruptedSaveLeavesConsistentBundle() = runBlocking<Unit> {
        val store = NoteDocumentStore(root, File(root, "w"))
        val doc = store.create("n1", PageMode.Pages)
        val pageId = doc.pages[0].id
        val old = PageContent(pageId, listOf(InkElement(ElementId(1), stroke(PenType.Pen, true, 0f))), 1)
        store.commit("n1", NoteSnapshot(doc, listOf(old)))

        val newPage = PageContent(
            "p-new",
            listOf(ObjectElement(ElementId(9), "{}", box(0f, 0f, 1f, 1f))), 1,
        )
        val edited = doc.copy(title = "x", pages = doc.pages + org.samesame.notes.core.model.document.PageSpec("p-new"))
        store.beforeDocumentCommit = { throw IOException("crash") }
        assertThrows(IOException::class.java) { runBlocking { store.commit("n1", NoteSnapshot(edited, listOf(newPage))) } }
        store.beforeDocumentCommit = null

        val reread = store.readDocument("n1")
        assertThat(reread).isEqualTo(doc)
        reread.pages.forEach { store.readPage("n1", it.id) } // every referenced page loads
        assertThat(File(root, "w/n1/ink/p-new.ink").exists()).isTrue() // orphan only
        store.sweepOrphans("n1", reread)
        assertThat(File(root, "w/n1/ink/p-new.ink").exists()).isFalse()
        assertThat(store.readPage("n1", pageId).elements.map { it.id.value }).containsExactly(1L)
    }
}
