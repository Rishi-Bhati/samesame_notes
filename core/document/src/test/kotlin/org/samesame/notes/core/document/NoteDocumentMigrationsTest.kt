package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.document.legacy.NoteDocumentV1
import org.samesame.notes.core.document.legacy.PageSpecV1
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
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.ink.page.newObjectElement
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.TemplateRef
import java.io.File
import java.io.IOException

class NoteDocumentMigrationsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v1 = NoteDocumentV1(
        id = "n1",
        pageMode = PageMode.Pages,
        pageRatio = PageRatio.Long,
        body = "one\ntwo",
        pages = listOf(PageSpecV1("p1", PageTemplate.GridMedium, 0xFF112233, true), PageSpecV1("p2")),
    )

    private fun store(root: File) = NoteDocumentStore(root, File(tmp.root, "working"))
    private fun working(id: String) = File(tmp.root, "working/$id")

    private fun writeV1(root: File) {
        File(root, "n1").mkdirs()
        File(root, "n1/document.json").writeText(NoteDocumentV1.toJson(v1))
    }

    @Test
    fun v1ToV2MapsFields() {
        val v2 = NoteDocumentMigrations.v1ToV2(v1, "My title")
        assertThat(v2.title).isEqualTo("My title")
        assertThat(v2.layout.mode).isEqualTo(PageMode.Pages)
        assertThat(v2.layout.ratio).isEqualTo(PageRatio.Long)
        assertThat(v2.body.text).isEqualTo("one\ntwo")
        assertThat(v2.body.paragraphs).hasSize(2)
        assertThat(v2.body.runs.sumOf { it.length }).isEqualTo(7)
        assertThat(v2.pages[0].template).isEqualTo(TemplateRef.Builtin(PageTemplate.GridMedium))
        assertThat(v2.pages[0].backgroundColor).isEqualTo(0xFF112233)
        assertThat(v2.pages[0].isBookmarked).isTrue()
        assertThat(v2.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
    }

    @Test
    fun storeMigratesV1BundleOnReadAndRewritesDocument() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        writeV1(root)
        val store = store(root)
        val doc = store.readDocument("n1", titleFromDb = "T")
        assertThat(doc.title).isEqualTo("T")
        val onDisk = NoteDocument.fromJson(File(working("n1"), "document.json").readText())
        assertThat(File(root, "n1.ssnote").exists()).isTrue()   // packed after migration
        assertThat(File(root, "n1").exists()).isFalse()          // legacy directory gone
        assertThat(onDisk).isEqualTo(doc)
        // Second read no longer needs the DB title.
        assertThat(store.readDocument("n1", titleFromDb = "other").title).isEqualTo("T")
    }

    @Test
    fun newerVersionIsRejected() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1").mkdirs()
        File(root, "n1/document.json").writeText("""{"formatVersion":7,"id":"n1","pages":[]}""")
        assertThrows(IOException::class.java) { runBlocking { store(root).readDocument("n1") } }
        Unit
    }

    @Test
    fun v2DocumentWithFloatingImagesLoadsAndSavesAsCurrent() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1/ink").mkdirs()
        // A v2 document: an inline image, no text boxes, no voice, no rotation/wrap fields.
        File(root, "n1/document.json").writeText(
            """{"formatVersion":2,"id":"n1","title":"old",""" +
                """"inlineObjects":[{"type":"image","id":"img1","media":"media/a.png","widthPu":100.0,"heightPu":50.0}],""" +
                """"pages":[{"id":"p1"}]}""",
        )
        // A v2 page with a floating image (the object JSON has no rotation/wrap keys).
        val floatingV2 = """{"obj":{"type":"image","id":"img2","media":"media/b.png","widthPu":10.0,"heightPu":20.0},""" +
            """"rect":{"left":1.0,"top":2.0,"right":11.0,"bottom":22.0}}"""
        File(root, "n1/ink/p1.ink").outputStream().use {
            PageLayerCodec.encode(
                PageContent("p1", listOf(ObjectElement(ElementId(7), floatingV2, box(1f, 2f, 11f, 22f))), 0), it,
            )
        }
        val store = store(root)
        val doc = store.readDocument("n1")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        // v4's recordings list starts empty on migration.
        assertThat(doc.recordings).isEmpty()
        assertThat(doc.inlineObjects).containsExactly(ImageObject("img1", "media/a.png", 100f, 50f))
        val floating = store.readPage("n1", "p1").elements.single() as ObjectElement
        assertThat(FloatingObject.fromJson(floating.json)).isEqualTo(
            FloatingObject(ImageObject("img2", "media/b.png", 10f, 20f), RectPu(1f, 2f, 11f, 22f)),
        )

        store.commit("n1", NoteSnapshot(doc.copy(title = "new")))
        val onDisk = NoteDocument.fromJson(File(working("n1"), "document.json").readText())
        assertThat(onDisk.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(onDisk.title).isEqualTo("new")
        assertThat(onDisk.inlineObjects).isEqualTo(doc.inlineObjects)

        store.flush("n1", close = true)
        val packed = entries(File(root, "n1.ssnote"))["document.json"]!!.decodeToString()
        assertThat(NoteDocument.fromJson(packed).formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(store.readDocument("n1").title).isEqualTo("new")
    }

    @Test
    fun v3DocumentLoadsAsCurrentWithEmptyRecordings() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1").mkdirs()
        // A v3 document: the same JSON without the v4 recordings key.
        File(root, "n1/document.json").writeText(
            """{"formatVersion":3,"id":"n1","title":"old","pages":[{"id":"p1"}]}""",
        )
        val doc = store(root).readDocument("n1")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(doc.recordings).isEmpty()
    }

    @Test
    fun v4DocumentLoadsAsCurrentAndKeepsEverything() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1").mkdirs()
        File(root, "n1/document.json").writeText(
            """{"formatVersion":4,"id":"n1","title":"old","recordings":[{"id":"r1","media":"media/r.m4a","elapsedMs":1500}],""" +
                """"pages":[{"id":"p1"}]}""",
        )
        val doc = store(root).readDocument("n1")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(doc.title).isEqualTo("old")
        assertThat(doc.recordings.map { it.id }).containsExactly("r1")
    }

    @Test
    fun v5DocumentLoadsAsCurrentWithEveryPageOnTheGlobalRatio() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1").mkdirs()
        // A v5 imported-PDF note: landscape global ratio, pdf refs, no per-page sizes.
        File(root, "n1/document.json").writeText(
            """{"formatVersion":5,"id":"n1","title":"pdf","layout":{"mode":"Pages","ratio":"A4Landscape"},""" +
                """"pages":[{"id":"p1","template":{"type":"image","media":"media/a.png"},"pdf":{"media":"media/s.pdf","pageIndex":0}},""" +
                """{"id":"p2","template":{"type":"image","media":"media/b.png"},"pdf":{"media":"media/s.pdf","pageIndex":1}}]}""",
        )
        val doc = store(root).readDocument("n1")
        assertThat(doc.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(doc.pages.map { it.size }).containsExactly(null, null)
        assertThat(doc.pages.map { it.heightOverWidth(doc.layout) })
            .containsExactly(org.samesame.notes.core.model.PageRatio.A4Landscape.heightOverWidth, org.samesame.notes.core.model.PageRatio.A4Landscape.heightOverWidth)
        assertThat(doc.pages.map { it.pdf?.pageIndex }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun v5ToV6IsPureAndDropsStraySizes() {
        val v5 = NoteDocument(
            formatVersion = 5, id = "n",
            pages = listOf(PageSpec("a"), PageSpec("b", size = org.samesame.notes.core.model.document.PageSize(1f, 2f))),
        )
        val v6 = NoteDocumentMigrations.v5ToV6(v5)
        assertThat(v6.formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
        assertThat(v6.pages.map { it.size }).containsExactly(null, null)
        assertThat(v6.pages.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun v6DocumentKeepsPerPageSizes() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1").mkdirs()
        File(root, "n1/document.json").writeText(
            """{"formatVersion":6,"id":"n1","pages":[{"id":"p1","size":{"widthPt":842.0,"heightPt":595.0}},{"id":"p2"}]}""",
        )
        val doc = store(root).readDocument("n1")
        assertThat(doc.pages[0].size).isEqualTo(org.samesame.notes.core.model.document.PageSize(842f, 595f))
        assertThat(doc.pages[1].size).isNull()
    }

    @Test
    fun v4PageAndV5PaintingObjectRoundTrip() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        File(root, "n1/ink").mkdirs()
        File(root, "n1/document.json").writeText("""{"formatVersion":4,"id":"n1","pages":[{"id":"p1"}]}""")
        val painting = FloatingObject(
            PaintingObject("d1", "media/t.png", "media/s.drw", RectPu(10f, 20f, 110f, 220f)),
            RectPu(5f, 6f, 105f, 206f),
        )
        val store = store(root)
        val doc = store.readDocument("n1")
        store.writePage("n1", PageContent("p1", listOf(newObjectElement(ElementId(3), painting)), 0))
        store.commit("n1", NoteSnapshot(doc))
        val back = store.readPage("n1", "p1").elements.single() as ObjectElement
        assertThat(FloatingObject.fromJson(back.json)).isEqualTo(painting)
        assertThat(painting.toJson()).contains("\"type\":\"painting\"")
    }

    private fun entries(container: File): Map<String, ByteArray> = java.util.zip.ZipFile(container).use { zf ->
        zf.entries().asSequence().associate { it.name to zf.getInputStream(it).readBytes() }
    }

    @Test
    fun interruptedCommitKeepsPreviousDocumentAndOrphansAreSwept() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val created = store.create("n1", PageMode.Pages)
        val orphan = File(working("n1"), "ink/gone.ink").also { it.parentFile.mkdirs(); it.writeText("x") }
        val staleTmp = File(working("n1"), "ink/a.ink.tmp").also { it.writeText("x") }
        store.beforeDocumentCommit = { throw IOException("crash") }
        val edited = created.copy(title = "edited")
        assertThrows(IOException::class.java) { runBlocking { store.commit("n1", NoteSnapshot(edited)) } }
        store.beforeDocumentCommit = null
        assertThat(store.readDocument("n1").title).isEqualTo("")   // old doc intact
        assertThat(orphan.exists()).isTrue()                        // sweep did not run on failure
        store.sweepOrphans("n1", store.readDocument("n1"))
        assertThat(orphan.exists()).isFalse()
        assertThat(staleTmp.exists()).isFalse()
        store.commit("n1", NoteSnapshot(edited))
        assertThat(store.readDocument("n1").title).isEqualTo("edited")
    }

    @Test
    fun commitDeletesPageFilesOfRemovedPages() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val doc = store.create("n1", PageMode.Pages)
        val pageFile = File(working("n1"), "ink/${doc.pages[0].id}.ink").also { it.parentFile.mkdirs(); it.writeText("x") }
        store.commit("n1", NoteSnapshot(doc.copy(pages = listOf(doc.pages[0].copy(id = "other")))))
        assertThat(pageFile.exists()).isFalse()
    }

    @Test
    fun duplicateCopiesBundleWithNewId() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        writeV1(root)
        File(root, "n1/media").mkdirs(); File(root, "n1/media/m.bin").writeText("m")
        val copy = store.duplicate("n1", "n2", "T")
        assertThat(copy.id).isEqualTo("n2")
        assertThat(store.readDocument("n2").title).isEqualTo("T")
        assertThat(File(working("n2"), "media/m.bin").readText()).isEqualTo("m")
        assertThat(store.readDocument("n1").id).isEqualTo("n1")
    }

    @Test
    fun packUnpackRoundTripIsByteEqualAndManifestFirst() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val doc = store.create("n1", PageMode.Pages, "T")
        File(working("n1"), "ink").mkdirs()
        File(working("n1"), "ink/${doc.pages[0].id}.ink").writeBytes(byteArrayOf(1, 2, 3))
        File(working("n1"), "media").mkdirs()
        File(working("n1"), "media/h.png").writeBytes(ByteArray(1000) { it.toByte() })
        store.writeDocument(doc) // files written behind the store's back: a store write marks the copy as changed
        store.flush("n1", close = true)
        assertThat(working("n1").exists()).isFalse()
        val container = File(root, "n1.ssnote")
        val first = entries(container)
        java.util.zip.ZipFile(container).use { zf ->
            assertThat(zf.entries().nextElement().name).isEqualTo("manifest.json")
            assertThat(zf.getEntry("media/h.png").method).isEqualTo(java.util.zip.ZipEntry.STORED)
            assertThat(zf.getEntry("document.json").method).isEqualTo(java.util.zip.ZipEntry.DEFLATED)
        }
        assertThat(first["manifest.json"]!!.decodeToString()).contains("samesame-note")
        assertThat(store.readDocument("n1").title).isEqualTo("T") // unpacks
        store.flush("n1")
        val second = entries(container)
        assertThat(second.keys).isEqualTo(first.keys)
        for ((k, v) in first) assertThat(second[k]).isEqualTo(v)
    }

    @Test
    fun crashBetweenAutosaveAndPackRecoversFromWorkingDir() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val a = store(root)
        val doc = a.create("n1", PageMode.Pages)
        Thread.sleep(20)
        a.commit("n1", NoteSnapshot(doc.copy(title = "unsaved-in-container")))
        // "process death": a new store instance, working dir kept, container is old.
        val b = store(root)
        assertThat(b.readDocument("n1").title).isEqualTo("unsaved-in-container")
        assertThat(NoteDocument.fromJson(String(entries(File(root, "n1.ssnote"))["document.json"]!!)).title).isEqualTo("")
        b.flush("n1")
        assertThat(NoteDocument.fromJson(String(entries(File(root, "n1.ssnote"))["document.json"]!!)).title)
            .isEqualTo("unsaved-in-container")
    }

    @Test
    fun partialTmpIsIgnoredAndRemoved() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        store.create("n1", PageMode.Pages, "ok")
        store.flush("n1", close = true)
        val partial = File(root, "n1.ssnote.tmp").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertThat(store.readDocument("n1").title).isEqualTo("ok")
        assertThat(partial.exists()).isFalse()
    }

    @Test
    fun exportImportRoundTripAndCollidingId() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val store = store(root)
        store.create("n1", PageMode.Pages, "T")
        val bytes = java.io.ByteArrayOutputStream().also { store.export("n1", it) }.toByteArray()
        // Collides with the existing note: gets a new id.
        val imported = store.import(bytes.inputStream())
        assertThat(imported.id).isNotEqualTo("n1")
        assertThat(store.readDocument(imported.id).id).isEqualTo(imported.id)
        assertThat(store.readDocument(imported.id).title).isEqualTo("T")
        assertThat(entries(File(root, "${imported.id}.ssnote"))["manifest.json"]!!.decodeToString()).contains(imported.id)
        // Foreign root: still a fresh id (ids in imported files are never trusted).
        val other = NoteDocumentStore(tmp.newFolder("notes2"), tmp.newFolder("working2"))
        val foreign = other.import(bytes.inputStream()).id
        assertThat(foreign).isNotEqualTo("n1")
        assertThat(java.util.UUID.fromString(foreign).toString()).isEqualTo(foreign)
        // Garbage is rejected.
        assertThrows(IOException::class.java) { runBlocking { other.import("nope".byteInputStream()) } }
        assertThat(File(tmp.root, "notes2").listFiles()!!.map { it.name }).containsExactly("$foreign.ssnote")
    }
}
