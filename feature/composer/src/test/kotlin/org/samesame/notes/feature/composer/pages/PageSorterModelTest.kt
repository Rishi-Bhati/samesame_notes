package org.samesame.notes.feature.composer.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.doc
import org.samesame.notes.feature.composer.session.EditorSession

@OptIn(ExperimentalCoroutinesApi::class)
class PageSorterModelTest {
    private val pages = listOf(
        PageSpec(id = "a"), PageSpec(id = "b", isBookmarked = true), PageSpec(id = "c"), PageSpec(id = "d", isBookmarked = true),
    )

    @Test fun modesListTheRightPages() {
        assertThat(PageSorterModel.visibleIndices(SorterMode.Normal, pages, emptyList())).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(PageSorterModel.visibleIndices(SorterMode.Edit, pages, emptyList())).hasSize(4)
        assertThat(PageSorterModel.visibleIndices(SorterMode.Bookmark, pages, emptyList())).containsExactly(1, 3).inOrder()
        assertThat(PageSorterModel.visibleIndices(SorterMode.Search, pages, listOf(2, 9))).containsExactly(2)
        assertThat(PageSorterModel.showsAddTile(SorterMode.Normal)).isTrue()
        assertThat(PageSorterModel.showsAddTile(SorterMode.Edit)).isFalse()
        assertThat(PageSorterModel.showsAddTile(SorterMode.Bookmark)).isFalse()
    }

    @Test fun noBookmarksGivesAnEmptyList() {
        val none = pages.map { it.copy(isBookmarked = false) }
        assertThat(PageSorterModel.visibleIndices(SorterMode.Bookmark, none, emptyList())).isEmpty()
    }

    @Test fun selectionToggleSelectAllAndPrune() {
        var sel = PageSorterModel.toggle(emptySet(), "a")
        sel = PageSorterModel.toggle(sel, "c")
        assertThat(sel).containsExactly("a", "c")
        assertThat(PageSorterModel.toggle(sel, "a")).containsExactly("c")
        assertThat(PageSorterModel.selectAll(listOf("a", "b"), sel)).containsExactly("a", "b")
        assertThat(PageSorterModel.selectAll(listOf("a", "b"), setOf("a", "b", "c"))).isEmpty() // all visible selected: clears
        assertThat(PageSorterModel.prune(setOf("a", "zzz"), pages)).containsExactly("a")
    }

    @Test fun recentKeywordsNewestFirstDedupedAndCapped() {
        var r = emptyList<String>()
        r = PageSorterModel.addRecent(r, "foo")
        r = PageSorterModel.addRecent(r, "Bar")
        r = PageSorterModel.addRecent(r, "FOO")
        assertThat(r).containsExactly("FOO", "Bar").inOrder()
        assertThat(PageSorterModel.addRecent(r, "  ")).isEqualTo(r)
        repeat(20) { r = PageSorterModel.addRecent(r, "k$it") }
        assertThat(r).hasSize(10)
    }

    private fun TestScope.session() = EditorSession(
        noteId = "n1", initialDocument = doc("p1", "p2", "p3"), scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler), pageLoader = storage::load, saveSink = storage::save,
        packContainer = storage::pack, prefs = FakePrefs(), clock = { testScheduler.currentTime },
    )
    private val storage = FakeStorage()

    @Test fun bookmarkTogglePersistsInTheSavedDocumentAndUndoes() = runTest {
        val s = session()
        runCurrent()
        s.updatePageSpec("p2") { it.copy(isBookmarked = true) }
        s.saveNow()
        advanceUntilIdle()
        val saved = storage.saved.last().document
        assertThat(saved.pages.filter { it.isBookmarked }.map { it.id }).containsExactly("p2")
        // The Bookmark filter sees it, and survives a page reorder.
        s.reorderPages(s.holder.current.pages.reversed())
        assertThat(PageSorterModel.visibleIndices(SorterMode.Bookmark, s.holder.current.pages, emptyList())).containsExactly(1)
        s.undo(); s.undo()
        assertThat(s.holder.current.pages.none { it.isBookmarked }).isTrue()
    }

    @Test fun deletedPagesLeaveTheBookmarkList() = runTest {
        val s = session()
        runCurrent()
        s.updatePageSpec("p1") { it.copy(isBookmarked = true) }
        s.deletePages(setOf("p1"))
        assertThat(PageSorterModel.visibleIndices(SorterMode.Bookmark, s.holder.current.pages, emptyList())).isEmpty()
    }
}
