package org.samesame.notes.feature.composer.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchNavigatorTest {
    private val text = "alpha beta alpha\ngamma ALPHA delta alpha"

    @Test fun newSearchJumpsToFirstHit() {
        val n = SearchNavigator()
        assertThat(n.search(text, "alpha")).isEqualTo(0..4)
        assertThat(n.count).isEqualTo(4)
        assertThat(n.current).isEqualTo(0)
        assertThat(n.canBackward).isFalse()
        assertThat(n.canForward).isTrue()
    }

    @Test fun forwardWalksAllHitsWithoutWrapping() {
        val n = SearchNavigator()
        n.search(text, "alpha")
        val seen = mutableListOf(n.current)
        while (n.canForward) { n.forward(); seen += n.current }
        assertThat(seen).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(n.forward()).isEqualTo(n.matches[3]) // stays on the last hit
        assertThat(n.current).isEqualTo(3)
        assertThat(n.canForward).isFalse()
        assertThat(n.canBackward).isTrue()
    }

    @Test fun backwardStopsAtFirst() {
        val n = SearchNavigator()
        n.search(text, "alpha"); n.forward(); n.forward()
        assertThat(n.backward()).isEqualTo(n.matches[1])
        n.backward()
        assertThat(n.current).isEqualTo(0)
        n.backward()
        assertThat(n.current).isEqualTo(0)
    }

    @Test fun noHitsDisablesBothArrows() {
        val n = SearchNavigator()
        assertThat(n.search(text, "zzz")).isNull()
        assertThat(n.canForward).isFalse()
        assertThat(n.canBackward).isFalse()
        assertThat(n.current).isEqualTo(-1)
    }

    @Test fun newQueryResetsTheCursor() {
        val n = SearchNavigator()
        n.search(text, "alpha"); n.forward(); n.forward()
        n.search(text, "gamma")
        assertThat(n.count).isEqualTo(1)
        assertThat(n.current).isEqualTo(0)
    }

    @Test fun queryIsCappedAt199Characters() {
        val n = SearchNavigator()
        n.search("x".repeat(400), "x".repeat(300))
        assertThat(n.query).hasLength(199)
        assertThat(n.count).isEqualTo(2) // 400 / 199 non-overlapping hits
    }

    @Test fun hitsAcrossPagesNavigateInOrder() {
        // Page 0: offsets 0..99, page 1: 100..199, page 2: 200+.
        val pageOf = { o: Int -> o / 100 }
        val body = buildString {
            append("hit"); append(" ".repeat(150)); append("hit"); append(" ".repeat(80)); append("hit")
        }
        val n = SearchNavigator()
        n.search(body, "hit")
        assertThat(n.matches.map { pageOf(it.first) }).containsExactly(0, 1, 2).inOrder()
        assertThat(SearchNavigator.pagesWithHits(n.matches, pageOf)).containsExactly(0, 1, 2).inOrder()
        n.forward(); n.forward()
        assertThat(pageOf(n.matches[n.current].first)).isEqualTo(2)
        n.backward()
        assertThat(pageOf(n.matches[n.current].first)).isEqualTo(1)
    }

    @Test fun pagesWithHitsAreDistinctAndSorted() {
        val matches = listOf(250..252, 5..7, 9..11, 130..132)
        assertThat(SearchNavigator.pagesWithHits(matches) { it / 100 }).containsExactly(0, 1, 2).inOrder()
        assertThat(SearchNavigator.hitsOnPage(matches, 0) { it / 100 }).isEqualTo(2)
    }

    @Test fun searchOverBodyAndPdfPagesNavigatesInUnifiedOrder() {
        val body = "Hello from body text"
        val pdfText = "Hello from PDF page"
        val glyphs = pdfText.mapIndexed { i, ch ->
            org.samesame.notes.core.model.pdf.PdfGlyph(
                char = ch,
                boundsPt = org.samesame.notes.core.model.pdf.RectPt(i * 10f, 100f, (i + 1) * 10f, 120f),
                index = i,
            )
        }
        val pdfPage = org.samesame.notes.core.model.pdf.PdfPageText(
            pageIndex = 0,
            text = pdfText,
            glyphs = glyphs,
            lines = listOf(
                org.samesame.notes.core.model.pdf.PdfLine(
                    text = pdfText,
                    boundsPt = org.samesame.notes.core.model.pdf.RectPt(0f, 100f, 200f, 120f),
                    words = emptyList(),
                )
            ),
            links = emptyList(),
        )

        val n = SearchNavigator()
        val firstHit = n.search(body, "Hello", listOf(pdfPage))
        assertThat(firstHit).isEqualTo(0..4)
        assertThat(n.count).isEqualTo(2)
        assertThat(n.currentHit()).isInstanceOf(SearchHit.Body::class.java)

        val secondHit = n.forwardHit()
        assertThat(secondHit).isInstanceOf(SearchHit.Pdf::class.java)
        val pdfHit = (secondHit as SearchHit.Pdf).match
        assertThat(pdfHit.pageIndex).isEqualTo(0)
        assertThat(pdfHit.start).isEqualTo(0)
        assertThat(pdfHit.endExclusive).isEqualTo(5)

        val backHit = n.backwardHit()
        assertThat(backHit).isInstanceOf(SearchHit.Body::class.java)
    }
}
