package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.ViewState
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.doc
import org.samesame.notes.feature.composer.fakeElement
import org.samesame.notes.feature.composer.history.TextSel

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionTest {
    private val storage = FakeStorage()

    private fun TestScope.session(vararg pages: String = arrayOf("p1")) = EditorSession(
        noteId = "n1",
        initialDocument = doc(*pages),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        prefs = FakePrefs(),
        clock = { testScheduler.currentTime },
    )

    private fun EditorSession.typeText(s: String) {
        val start = body.toRichText().length
        val (st, rem, ins) = (body as InMemoryBodyText).edit(start, start, s)
        onTextEdit(st, rem, ins, TextSel(start), TextSel(start + s.length))
    }

    @Test fun `the opener chooses edit or reading mode`() = runTest {
        val editing = session()
        assertThat(editing.chrome.value.toolbar.mode)
            .isEqualTo(org.samesame.notes.feature.composer.toolbar.EditorMode.Text)
        val reading = EditorSession(
            noteId = "n2",
            initialDocument = doc("p1"),
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            pageLoader = storage::load,
            saveSink = storage::save,
            packContainer = storage::pack,
            prefs = FakePrefs(),
            clock = { testScheduler.currentTime },
            initialMode = org.samesame.notes.feature.composer.toolbar.EditorMode.Reading,
        )
        assertThat(reading.chrome.value.toolbar.mode)
            .isEqualTo(org.samesame.notes.feature.composer.toolbar.EditorMode.Reading)
        assertThat(reading.chrome.value.toolbar.modeBeforeReading)
            .isEqualTo(org.samesame.notes.feature.composer.toolbar.EditorMode.Text)
        // An opened note is in view mode (touch to edit); a FAB note is not.
        assertThat(reading.chrome.value.toolbar.viewMode).isTrue()
        assertThat(editing.chrome.value.toolbar.viewMode).isFalse()
    }

    @Test fun documentStateOnlyChangesWithStructure() = runTest {
        val s = session()
        runCurrent()
        val seen = mutableListOf<DocumentUiState>()
        val job = backgroundScope.launch { s.document.collect { seen += it } }
        runCurrent()
        val initial = seen.toList()
        assertThat(initial).hasSize(1)

        // Text, ink and page-layer edits are drawn through the page source; they must not rebuild the editor.
        s.typeText("abc")
        s.addElements("p1", listOf(fakeElement(1)))
        s.editElements("p1") { list -> list.filter { it.id.value != 1L } }
        s.setTitle("renamed")
        runCurrent()
        assertThat(seen).hasSize(initial.size)

        // Page-list changes still reach the layout.
        s.addPage(PageSpec("p2"))
        runCurrent()
        assertThat(seen).hasSize(initial.size + 1)
        assertThat(seen.last().pages.map { it.id }).containsExactly("p1", "p2").inOrder()
        job.cancel()
    }

    @Test fun autoSaveOffWritesNothingUntilSaveNow() = runTest {
        val s = session()
        runCurrent()
        s.autoSave = false
        s.typeText("draft")
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(s.hasUnsavedChanges()).isTrue()
        assertThat(storage.saved).isEmpty()
        s.saveNow()
        advanceUntilIdle()
        assertThat(s.hasUnsavedChanges()).isFalse()
        assertThat(storage.saved).isNotEmpty()
    }

    @Test fun undoRedoAcrossTextInkAndPages() = runTest {
        val s = session()
        runCurrent()
        s.typeText("hi")
        s.addElements("p1", listOf(fakeElement(1), fakeElement(2)))
        s.addPage(PageSpec("p2"))
        s.addElements("p2", listOf(fakeElement(3)))
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", "p2").inOrder()

        assertThat(s.undo()).isTrue() // ink on p2
        assertThat(s.content("p2")!!.elements).isEmpty()
        assertThat(s.undo()).isTrue() // page add
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1")
        assertThat(s.undo()).isTrue() // ink on p1
        assertThat(s.content("p1")!!.elements).isEmpty()
        assertThat(s.undo()).isTrue() // text
        assertThat(s.body.toRichText().text).isEmpty()
        assertThat(s.undo()).isFalse()

        repeat(4) { assertThat(s.redo()).isTrue() }
        assertThat(s.body.toRichText().text).isEqualTo("hi")
        assertThat(s.content("p1")!!.elements.map { it.id.value }).containsExactly(1L, 2L).inOrder()
        assertThat(s.content("p2")!!.elements.map { it.id.value }).containsExactly(3L)
    }

    @Test fun insertedPagesGoAfterThePageAndUndoInOneStep() = runTest {
        val s = session("p1", "p2")
        runCurrent()
        val pdf = listOf(PageSpec("q1", TemplateRef.Image("media/a.png")), PageSpec("q2", TemplateRef.Image("media/b.png")))
        assertThat(s.insertPagesAfter("p1", pdf)).isTrue()
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", "q1", "q2", "p2").inOrder()
        assertThat(s.undo()).isTrue()
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(s.redo()).isTrue()
        // An anchor that is no longer in the note appends at the end; nothing to insert is a no-op.
        assertThat(s.insertPagesAfter("gone", listOf(PageSpec("q3")))).isTrue()
        assertThat(s.holder.current.pages.last().id).isEqualTo("q3")
        assertThat(s.insertPagesAfter("p1", emptyList())).isFalse()
    }

    @Test fun insertedPdfPagesTakeInkAndKeepItThroughUndoRedo() = runTest {
        val s = session("p1", "p2")
        runCurrent()
        val pdf = listOf(PageSpec("q1", TemplateRef.Image("media/a.png")), PageSpec("q2", TemplateRef.Image("media/b.png")))
        s.insertPagesAfter("p1", pdf)
        assertThat(s.addElements("q1", listOf(fakeElement(5)))).isTrue()
        assertThat(s.addElements("q2", listOf(fakeElement(6)))).isTrue()
        assertThat(s.content("q1")!!.elements.map { it.id.value }).containsExactly(5L)
        s.undo() // the stroke on q2
        s.undo() // the stroke on q1
        assertThat(s.content("q1")!!.elements).isEmpty()
        s.redo()
        assertThat(s.content("q1")!!.elements.map { it.id.value }).containsExactly(5L)
    }

    @Test fun deletedPageComesBackWithItsInkOnUndo() = runTest {
        val s = session("p1", "p2")
        runCurrent()
        s.pages.await("p2")
        s.addElements("p2", listOf(fakeElement(7)))
        s.deletePages(setOf("p2"))
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1")
        assertThat(s.pages.content("p2")).isNull()
        s.undo()
        assertThat(s.holder.current.pages.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(s.content("p2")!!.elements.map { it.id.value }).containsExactly(7L)
    }

    @Test fun eraseResultStyleDeltaUndoes() = runTest {
        val s = session()
        runCurrent()
        s.addElements("p1", listOf(fakeElement(1), fakeElement(2), fakeElement(3)))
        s.editElements("p1") { list -> list.filter { it.id.value != 2L } }
        assertThat(s.content("p1")!!.elements.map { it.id.value }).containsExactly(1L, 3L).inOrder()
        s.undo()
        assertThat(s.content("p1")!!.elements.map { it.id.value }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test fun autosaveDebounces400ms() = runTest {
        val s = session()
        runCurrent()
        s.addElements("p1", listOf(fakeElement(1)))
        advanceTimeBy(399); runCurrent()
        assertThat(storage.saved).isEmpty()
        advanceTimeBy(2); runCurrent()
        assertThat(storage.saved).hasSize(1)
        assertThat(storage.saved[0].dirtyPages.map { it.pageId }).containsExactly("p1")
        // clean now: nothing more is written
        advanceTimeBy(5_000)
        assertThat(storage.saved).hasSize(1)
    }

    @Test fun continuousEditsAreForcedEvery3s() = runTest {
        val s = session()
        runCurrent()
        repeat(40) { // an edit every 250 ms for 10 s: the debounce never fires
            s.addElements("p1", listOf(fakeElement(it.toLong())))
            advanceTimeBy(250); runCurrent()
        }
        assertThat(storage.saved.size).isAtLeast(3)
        assertThat(storage.saved.size).isAtMost(4)
        val versions = storage.saved.map { it.dirtyPages.single().version }
        assertThat(versions).isInStrictOrder() // ordering: later snapshots never overwritten by earlier ones
    }

    @Test fun failedSaveKeepsDirtyAndRetriesWithBackoff() = runTest {
        val s = session()
        runCurrent()
        storage.failures = 2
        s.addElements("p1", listOf(fakeElement(1)))
        advanceTimeBy(401); runCurrent()
        assertThat(storage.saved).isEmpty()          // first attempt failed
        advanceTimeBy(1_000); runCurrent()
        assertThat(storage.saved).isEmpty()          // retry after 1 s failed
        advanceTimeBy(2_000); runCurrent()
        assertThat(storage.saved).hasSize(1)         // retry after 2 s succeeded
        assertThat(s.pages.hasDirty()).isFalse()
    }

    @Test fun flushSavesAndPacksAndCloseDropsWorkingCopy() = runTest {
        val s = session()
        runCurrent()
        s.addElements("p1", listOf(fakeElement(1)))
        s.flush()
        assertThat(storage.saved).hasSize(1)
        assertThat(storage.packs).containsExactly(false)
        s.setTitle("t")
        s.finish()
        assertThat(storage.saved).hasSize(2)
        assertThat(storage.packs.last()).isTrue()
        assertThat(storage.saved.last().document.title).isEqualTo("t")
    }

    @Test fun periodicContainerFlushWhileDirty() = runTest {
        val s = session()
        runCurrent()
        s.addElements("p1", listOf(fakeElement(1)))
        advanceTimeBy(31_000); runCurrent()
        assertThat(storage.packs).containsExactly(false)
    }

    @Test fun dirtyPagesAreNeverEvicted() = runTest {
        val s = session("p1", "p2", "p3")
        runCurrent()
        val small = PageStore(backgroundScope, storage::load, maxLoaded = 1)
        small.request("p1"); runCurrent()
        small.edit("p1") { listOf(fakeElement(1)) }
        small.request("p2"); runCurrent()
        small.request("p3"); runCurrent()
        assertThat(small.content("p1")).isNotNull()
        assertThat(small.content("p2")).isNull() // clean pages were evicted instead
    }

    @Test fun bodyTextIsSnapshottedOnlyWhenDirty() = runTest {
        val s = session()
        runCurrent()
        s.typeText("abc")
        s.flush()
        assertThat(storage.saved.single().document.body.text).isEqualTo("abc")
    }

    // ---- A5 regression: "typed text lost" (device round 3, note C)

    /** Typing 40 lines and closing at once (back pressed straight after the last key) must keep every line. */
    @Test fun quickTypingThenImmediateFinishKeepsAllText() = runTest {
        val s = session()
        runCurrent()
        val expected = (1..40).joinToString("") { "Line $it\n" }
        (1..40).forEach { s.typeText("Line $it\n") }
        s.finish() // no time passes: the 400 ms debounce never fired
        assertThat(storage.saved.last().document.body.text).isEqualTo(expected)
        assertThat(storage.packs.last()).isTrue()
        assertThat(s.hasUnsavedChanges()).isFalse()
    }

    /** The editor closes while the debounced save is still writing: the in-flight write is cancelled, the final one wins. */
    @Test fun finishDuringInFlightSaveStillWritesLatestText() = runTest {
        val slowSaved = ArrayList<String>()
        val s = EditorSession(
            noteId = "n1", initialDocument = doc("p1"), scope = backgroundScope, io = StandardTestDispatcher(testScheduler),
            pageLoader = storage::load,
            saveSink = { snap -> kotlinx.coroutines.delay(500); slowSaved += snap.document.body.text },
            packContainer = storage::pack, prefs = FakePrefs(), clock = { testScheduler.currentTime },
        )
        runCurrent()
        s.typeText("first")
        advanceTimeBy(450); runCurrent() // the debounce fired, the write is suspended mid-way
        s.typeText(" second")
        s.finish()
        advanceUntilIdle()
        assertThat(slowSaved.last()).isEqualTo("first second")
        assertThat(storage.packs.last()).isTrue()
    }

    /** Edits made after a snapshot was taken (but before it finished writing) keep the note dirty. */
    @Test fun editDuringSaveKeepsNoteDirty() = runTest {
        val s = EditorSession(
            noteId = "n1", initialDocument = doc("p1"), scope = backgroundScope, io = StandardTestDispatcher(testScheduler),
            pageLoader = storage::load,
            saveSink = { kotlinx.coroutines.delay(500) },
            packContainer = storage::pack, prefs = FakePrefs(), clock = { testScheduler.currentTime },
        )
        runCurrent()
        s.typeText("a")
        advanceTimeBy(450); runCurrent()
        s.typeText("b")
        advanceTimeBy(100); runCurrent() // first write completes
        assertThat(s.hasUnsavedChanges()).isTrue()
    }

    /**
     * rel011: a pure view change (scroll, zoom, page) goes to the per-note store and never to the document. It must not
     * bump the document revision, make the note dirty, or trigger a save/pack.
     */
    @Test fun viewStateIsStoredOutsideTheDocumentAndNeverDirtiesIt() = runTest {
        val saved = mutableListOf<ViewState>()
        val s = EditorSession(
            noteId = "n1", initialDocument = doc("p1"), scope = backgroundScope, io = StandardTestDispatcher(testScheduler),
            pageLoader = storage::load,
            saveSink = storage::save,
            packContainer = storage::pack, prefs = FakePrefs(), clock = { testScheduler.currentTime },
            initialViewState = ViewState(0, 5f, 1f),
            viewStateSink = { saved += it },
        )
        runCurrent()
        val revision = s.holder.revision
        assertThat(s.initialViewState).isEqualTo(ViewState(0, 5f, 1f))

        // The restored position is not written back.
        s.setViewState(ViewState(0, 5f, 1f))
        runCurrent()
        assertThat(saved).isEmpty()

        s.setViewState(ViewState(2, 30f, 1.25f))
        runCurrent()
        assertThat(saved).containsExactly(ViewState(2, 30f, 1.25f)).inOrder()
        assertThat(s.holder.revision).isEqualTo(revision)
        assertThat(s.hasUnsavedChanges()).isFalse()
        assertThat(storage.saved).isEmpty()
        assertThat(storage.packs).isEmpty()
    }
}
