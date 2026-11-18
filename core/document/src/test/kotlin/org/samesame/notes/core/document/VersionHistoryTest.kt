package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.security.LockCrypto
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Automatic version history: when versions are taken, what is pruned, restore, locked notes. */
class VersionHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var notes: File
    private lateinit var working: File
    private lateinit var versionsRoot: File
    private var now = 1_000_000_000_000L
    private val master = LockCrypto.newKey()
    private val minute = 60_000L

    @Before fun setUp() {
        notes = tmp.newFolder("notes")
        working = tmp.newFolder("working")
        versionsRoot = tmp.newFolder("versions")
    }

    private fun versions(copier: ((File, File) -> Unit)? = null) =
        if (copier == null) NoteVersionStore(versionsRoot, { now }) else NoteVersionStore(versionsRoot, { now }, copier)

    private fun store(v: NoteVersionStore = versions()) = NoteDocumentStore(notes, working, v)
    private fun container(id: String = "n1") = File(notes, "$id.ssnote")

    /** Writes [text] as the note body and packs without closing (an autosave + flush). */
    private suspend fun edit(s: NoteDocumentStore, text: String, id: String = "n1", title: String = "T") {
        val doc = if (s.exists(id)) s.readDocument(id) else s.create(id, PageMode.Pages)
        s.commit(id, NoteSnapshot(doc.copy(title = title, body = RichText.of(text))))
        s.flush(id)
    }

    private fun textOf(s: NoteDocumentStore, id: String = "n1") = runBlocking { s.readDocument(id).body.plainText() }

    @Test fun firstPackTakesAVersionThenTheThrottleHolds() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "one")
        assertThat(v.list("n1")).hasSize(1)
        now += minute
        edit(s, "two")
        assertThat(v.list("n1")).hasSize(1)
        now += 5 * minute
        edit(s, "three")
        val list = v.list("n1")
        assertThat(list).hasSize(2)
        assertThat(list.first().preview).isEqualTo("three")
        assertThat(list.first().title).isEqualTo("T")
        assertThat(list.first().sizeBytes).isEqualTo(container().length())
    }

    @Test fun closingTakesAVersionOfAnythingNewerAndSkipsIdenticalCopies() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "one")
        now += minute
        edit(s, "two") // throttled
        assertThat(v.list("n1")).hasSize(1)
        s.flush("n1", close = true)
        assertThat(v.list("n1")).hasSize(2)
        // Opening and closing again without a change adds nothing.
        s.readDocument("n1")
        s.flush("n1", close = true)
        assertThat(v.list("n1")).hasSize(2)
    }

    @Test fun identicalCopyIsSkippedEvenWhenForced() {
        val v = versions()
        val file = tmp.newFile("c.ssnote").also { it.writeText("same bytes") }
        assertThat(v.snapshot("a", file, { VersionMeta() }, force = true)).isNotNull()
        now += 10 * minute
        assertThat(v.snapshot("a", file, { VersionMeta() }, force = true)).isNull()
        file.writeText("other bytes")
        assertThat(v.snapshot("a", file, { VersionMeta() }, force = true)).isNotNull()
        assertThat(v.list("a")).hasSize(2)
    }

    @Test fun countLimitDropsTheOldest() {
        val v = versions()
        val file = tmp.newFile("c.ssnote")
        repeat(Limits.MAX_VERSIONS_PER_NOTE + 10) { i ->
            file.writeText("content $i")
            now += minute
            v.snapshot("a", file, { VersionMeta(title = "v$i") }, force = true)
        }
        val list = v.list("a")
        assertThat(list).hasSize(Limits.MAX_VERSIONS_PER_NOTE)
        assertThat(list.first().title).isEqualTo("v${Limits.MAX_VERSIONS_PER_NOTE + 9}")
        assertThat(list.last().title).isEqualTo("v10")
    }

    @Test fun oldVersionsGoButTheNewestFiveStayWhateverTheirAge() {
        val v = versions()
        val file = tmp.newFile("c.ssnote")
        repeat(8) { i ->
            file.writeText("content $i")
            now += minute
            v.snapshot("a", file, { VersionMeta(title = "v$i") }, force = true)
        }
        assertThat(v.list("a")).hasSize(8)
        now += TimeUnit.DAYS.toMillis(Limits.VERSION_RETENTION_DAYS + 5L)
        v.prune("a")
        assertThat(v.list("a").map { it.title }).containsExactly("v7", "v6", "v5", "v4", "v3").inOrder()
        // One fresh version: it and the four before it are the newest five; the rest are old.
        file.writeText("fresh")
        v.snapshot("a", file, { VersionMeta(title = "fresh") }, force = true)
        assertThat(v.list("a").map { it.title }).containsExactly("fresh", "v7", "v6", "v5", "v4").inOrder()
    }

    @Test fun totalSizeDropsTheOldestFirstButKeepsEachNotesNewest() {
        val v = versions()
        val a = tmp.newFile("a.ssnote")
        val b = tmp.newFile("b.ssnote")
        fun take(id: String, f: File, i: Int) {
            f.writeBytes(ByteArray(1000) { i.toByte() })
            now += minute
            v.snapshot(id, f, { VersionMeta(title = "$id$i") }, force = true)
        }
        take("a", a, 1); take("b", b, 2); take("a", a, 3); take("b", b, 4); take("a", a, 5)
        assertThat(v.list("a")).hasSize(3)
        v.pruneTotal(maxBytes = 3500)
        assertThat((v.list("a") + v.list("b")).sumOf { it.sizeBytes }).isAtMost(3500)
        assertThat(v.list("a").map { it.title }).contains("a5")
        assertThat(v.list("b").map { it.title }).contains("b4")
        assertThat(v.list("a").map { it.title }).doesNotContain("a1")
        v.pruneTotal(maxBytes = 1)
        assertThat(v.list("a")).hasSize(1)
        assertThat(v.list("b")).hasSize(1)
    }

    @Test fun deletingTheNoteForeverRemovesItsVersions() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "one")
        edit(s, "keep", id = "n2")
        assertThat(v.list("n1")).isNotEmpty()
        s.delete("n1")
        assertThat(v.list("n1")).isEmpty()
        assertThat(File(versionsRoot, "n1").exists()).isFalse()
        assertThat(v.list("n2")).isNotEmpty()
    }

    @Test fun aFailingSnapshotDoesNotFailThePack() = runBlocking<Unit> {
        val v = versions { _, _ -> throw IOException("disk full") }
        val s = store(v)
        edit(s, "saved anyway")
        assertThat(v.list("n1")).isEmpty()
        assertThat(File(versionsRoot, "n1").listFiles().orEmpty().toList()).isEmpty()
        assertThat(textOf(store(versions()))).isEqualTo("saved anyway")
        s.flush("n1", close = true) // the close snapshot fails quietly too
        assertThat(container().exists()).isTrue()
    }

    @Test fun restoreReplacesTheContentAndKeepsTheCurrentStateAsAVersion() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "first draft")
        now += 10 * minute
        edit(s, "second draft")
        now += 10 * minute
        edit(s, "final draft")
        val oldest = v.list("n1").last()
        assertThat(oldest.preview).isEqualTo("first draft")
        val countBefore = v.list("n1").size

        s.restoreVersion("n1", oldest.id)
        assertThat(textOf(s)).isEqualTo("first draft")
        val after = v.list("n1")
        assertThat(after).hasSize(countBefore) // "final draft" was already the newest version
        assertThat(after.first().preview).isEqualTo("final draft")

        // An unsaved change in the working copy is saved as a version before it is replaced.
        s.commit("n1", NoteSnapshot(s.readDocument("n1").copy(body = RichText.of("unsaved edit"))))
        s.restoreVersion("n1", after.first().id)
        assertThat(textOf(s)).isEqualTo("final draft")
        assertThat(v.list("n1").map { it.preview }).contains("unsaved edit")
        // Undo the restore.
        val undo = v.list("n1").first { it.preview == "unsaved edit" }
        s.restoreVersion("n1", undo.id)
        assertThat(textOf(s)).isEqualTo("unsaved edit")
        s.flush("n1", close = true)
        assertThat(textOf(store(versions()))).isEqualTo("unsaved edit")
    }

    @Test fun restoreWhenWorkingCopyIsClosedPreservesMetadataInUndoVersion() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "v1 body", title = "V1 Title")
        now += 10 * minute
        edit(s, "v2 body", title = "V2 Title")
        val v1 = v.list("n1").last()

        // Close the note so working copy is deleted
        s.flush("n1", close = true)
        assertThat(File(working, "n1").exists()).isFalse()

        now += 10 * minute
        // Restore v1 while note is closed (working copy absent)
        s.restoreVersion("n1", v1.id)

        // The undo version taken from the container before restore must have title and preview preserved
        val versionsAfter = v.list("n1")
        val undoVersion = versionsAfter.first()
        assertThat(undoVersion.title).isEqualTo("V2 Title")
        assertThat(undoVersion.preview).isEqualTo("v2 body")
        assertThat(undoVersion.pages).isGreaterThan(0)
    }

    @Test fun restoreOfAMissingVersionFailsAndChangesNothing() = runBlocking<Unit> {
        val s = store()
        edit(s, "keep me")
        assertThrows(java.io.FileNotFoundException::class.java) { runBlocking { s.restoreVersion("n1", 42L) } }
        assertThat(textOf(s)).isEqualTo("keep me")
    }

    @Test fun aFailedUndoPointStopsTheRestore() = runBlocking<Unit> {
        var fail = false
        val v = versions { from, to -> if (fail) throw IOException("no space") else java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        val s = store(v)
        edit(s, "first")
        now += 10 * minute
        edit(s, "second")
        val first = v.list("n1").last()
        s.commit("n1", NoteSnapshot(s.readDocument("n1").copy(body = RichText.of("unsaved"))))
        fail = true
        assertThrows(IOException::class.java) { runBlocking { s.restoreVersion("n1", first.id) } }
        assertThat(textOf(s)).isEqualTo("unsaved")
    }

    @Test fun lockedVersionsStayEncryptedAndNeedTheKey() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "plain secret text", title = "SECRET-TITLE-77")
        s.lockNote("n1", master)
        assertThat(v.list("n1")).isEmpty() // plain copies from before the lock are gone

        s.openLocked("n1", master)
        now += 10 * minute
        s.commit("n1", NoteSnapshot(s.readDocument("n1").copy(title = "SECRET-TITLE-77", body = RichText.of("locked body A"))))
        s.flush("n1")
        now += 10 * minute
        s.commit("n1", NoteSnapshot(s.readDocument("n1").copy(title = "SECRET-TITLE-77", body = RichText.of("locked body B"))))
        s.flush("n1", close = true)
        val list = v.list("n1")
        assertThat(list).hasSize(2)
        assertThat(list.all { it.locked && it.title.isEmpty() && it.preview.isEmpty() }).isTrue()
        val bytes = String(v.file("n1", list.last().id)!!.readBytes(), Charsets.ISO_8859_1)
        assertThat(bytes).doesNotContain("SECRET-TITLE-77")
        assertThat(bytes).doesNotContain("locked body")

        // Without the key (a fresh process) neither reading nor restoring works.
        val fresh = store(versions())
        assertThrows(NoteLockedException::class.java) { runBlocking { fresh.readVersionText("n1", list.last().id) } }
        assertThrows(NoteLockedException::class.java) { runBlocking { fresh.restoreVersion("n1", list.last().id) } }

        fresh.openLocked("n1", master)
        assertThat(fresh.readVersionText("n1", list.last().id)!!.body).isEqualTo("locked body A")
        fresh.restoreVersion("n1", list.last().id)
        assertThat(fresh.readDocument("n1").body.plainText()).isEqualTo("locked body A")
        fresh.flush("n1", close = true)
        assertThat(fresh.isLocked("n1")).isTrue()
        assertThat(String(container().readBytes(), Charsets.ISO_8859_1)).doesNotContain("locked body")
    }

    @Test fun unlockingDropsTheVersionsToo() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "x")
        s.lockNote("n1", master)
        s.openLocked("n1", master)
        s.commit("n1", NoteSnapshot(s.readDocument("n1").copy(body = RichText.of("y"))))
        s.flush("n1", close = true)
        assertThat(v.list("n1")).isNotEmpty()
        s.unlockNote("n1", master)
        assertThat(v.list("n1")).isEmpty()
    }

    @Test fun readVersionTextReturnsTitleAndBody() = runBlocking<Unit> {
        val v = versions()
        val s = store(v)
        edit(s, "hello version", title = "My note")
        val text = s.readVersionText("n1", v.list("n1").first().id)!!
        assertThat(text.title).isEqualTo("My note")
        assertThat(text.body).isEqualTo("hello version")
    }
}
