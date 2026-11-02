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
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** The rel011 fast paths: no-op flushes, lock/unlock without a working copy; locked autosaves sealed at once. */
class StoreFastPathTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var notes: File
    private lateinit var working: File
    private val master = LockCrypto.newKey()
    private val image = ByteArray(300_000) { (it * 31).toByte() }

    @Before fun setUp() {
        notes = tmp.newFolder("notes")
        working = tmp.newFolder("working")
    }

    private fun container(id: String) = File(notes, "$id.ssnote")
    private fun entries(id: String) = ZipFile(container(id)).use { zf -> zf.entries().toList().map { it.name } }

    private fun note(s: NoteDocumentStore, id: String = "n1", title: String = "Plain title"): String = runBlocking {
        val doc = s.create(id, PageMode.Pages)
        s.writeDocument(doc.copy(title = title))
        val media = s.writeMedia(id, image, "png")
        s.flush(id, close = true)
        media
    }

    @Test fun flushOfAnUnchangedNoteDoesNotRewriteTheContainer() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        s.readDocument("n1") // unpacks a working copy
        val before = container("n1").lastModified()
        container("n1").setLastModified(before - 10_000)
        File(File(working, "n1"), "document.json").setLastModified(before - 10_000)
        s.flush("n1", close = true)
        assertThat(container("n1").lastModified()).isEqualTo(before - 10_000)
        assertThat(File(working, "n1").exists()).isFalse()
    }

    @Test fun flushAfterAWritePacks() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        val doc = s.readDocument("n1")
        s.commit("n1", NoteSnapshot(doc.copy(title = "Changed")))
        s.flush("n1", close = true)
        assertThat(NoteDocumentStore(notes, tmp.newFolder("w2")).readDocument("n1").title).isEqualTo("Changed")
    }

    @Test fun lockFromContainerSealsEverythingAndCarriesTheTitle() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        val media = note(s, title = "")
        s.lockNote("n1", master, title = "Catalog title")
        assertThat(entries("n1")).containsExactly("manifest.json", "payload.enc")
        assertThat(String(container("n1").readBytes(), Charsets.ISO_8859_1)).doesNotContain("Catalog title")
        assertThat(File(working, "n1").exists()).isFalse()

        val fresh = NoteDocumentStore(notes, tmp.newFolder("w2"))
        fresh.openLocked("n1", master)
        assertThat(fresh.readDocument("n1").title).isEqualTo("Catalog title")
        assertThat(fresh.readMedia("n1", media)).isEqualTo(image)
    }

    @Test fun lockUsesNewerEditsOfTheWorkingCopy() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        val doc = s.readDocument("n1")
        s.commit("n1", NoteSnapshot(doc.copy(body = doc.body)))
        val media2 = s.writeMedia("n1", byteArrayOf(9, 9, 9), "bin")
        s.lockNote("n1", master, title = "T")
        s.openLocked("n1", master)
        assertThat(s.readMedia("n1", media2)).isEqualTo(byteArrayOf(9, 9, 9))
        assertThat(s.readDocument("n1").title).isEqualTo("T")
    }

    @Test fun unlockWithoutWorkingCopyReturnsTheDocumentAndPlainContainer() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        val media = note(s)
        s.lockNote("n1", master, title = "Secret")
        val fresh = NoteDocumentStore(notes, tmp.newFolder("w2"))
        val doc = fresh.unlockNote("n1", master)
        assertThat(doc.title).isEqualTo("Secret")
        assertThat(fresh.isLocked("n1")).isFalse()
        assertThat(entries("n1")).contains(media)
        assertThat(entries("n1")).contains("document.json")
        assertThat(fresh.readMedia("n1", media)).isEqualTo(image)
    }

    @Test fun unlockOfAnOpenNoteKeepsItsUnpackedEdits() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        s.lockNote("n1", master, title = "Before")
        s.openLocked("n1", master)
        s.commit("n1", NoteSnapshot(s.readDocument("n1").copy(title = "Edited while open")))
        assertThat(s.unlockNote("n1", master).title).isEqualTo("Edited while open")
        assertThat(NoteDocumentStore(notes, tmp.newFolder("w2")).readDocument("n1").title).isEqualTo("Edited while open")
    }

    @Test fun everyAutosaveOfALockedNoteIsSealedOnDiskImmediately() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        s.lockNote("n1", master, title = "v0")
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        for (title in listOf("v1 SECRET-EDIT", "v2 SECRET-EDIT", "v3 SECRET-EDIT")) {
            s.commit("n1", NoteSnapshot(doc.copy(title = title)))
            // A "process death" right after the save: a new store wipes the decrypted copy; the edit is in the container.
            val after = NoteDocumentStore(notes, tmp.newFolder())
            after.openLocked("n1", master)
            assertThat(after.readDocument("n1").title).isEqualTo(title)
            assertThat(entries("n1")).containsExactly("manifest.json", "payload.enc")
            assertThat(String(container("n1").readBytes(), Charsets.ISO_8859_1)).doesNotContain("SECRET-EDIT")
        }
        NoteDocumentStore(notes, working) // restart: the stale decrypted working copy is wiped
        assertThat(File(working, "n1").exists()).isFalse()
    }

    /**
     * The relock-after-background path and the normal close both end in `flush(close = true)`. When the seal fails there,
     * the plaintext working copy is the only copy of the edits and must be kept.
     */
    @Test fun aFailedSealOnCloseKeepsTheWorkingCopyAndTheEditIsRecovered() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        s.lockNote("n1", master, title = "v0")
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        s.beforePackRename = { throw IOException("No space left on device") }
        assertThrows(IOException::class.java) { runBlocking { s.commit("n1", NoteSnapshot(doc.copy(title = "SECRET-EDIT"))) } }
        assertThrows(LockedNoteSaveException::class.java) { runBlocking { s.flush("n1", close = true) } }
        // Kept, and the container still holds only the old state.
        assertThat(File(working, "n1/document.json").exists()).isTrue()
        assertThat(String(container("n1").readBytes(), Charsets.ISO_8859_1)).doesNotContain("SECRET-EDIT")

        // Space is free again: opening with the password adopts the working copy instead of decrypting over it, then the
        // edit is sealed immediately.
        s.beforePackRename = null
        s.openLocked("n1", master)
        assertThat(s.readDocument("n1").title).isEqualTo("SECRET-EDIT")
        assertThat(entries("n1")).containsExactly("manifest.json", "payload.enc")
        assertThat(String(container("n1").readBytes(), Charsets.ISO_8859_1)).doesNotContain("SECRET-EDIT")

        // And it survives a process death from here.
        val restarted = NoteDocumentStore(notes, tmp.newFolder())
        restarted.openLocked("n1", master)
        assertThat(restarted.readDocument("n1").title).isEqualTo("SECRET-EDIT")
    }

    @Test fun startupKeepsANewerLockedWorkingCopyAndWipesAStaleOne() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        s.lockNote("n1", master, title = "v0")
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        // A commit reached the working copy but its seal failed (the same state a process kill before the final pack leaves).
        s.beforePackRename = { throw IOException("No space left on device") }
        runCatching { s.commit("n1", NoteSnapshot(doc.copy(title = "SECRET-EDIT"))) }
        s.beforePackRename = null

        // Startup with the same directories: the newer copy is kept, adopted on open and sealed right away.
        val restarted = NoteDocumentStore(notes, working)
        assertThat(File(working, "n1/document.json").exists()).isTrue()
        restarted.openLocked("n1", master)
        assertThat(restarted.readDocument("n1").title).isEqualTo("SECRET-EDIT")
        assertThat(entries("n1")).containsExactly("manifest.json", "payload.enc")
        assertThat(String(container("n1").readBytes(), Charsets.ISO_8859_1)).doesNotContain("SECRET-EDIT")

        // Now in sync: the next startup wipes the decrypted working copy as before.
        NoteDocumentStore(notes, working)
        assertThat(File(working, "n1").exists()).isFalse()
    }

    @Test fun unlockWithANewerWorkingCopyKeepsTheEdits() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        note(s)
        s.lockNote("n1", master, title = "v0")
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        s.beforePackRename = { throw IOException("No space left on device") }
        runCatching { s.commit("n1", NoteSnapshot(doc.copy(title = "SECRET-EDIT"))) }
        s.beforePackRename = null
        // Unlock from a fresh process (no key in memory): the newer working copy must win over the stale container.
        val restarted = NoteDocumentStore(notes, working)
        assertThat(restarted.unlockNote("n1", master).title).isEqualTo("SECRET-EDIT")
        assertThat(restarted.isLocked("n1")).isFalse()
        assertThat(NoteDocumentStore(notes, tmp.newFolder()).readDocument("n1").title).isEqualTo("SECRET-EDIT")
    }
}
