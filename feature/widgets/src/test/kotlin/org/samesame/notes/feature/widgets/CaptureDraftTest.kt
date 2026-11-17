package org.samesame.notes.feature.widgets

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureDraftTest {
    @Test fun `an empty or blank draft creates nothing`() {
        assertThat(CaptureDraft().isEmpty).isTrue()
        assertThat(CaptureDraft(title = "  ", body = "\n ").isEmpty).isTrue()
        assertThat(CaptureDraft(checklist = true, items = listOf("", " ")).isEmpty).isTrue()
        assertThat(CaptureDraft(title = "Idea").isEmpty).isFalse()
        assertThat(CaptureDraft(checklist = true, items = listOf("", "milk")).isEmpty).isFalse()
    }

    @Test fun `text becomes one checkbox paragraph per item`() {
        val rt = CaptureDraft(checklist = true, items = listOf(" milk ", "", "eggs")).richText()
        assertThat(rt.text).isEqualTo("milk\neggs")
        assertThat(rt.paragraphs).hasSize(2)
        assertThat(rt.paragraphs.all { it.checkbox && !it.checked }).isTrue()
    }

    @Test fun `plain text keeps its lines and has no checkboxes`() {
        val rt = CaptureDraft(body = "line one\nline two\n").richText()
        assertThat(rt.text).isEqualTo("line one\nline two")
        assertThat(rt.paragraphs.none { it.checkbox }).isTrue()
    }

    @Test fun `switching modes keeps every word`() {
        val list = CaptureDraft(body = "milk\n\n eggs \nbread").toChecklist()
        assertThat(list.items).containsExactly("milk", "eggs", "bread").inOrder()
        assertThat(list.toText().body).isEqualTo("milk\neggs\nbread")
        assertThat(CaptureDraft(body = "").toChecklist().items).containsExactly("")
    }

    @Test fun `dictation is appended`() {
        assertThat(CaptureDraft(body = "Call").append(" Anna ").body).isEqualTo("Call Anna")
        assertThat(CaptureDraft().append("hello").body).isEqualTo("hello")
        val list = CaptureDraft(checklist = true, items = listOf("milk", ""))
        assertThat(list.append("eggs").items).containsExactly("milk", "eggs").inOrder()
        assertThat(CaptureDraft(checklist = true, items = listOf("milk")).append("eggs").items).containsExactly("milk", "eggs").inOrder()
        assertThat(CaptureDraft(body = "x").append("  ").body).isEqualTo("x")
    }

    @Test fun `items are edited, inserted and removed and one item always stays`() {
        var d = CaptureDraft(checklist = true)
        d = d.setItem(0, "a\nb")
        assertThat(d.items).containsExactly("a b")
        d = d.insertItemAfter(0).setItem(1, "c")
        assertThat(d.items).containsExactly("a b", "c").inOrder()
        d = d.removeItem(0).removeItem(0)
        assertThat(d.items).containsExactly("")
        assertThat(d.setItem(5, "x")).isEqualTo(d)
    }

    @Test fun `title is one trimmed line`() {
        assertThat(CaptureDraft(title = " Shopping\nlist ").noteTitle()).isEqualTo("Shopping list")
    }

    @Test fun `capture modes round-trip`() {
        CaptureMode.entries.forEach { assertThat(CaptureMode.fromKey(it.key)).isEqualTo(it) }
        assertThat(CaptureMode.fromKey(null)).isEqualTo(CaptureMode.Text)
    }
}
