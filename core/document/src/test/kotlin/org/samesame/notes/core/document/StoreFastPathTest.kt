package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.security.LockCrypto
import java.io.File
import java.util.zip.ZipFile

/** The rel011 fast paths: no-op flushes, lock/unlock without a working copy, the locked-autosave pack throttle. */
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

    @Test fun lockedAutosavesOfASlowNoteAreThrottledButNeverLost() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        var now = 1_000_000L
        s.clockMs = { now.also { now += 100 } } // every pack "takes" 100 ms: the throttle interval is 20 x 100 ms
        note(s)
        s.lockNote("n1", master, title = "v0")
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        fun sealedTitle() = runBlocking {
            NoteDocumentStore(notes, tmp.newFolder()).run { openLocked("n1", master); readDocument("n1").title }
        }

        s.commit("n1", NoteSnapshot(doc.copy(title = "v1"))) // the first commit after opening packs
        assertThat(sealedTitle()).isEqualTo("v1")
        s.commit("n1", NoteSnapshot(doc.copy(title = "v2"))) // 100 ms later: stays in the working copy
        assertThat(sealedTitle()).isEqualTo("v1")
        assertThat(s.readDocument("n1").title).isEqualTo("v2")
        now += 5_000
        s.commit("n1", NoteSnapshot(doc.copy(title = "v3"))) // the interval passed: packs again
        assertThat(sealedTitle()).isEqualTo("v3")
        s.commit("n1", NoteSnapshot(doc.copy(title = "v4")))
        s.flush("n1") // the editor's periodic / ON_STOP flush packs what was deferred
        assertThat(sealedTitle()).isEqualTo("v4")
        s.commit("n1", NoteSnapshot(doc.copy(title = "v5")))
        s.flush("n1", close = true) // and so does closing
        assertThat(sealedTitle()).isEqualTo("v5")
        assertThat(File(working, "n1").exists()).isFalse()
    }

    @Test fun smallLockedNotesStillPackOnEveryCommit() = runBlocking<Unit> {
        val s = NoteDocumentStore(notes, working)
        var now = 1_000_000L
        s.clockMs = { now } // packs take 0 ms
        note(s)
        s.lockNote("n1", master, title = "v0")
        s.openLocked("n1", master)
        val doc = s.readDocument("n1")
        s.commit("n1", NoteSnapshot(doc.copy(title = "v1")))
        s.commit("n1", NoteSnapshot(doc.copy(title = "v2")))
        val fresh = NoteDocumentStore(notes, tmp.newFolder())
        fresh.openLocked("n1", master)
        assertThat(fresh.readDocument("n1").title).isEqualTo("v2")
    }
}
