package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.security.LockCrypto
import java.io.File
import kotlin.random.Random

/**
 * Lock / unlock timings of a big synthetic note (40 pages of ink, 8 x 2 MB images, about 18 MB), printed to the test
 * output (`build/test-results`): the JVM numbers of the before/after table in `docs/checkpoints/rel011-perf.md`.
 * Asserts only correctness (timings on a build machine are not stable enough to gate on).
 */
class LockPerformanceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var notes: File
    private lateinit var working: File
    private val master = LockCrypto.newKey()
    private val random = Random(7)
    private val media = ArrayList<Pair<String, ByteArray>>()

    @Before fun setUp() {
        notes = tmp.newFolder("notes")
        working = tmp.newFolder("working")
    }

    private fun store() = NoteDocumentStore(notes, working)

    /** A note the editor closed: only the packed container exists (the usual state when locking from the list). */
    private fun bigNote(s: NoteDocumentStore, id: String): Unit = runBlocking {
        val doc = s.create(id, PageMode.Pages)
        val pages = (0 until PAGES).map { PageSpec(id = "page-$it") }
        s.writeDocument(doc.copy(title = "Big note", pages = pages))
        val ink = File(File(working, id), "ink").apply { mkdirs() }
        pages.forEach { File(ink, "${it.id}.ink").writeBytes(random.nextBytes(INK_BYTES)) }
        media.clear()
        repeat(IMAGES) {
            val bytes = random.nextBytes(IMAGE_BYTES)
            media += s.writeMedia(id, bytes, "jpg") to bytes
        }
        s.flush(id, close = true)
    }

    private inline fun ms(block: () -> Unit): Double {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1e6
    }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    @Test fun bigNoteLockOpenEditUnlock() = runBlocking<Unit> {
        val lock = ArrayList<Double>()
        val open = ArrayList<Double>()
        val commit = ArrayList<Double>()
        val close = ArrayList<Double>()
        val unlock = ArrayList<Double>()
        repeat(RUNS) { run ->
            val s = store()
            val id = "big$run"
            bigNote(s, id)
            lock += ms { s.lockNote(id, master) }
            assertThat(s.isLocked(id)).isTrue()

            open += ms { s.openLocked(id, master) }
            val doc = s.readDocument(id)
            // Autosaves of small edits (document only) while the locked note is open in the editor: 5 in a row.
            commit += ms { repeat(5) { i -> s.commit(id, NoteSnapshot(doc.copy(title = "Edited $run" + if (i < 4) " $i" else ""))) } }
            close += ms { s.flush(id, close = true) }

            unlock += ms { s.unlockNote(id, master) }
            assertThat(s.isLocked(id)).isFalse()
            assertThat(s.readDocument(id).title).isEqualTo("Edited $run")
            val (path, bytes) = media.last()
            assertThat(s.readMedia(id, path)).isEqualTo(bytes)
            assertThat(s.readDocument(id).pages).hasSize(PAGES)
            s.flush(id, close = true)
        }
        val mb = (PAGES * INK_BYTES + IMAGES * IMAGE_BYTES) / (1024.0 * 1024.0)
        println(
            "LOCK-PERF note=%.1fMB runs=%d median ms: lock=%.1f openLocked=%.1f commitLockedx5=%.1f closeLocked=%.1f unlock=%.1f"
                .format(mb, RUNS, median(lock), median(open), median(commit), median(close), median(unlock)),
        )
    }

    private companion object {
        const val RUNS = 5
        const val PAGES = 40
        const val INK_BYTES = 60 * 1024
        const val IMAGES = 8
        const val IMAGE_BYTES = 2 * 1024 * 1024
    }
}
