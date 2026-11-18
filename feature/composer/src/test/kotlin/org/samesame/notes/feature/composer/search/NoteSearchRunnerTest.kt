package org.samesame.notes.feature.composer.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.pdf.PdfPageText

class NoteSearchRunnerTest {
    @Test fun bodyAndPdfShareOneBudgetAndStopReadingPages() = runTest {
        var visited = 0
        val runner = NoteSearchRunner(this, { _, visit ->
            for (i in 0..100) {
                visited++
                if (!visit(PdfPageText(i, "a".repeat(500), emptyList(), emptyList(), emptyList()))) break
            }
        }, StandardTestDispatcher(testScheduler))
        var result: SearchNavigator.SearchResult? = null
        runner.submit("a".repeat(750), "a", emptyList()) { result = it }
        advanceUntilIdle()
        assertThat(result!!.body.size + result!!.pdf.size).isEqualTo(Limits.PDF_SEARCH_MAX_MATCHES)
        assertThat(result!!.pdf).hasSize(250)
        assertThat(visited).isEqualTo(1)
    }

    @Test fun newQueryCancelsPreviousWorkAndPublishesOnlyNewResults() = runTest {
        var started = false
        val runner = NoteSearchRunner(this, { _, _ -> started = true; awaitCancellation() }, StandardTestDispatcher(testScheduler))
        val published = mutableListOf<String>()
        runner.submit("", "old", emptyList()) { published += it.query }
        runCurrent()
        assertThat(started).isTrue()
        runner.submit("new".repeat(1000), "new", emptyList()) { published += it.query }
        advanceUntilIdle()
        assertThat(published).containsExactly("new")
    }

    @Test fun bodySearchStopsAtSharedBudget() {
        val result = SearchNavigator.bodyResult("a".repeat(100000), "a")
        assertThat(result.body).hasSize(Limits.PDF_SEARCH_MAX_MATCHES)
    }
}
