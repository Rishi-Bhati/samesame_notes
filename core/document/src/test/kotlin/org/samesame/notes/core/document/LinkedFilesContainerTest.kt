package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `.ssnote` container v3 (bundled linked files): write, read, remap, and v1/v2 containers still read unchanged. */
class LinkedFilesContainerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fileId = "0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60"
    private val uri = "samesame://file/$fileId?n=plan.pdf"
    private val content = ByteArray(5000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val entry = LinkedFileEntry(fileId, "plan.pdf", "application/pdf", content.size.toLong(), sha, listOf("Work", "Plans"))

    private fun store() = NoteDocumentStore(tmp.newFolder(), tmp.newFolder())

    private suspend fun linkedNote(store: NoteDocumentStore): NoteDocument {
        val created = store.create("n1", PageMode.Pages, "Linked")
        val doc = created.copy(
            body = RichText("see plan.pdf ￼", listOf(TextRun(4, CharStyle()), TextRun(8, CharStyle(link = uri)), TextRun(1, CharStyle()), TextRun(1, CharStyle(objectId = "i")))),
            inlineObjects = listOf(ImageObject("i", "media/a.png", 10f, 10f, libraryFileId = fileId)),
        )
        store.commit("n1", NoteSnapshot(doc))
        return doc
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    @Test
    fun exportWithFilesWritesAV3ContainerWithTheFilesSection() = runBlocking<Unit> {
        val s = store()
        linkedNote(s)
        val out = ByteArrayOutputStream()
        // two ids sharing one content produce one blob entry
        val other = entry.copy(id = "11111111-2222-4333-8444-555555555555", name = "copy.pdf")
        s.exportWithFiles("n1", out, listOf(LinkedFileSource(entry) { content.inputStream() }, LinkedFileSource(other) { content.inputStream() }))
        val parts = entries(out.toByteArray())
        assertThat(parts.keys.first()).isEqualTo("manifest.json")
        assertThat(String(parts.getValue("manifest.json"))).contains("\"containerVersion\":3")
        assertThat(parts.getValue("files/$sha")).isEqualTo(content)
        assertThat(parts.keys.count { it.startsWith("files/") && it != "files/files.json" }).isEqualTo(1)
        val index = String(parts.getValue("files/files.json"))
        assertThat(index).contains(fileId)
        assertThat(index).contains("\"Plans\"")
        assertThat(parts).containsKey("document.json")
    }

    @Test
    fun noFilesMeansTheUnchangedV2Container() = runBlocking<Unit> {
        val s = store()
        linkedNote(s)
        val a = ByteArrayOutputStream().also { s.exportWithFiles("n1", it, emptyList()) }.toByteArray()
        val b = ByteArrayOutputStream().also { s.export("n1", it) }.toByteArray()
        assertThat(a).isEqualTo(b)
        assertThat(String(entries(a).getValue("manifest.json"))).contains("\"containerVersion\":2")
    }

    @Test
    fun v3RoundTripRestoresFilesThroughTheRestorerAndKeepsTheDocument() = runBlocking<Unit> {
        val src = store()
        val doc = linkedNote(src)
        val out = ByteArrayOutputStream()
        src.exportWithFiles("n1", out, listOf(LinkedFileSource(entry) { content.inputStream() }))

        val dst = store()
        var seenTitle = ""
        var seen = emptyList<LinkedFileEntry>()
        var restoredBytes = ByteArray(0)
        val imported = dst.importContainer(ByteArrayInputStream(out.toByteArray())) { title, files, open ->
            seenTitle = title; seen = files
            restoredBytes = open(files.single()).readBytes()
            emptyMap()
        }
        assertThat(seenTitle).isEqualTo("Linked")
        assertThat(seen).containsExactly(entry)
        assertThat(restoredBytes).isEqualTo(content)
        assertThat(imported.bundledFiles).isEqualTo(1)
        assertThat(imported.document).isEqualTo(doc.copy(id = imported.document.id))
        // the stored copy is a normal v2 note without the files section
        assertThat(dst.readDocument(imported.document.id).body).isEqualTo(doc.body)
    }

    @Test
    fun remapRewritesLinkRunsRawTextAndLibraryIds() = runBlocking<Unit> {
        val src = store()
        linkedNote(src)
        val out = ByteArrayOutputStream()
        src.exportWithFiles("n1", out, listOf(LinkedFileSource(entry) { content.inputStream() }))
        val newId = "99999999-8888-4777-8666-555555555555"
        val dst = store()
        val imported = dst.importContainer(ByteArrayInputStream(out.toByteArray())) { _, _, _ -> mapOf(fileId to newId) }
        val d = imported.document
        assertThat(d.body.runs.map { it.style.link }).contains("samesame://file/$newId?n=plan.pdf")
        assertThat((d.inlineObjects.single() as ImageObject).libraryFileId).isEqualTo(newId)
        assertThat(d.body.text).isEqualTo("see plan.pdf ￼")
        // persisted too
        assertThat(dst.readDocument(d.id)).isEqualTo(d)
    }

    @Test
    fun aFailingRestorerStillImportsTheNote() = runBlocking<Unit> {
        val src = store()
        val doc = linkedNote(src)
        val out = ByteArrayOutputStream()
        src.exportWithFiles("n1", out, listOf(LinkedFileSource(entry) { content.inputStream() }))
        val imported = store().importContainer(ByteArrayInputStream(out.toByteArray())) { _, _, _ -> error("boom") }
        assertThat(imported.document).isEqualTo(doc.copy(id = imported.document.id))
        assertThat(imported.bundledFiles).isEqualTo(0)
    }

    @Test
    fun v3WithoutTheFilesSectionIsValid() = runBlocking<Unit> {
        val src = store()
        val doc = linkedNote(src)
        val v2 = ByteArrayOutputStream().also { src.export("n1", it) }.toByteArray()
        val v3 = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                for ((name, data) in entries(v2)) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(if (name == "manifest.json") String(data).replace("\"containerVersion\":2", "\"containerVersion\":3").toByteArray() else data)
                    z.closeEntry()
                }
            }
        }.toByteArray()
        var called = false
        val imported = store().importContainer(ByteArrayInputStream(v3)) { _, _, _ -> called = true; emptyMap() }
        assertThat(called).isFalse()
        assertThat(imported.document).isEqualTo(doc.copy(id = imported.document.id))
    }

    @Test
    fun aBlobThatIsMissingFromTheZipIsNotOffered() = runBlocking<Unit> {
        val src = store()
        linkedNote(src)
        val out = ByteArrayOutputStream()
        src.exportWithFiles("n1", out, listOf(LinkedFileSource(entry) { content.inputStream() }))
        val stripped = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                for ((name, data) in entries(out.toByteArray())) {
                    if (name == "files/$sha") continue
                    z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
                }
            }
        }.toByteArray()
        var called = false
        store().importContainer(ByteArrayInputStream(stripped)) { _, _, _ -> called = true; emptyMap() }
        assertThat(called).isFalse()
    }

    // ---- old containers (owner priority: never lose data) ----

    private val v2Doc = """{"formatVersion":7,"id":"old","title":"Old note","body":{"text":"hello","runs":[{"length":5,"style":{}}]},"pages":[{"id":"p1"}]}"""

    private fun oldContainer(manifest: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            put("manifest.json", manifest.toByteArray())
            put("document.json", v2Doc.toByteArray())
            put("media/abc.png", ByteArray(40) { it.toByte() })
        }
    }.toByteArray()

    @Test
    fun v1AndV2ContainersImportExactlyAsBefore() = runBlocking<Unit> {
        val manifests = listOf(
            """{"format":"samesame-note","formatVersion":7,"noteId":"old"}""",
            """{"format":"samesame-note","formatVersion":7,"containerVersion":2,"noteId":"old"}""",
        )
        for (m in manifests) {
            val plain = store().importWithLegacyViewState(ByteArrayInputStream(oldContainer(m)))
            var called = false
            val s = store()
            val viaRestorer = s.importContainer(ByteArrayInputStream(oldContainer(m))) { _, _, _ -> called = true; emptyMap() }
            assertThat(called).isFalse()
            assertThat(viaRestorer.bundledFiles).isEqualTo(0)
            assertThat(viaRestorer.document.copy(id = "x")).isEqualTo(plain.first.copy(id = "x"))
            assertThat(viaRestorer.document.title).isEqualTo("Old note")
            assertThat(viaRestorer.document.body.text).isEqualTo("hello")
            assertThat(s.readMedia(viaRestorer.document.id, "media/abc.png")).isEqualTo(ByteArray(40) { it.toByte() })
        }
    }

    @Test
    fun aNewerContainerVersionIsStillRefused() = runBlocking<Unit> {
        val c = oldContainer("""{"format":"samesame-note","formatVersion":7,"containerVersion":4,"noteId":"old"}""")
        val r = runCatching { store().importContainer(ByteArrayInputStream(c), null) }
        assertThat(r.isFailure).isTrue()
    }
}
