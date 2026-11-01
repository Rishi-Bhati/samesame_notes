package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.TamperedDataException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LockedContainerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var notes: File
    private lateinit var working: File
    private val master = LockCrypto.newKey()
    private val secretTitle = "TOPSECRET-TITLE-9f3a"
    private val secretMedia = "TOPSECRET-MEDIA-BYTES-77".encodeToByteArray()

    @Before fun setUp() {
        notes = tmp.newFolder("notes")
        working = tmp.newFolder("working")
    }

    private fun store() = NoteDocumentStore(notes, working)
    private fun container(id: String) = File(notes, "$id.ssnote")

    private fun newNote(s: NoteDocumentStore, id: String = "n1") = runBlocking {
        val doc = s.create(id, PageMode.Pages)
        s.writeDocument(doc.copy(title = secretTitle))
        s.writeMedia(id, secretMedia, "bin")
        s.flush(id)
    }

    private fun manifest(id: String): String =
        ZipFile(container(id)).use { it.getInputStream(it.getEntry("manifest.json")).readBytes().decodeToString() }

    @Test fun lockedContainerHoldsNoPlaintext() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)

        val bytes = container("n1").readBytes()
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain(secretTitle)
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("TOPSECRET-MEDIA")
        ZipFile(container("n1")).use { zf ->
            assertThat(zf.entries().toList().map { it.name }).containsExactly("manifest.json", "payload.enc")
        }
        assertThat(manifest("n1")).contains("\"locked\":true")
        assertThat(manifest("n1")).contains("\"containerVersion\":2")
        assertThat(s.isLocked("n1")).isTrue()
        assertThat(File(working, "n1").exists()).isFalse()
    }

    @Test fun lockedNoteNeedsOpenBeforeAccess() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        assertThrows(NoteLockedException::class.java) { runBlocking { s.readDocument("n1") } }
        assertThrows(NoteLockedException::class.java) { runBlocking { s.readMedia("n1", "media/x") } }

        s.openLocked("n1", master)
        assertThat(s.readDocument("n1").title).isEqualTo(secretTitle)
        val mediaPath = s.writeMedia("n1", secretMedia, "bin")
        assertThat(s.readMedia("n1", mediaPath)).isEqualTo(secretMedia)
    }

    @Test fun wrongMasterKeyIsRejected() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        assertThrows(TamperedDataException::class.java) { runBlocking { s.openLocked("n1", LockCrypto.newKey()) } }
        assertThat(File(working, "n1").exists()).isFalse()
        assertThrows(NoteLockedException::class.java) { runBlocking { s.readDocument("n1") } }
    }

    @Test fun tamperedPayloadIsRejected() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        // Flip one byte in the sealed payload and rewrite the container.
        val entries = ZipFile(container("n1")).use { zf -> zf.entries().toList().associate { it.name to zf.getInputStream(it).readBytes() } }
        val payload = entries.getValue("payload.enc").also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(entries.getValue("manifest.json")); z.closeEntry()
            z.putNextEntry(ZipEntry("payload.enc")); z.write(payload); z.closeEntry()
        }
        container("n1").writeBytes(out.toByteArray())
        assertThrows(TamperedDataException::class.java) { runBlocking { s.openLocked("n1", master) } }
    }

    @Test fun editsAreEncryptedOnEveryCommitAndSurviveClose() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        s.commit("n1", NoteSnapshot(doc.copy(title = "edited-title-123")))
        // Packed immediately: the container already holds the edit, still encrypted.
        assertThat(String(container("n1").readBytes(), Charsets.ISO_8859_1)).doesNotContain("edited-title-123")
        s.flush("n1", close = true)
        assertThat(File(working, "n1").exists()).isFalse()
        s.openLocked("n1", master)
        assertThat(s.readDocument("n1").title).isEqualTo("edited-title-123")
    }

    @Test fun processRestartWipesDecryptedWorkingCopy() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        s.openLocked("n1", master)
        assertThat(File(working, "n1/document.json").exists()).isTrue()
        // Simulated process kill: a new store instance, same directories.
        val restarted = store()
        assertThat(File(working, "n1").exists()).isFalse()
        assertThrows(NoteLockedException::class.java) { runBlocking { restarted.readDocument("n1") } }
        restarted.openLocked("n1", master)
        assertThat(restarted.readDocument("n1").title).isEqualTo(secretTitle)
    }

    @Test fun unlockRestoresPlainContainer() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        s.unlockNote("n1", master)
        assertThat(s.isLocked("n1")).isFalse()
        assertThat(manifest("n1")).doesNotContain("locked")
        assertThat(store().readDocument("n1").title).isEqualTo(secretTitle)
    }

    @Test fun lockedNotesCannotBeExportedDuplicatedOrImported() = runBlocking<Unit> {
        val s = store()
        newNote(s)
        s.lockNote("n1", master)
        assertThrows(NoteLockedException::class.java) { runBlocking { s.export("n1", ByteArrayOutputStream()) } }
        assertThrows(NoteLockedException::class.java) { runBlocking { s.duplicate("n1", "n2") } }
        s.openLocked("n1", master)
        assertThrows(NoteLockedException::class.java) { runBlocking { s.export("n1", ByteArrayOutputStream()) } }
        assertThrows(NoteLockedException::class.java) { runBlocking { s.duplicate("n1", "n2") } }
        val bytes = container("n1").readBytes()
        assertThrows(IOException::class.java) { runBlocking { s.import(bytes.inputStream()) } }
    }

    @Test fun v1ContainerWithoutContainerVersionStillReads() = runBlocking<Unit> {
        // Build a container the way the previous version did: manifest without containerVersion.
        val s = store()
        val doc = s.create("old", PageMode.Pages, title = "legacy-title")
        val docJson = doc.toJson().encodeToByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"samesame-note","formatVersion":${org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION},"noteId":"old"}""".encodeToByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("document.json")); z.write(docJson); z.closeEntry()
        }
        s.delete("old")
        container("old").writeBytes(out.toByteArray())
        val fresh = NoteDocumentStore(notes, File(tmp.root, "working2"))
        assertThat(fresh.isLocked("old")).isFalse()
        assertThat(fresh.readDocument("old").title).isEqualTo("legacy-title")
        fresh.flush("old") // repack as v2
        assertThat(manifest("old")).contains("\"containerVersion\":2")
    }

    @Test fun newerContainerVersionIsRefused() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"samesame-note","formatVersion":2,"containerVersion":99,"noteId":"x"}""".encodeToByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("document.json")); z.write("{}".encodeToByteArray()); z.closeEntry()
        }
        assertThrows(IOException::class.java) { runBlocking { store().import(out.toByteArray().inputStream()) } }
    }
}
