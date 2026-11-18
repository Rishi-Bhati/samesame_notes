package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.QuadPu
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.doc

@OptIn(ExperimentalCoroutinesApi::class)
class TextMarkupSessionTest {
    private val storage = FakeStorage()

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = doc("p1"),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        prefs = FakePrefs(),
        clock = { testScheduler.currentTime },
    )

    private val quad = QuadPu.fromRect(RectPu(10f, 10f, 200f, 30f))

    private fun EditorSession.markups() = holder.current.pages.first().textMarkup

    @Test fun `add undo redo and remove a markup`() = runTest {
        val s = session()
        val id = s.addTextMarkup("p1", PdfMarkupKind.Highlight, 0xFFFFEB3BL, listOf(quad), "hello")!!
        assertThat(s.markups().map { it.id }).containsExactly(id)
        assertThat(s.markups().single().text).isEqualTo("hello")

        assertThat(s.undo()).isTrue()
        assertThat(s.markups()).isEmpty()
        assertThat(s.redo()).isTrue()
        assertThat(s.markups()).hasSize(1)

        assertThat(s.removeTextMarkup("p1", id)).isTrue()
        assertThat(s.markups()).isEmpty()
        assertThat(s.undo()).isTrue()
        assertThat(s.markups()).hasSize(1)
        assertThat(s.removeTextMarkup("p1", "nope")).isFalse()
    }

    @Test fun `rejects empty quads and unknown pages`() = runTest {
        val s = session()
        assertThat(s.addTextMarkup("p1", PdfMarkupKind.Strike, 0xFF000000L, emptyList())).isNull()
        assertThat(s.addTextMarkup("zz", PdfMarkupKind.Strike, 0xFF000000L, listOf(quad))).isNull()
        assertThat(s.markups()).isEmpty()
    }
}
