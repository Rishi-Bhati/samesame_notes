package org.samesame.notes.feature.composer.pages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmptyPagePolicyTest {

    private fun emptyPage(id: String) = PageFacts(
        id = id,
        hasElements = false,
        hasPdf = false,
        hasImageTemplate = false,
        bookmarked = false,
    )

    @Test
    fun trailingEmptyPagesRemovedFromTheEnd() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1"), emptyPage("p2"), emptyPage("p3"))
        // Caret moved from p3 to p1, text ends on p1. Both p2 and p3 are trailing empty pages.
        val removed = pagesToRemoveAfterBackspace(
            pages = pages,
            caretPageBefore = 3,
            caretPageAfter = 1,
            lastTextPage = 1,
        )
        assertThat(removed).containsExactly("p2", "p3").inOrder()
    }

    @Test
    fun stopsAtNonEmptyPage() {
        // Last page has elements (ink): nothing is removed because scan stops at the end
        val pagesWithLastInk = listOf(
            emptyPage("p0"),
            emptyPage("p1"),
            emptyPage("p2"),
            emptyPage("p3").copy(hasElements = true),
        )
        val removed1 = pagesToRemoveAfterBackspace(
            pages = pagesWithLastInk,
            caretPageBefore = 2,
            caretPageAfter = 1,
            lastTextPage = 1,
        )
        assertThat(removed1).isEmpty()

        // Page 3 empty, page 2 has ink: only page 3 is removed, stopping at page 2
        val pagesWithMiddleInk = listOf(
            emptyPage("p0"),
            emptyPage("p1"),
            emptyPage("p2").copy(hasElements = true),
            emptyPage("p3"),
        )
        val removed2 = pagesToRemoveAfterBackspace(
            pages = pagesWithMiddleInk,
            caretPageBefore = 3,
            caretPageAfter = 1,
            lastTextPage = 1,
        )
        assertThat(removed2).containsExactly("p3")
    }

    @Test
    fun inkKeepsPage() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1").copy(hasElements = true))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = 0, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun pdfKeepsPage() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1").copy(hasPdf = true))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = 0, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun imageTemplateKeepsPage() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1").copy(hasImageTemplate = true))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = 0, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun bookmarkKeepsPage() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1").copy(bookmarked = true))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = 0, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun neverRemovesTheOnlyPage() {
        val pages = listOf(emptyPage("p0"))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = 0, lastTextPage = -1)
        assertThat(removed).isEmpty()
    }

    @Test
    fun neverRemovesPagesLessThanOrEqualToM() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1"), emptyPage("p2"))
        // Caret moved from p2 to p1 (M = 1), text ends on p0 (lastTextPage = 0).
        // Page 1 has index <= M, so it must not be removed even though it is after lastTextPage.
        val removed = pagesToRemoveAfterBackspace(
            pages = pages,
            caretPageBefore = 2,
            caretPageAfter = 1,
            lastTextPage = 0,
        )
        assertThat(removed).containsExactly("p2")
    }

    @Test
    fun caretDidNotChangePageYieldsNothing() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1"))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = 1, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun caretMovedForwardYieldsNothing() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1"))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 0, caretPageAfter = 1, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun emptyDocumentWithNoTextRemovesTrailingEmptyPages() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1"), emptyPage("p2"))
        val removed = pagesToRemoveAfterBackspace(
            pages = pages,
            caretPageBefore = 2,
            caretPageAfter = 0,
            lastTextPage = -1,
        )
        assertThat(removed).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun emptyPagesListYieldsNothing() {
        val removed = pagesToRemoveAfterBackspace(emptyList(), caretPageBefore = 1, caretPageAfter = 0, lastTextPage = 0)
        assertThat(removed).isEmpty()
    }

    @Test
    fun negativeCaretPageAfterYieldsNothing() {
        val pages = listOf(emptyPage("p0"), emptyPage("p1"))
        val removed = pagesToRemoveAfterBackspace(pages, caretPageBefore = 1, caretPageAfter = -1, lastTextPage = -1)
        assertThat(removed).isEmpty()
    }
}
