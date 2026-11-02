package org.samesame.notes.feature.composer.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.feature.composer.canvas.DocumentLayout

/**
 * Opening a note shapes its body off the main thread for a `DocumentLayout` the view-model built, while the editor builds
 * its own instance from the same document. `BodyTextEngine` only keeps that shaped layout when the geometry it is handed
 * is value-equal to the one it was shaped for (it rebuilds only on a column-width / line-pitch change), so the two
 * instances — built on different threads — must keep comparing equal. These tests pin that invariant.
 */
class PreparedLayoutGeometryTest {

    private fun document(vararg pages: PageSpec) = NoteDocument(id = "n1", pages = pages.toList())

    private fun fourPages() = document(PageSpec("a"), PageSpec("b"), PageSpec("c"), PageSpec("d"))

    @Test fun viewModelGeometryEqualsTheEditorsGeometry() {
        val doc = fourPages()
        val beforeComposition = DocumentLayout(doc.pages, doc.layout) // built on Dispatchers.Default by the view-model
        val inComposition = DocumentLayout(doc.pages, doc.layout) // built while the editor composes
        assertThat(inComposition).isEqualTo(beforeComposition)
        assertThat(inComposition.columnWidth).isEqualTo(beforeComposition.columnWidth)
        assertThat(inComposition.pageCount).isEqualTo(4)
    }

    @Test fun ruledTemplateLinePitchSurvivesTheSecondConstruction() {
        val doc = document(
            PageSpec("a", template = TemplateRef.Builtin(PageTemplate.LinesMedium)),
            PageSpec("b"),
            PageSpec("c", template = TemplateRef.Builtin(PageTemplate.LinesMedium)),
        )
        val a = DocumentLayout(doc.pages, doc.layout)
        val b = DocumentLayout(doc.pages, doc.layout)
        assertThat(b).isEqualTo(a)
        assertThat(a.linePitch(0)).isNotNull()
        assertThat(a.linePitch(1)).isNull()
        assertThat(b.linePitch(2)).isEqualTo(a.linePitch(0))
    }

    @Test fun otherRatiosAndPageSizesDoNotCompareEqual() {
        val portrait = DocumentLayout(document(PageSpec("a")).pages, PageLayout())
        val landscape = DocumentLayout(document(PageSpec("a")).pages, PageLayout(ratio = PageRatio.A4Landscape))
        // Same horizontal column, different page heights: a layout shaped for one must not be adopted for the other.
        assertThat(landscape.columnWidth).isEqualTo(portrait.columnWidth)
        assertThat(landscape).isNotEqualTo(portrait)
    }
}
