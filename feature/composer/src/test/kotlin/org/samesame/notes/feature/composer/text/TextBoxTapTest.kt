package org.samesame.notes.feature.composer.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.ink.page.ElementId

class TextBoxTapTest {
    private val id = ElementId(7)

    @Test fun `a tap on an unselected box selects it`() {
        assertThat(textBoxTapAction(null, emptySet(), "p1", id)).isEqualTo(TextBoxTapAction.Select)
        assertThat(textBoxTapAction("p1", setOf(ElementId(8)), "p1", id)).isEqualTo(TextBoxTapAction.Select)
    }

    @Test fun `a tap on the selected box enters editing`() {
        assertThat(textBoxTapAction("p1", setOf(id), "p1", id)).isEqualTo(TextBoxTapAction.Edit)
    }

    @Test fun `a selection on another page does not count as the same box`() {
        assertThat(textBoxTapAction("p2", setOf(id), "p1", id)).isEqualTo(TextBoxTapAction.Select)
    }

    @Test fun `a multi-object selection including the box still edits it`() {
        assertThat(textBoxTapAction("p1", setOf(id, ElementId(9)), "p1", id)).isEqualTo(TextBoxTapAction.Edit)
    }
}
