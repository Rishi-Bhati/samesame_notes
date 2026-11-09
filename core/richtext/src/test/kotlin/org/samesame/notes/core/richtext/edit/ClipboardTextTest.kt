package org.samesame.notes.core.richtext.edit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle

/** E4: the plain text Copy / Cut put on the system clipboard carries the list markers the editor only draws. */
class ClipboardTextTest {

    private val plain = ParagraphStyle()
    private val numbered = ParagraphStyle(list = ListKind.Numbered)
    private val bullet = ParagraphStyle(list = ListKind.Bullet)

    private fun clip(text: String, paragraphs: List<ParagraphStyle>, start: Int = 0, end: Int = text.length): String =
        ClipboardText.withListMarkers(text, paragraphs, ParagraphRules.numbering(paragraphs), start, end)

    /** The rc1 device note "Numbered A": its first body line is "Numbered A" (the title field is empty). Select all + Copy. */
    @Test fun selectAllOfTheNumberedNoteCopiesTheNumbers() {
        val text = "Numbered A\n\n\n\n\none\nTwo\nthree"
        val paragraphs = listOf(plain, plain, plain, plain, plain, plain, numbered, numbered)
        assertThat(clip(text, paragraphs)).isEqualTo("Numbered A\n\n\n\n\none\n1. Two\n2. three")
    }

    @Test fun everyListKindGetsItsMarker() {
        val text = "a\nb\nc\nd\ne"
        val paragraphs = listOf(
            bullet,
            numbered,
            numbered,
            ParagraphStyle(list = ListKind.Checklist, checked = true),
            ParagraphStyle(list = ListKind.Checklist),
        )
        assertThat(clip(text, paragraphs)).isEqualTo("• a\n1. b\n2. c\n[x] d\n[ ] e")
    }

    @Test fun onlyParagraphsWhoseStartIsCoveredGetAMarker() {
        val text = "one\ntwo\nthree"
        val paragraphs = listOf(numbered, numbered, numbered)
        assertThat(clip(text, paragraphs, 2, 8)).isEqualTo("e\n2. two\n")
        assertThat(clip(text, paragraphs, 8, 13)).isEqualTo("3. three")
        assertThat(clip(text, paragraphs, 9, 13)).isEqualTo("hree")
        assertThat(clip(text, paragraphs, 4, 4)).isEmpty()
    }

    @Test fun indentationAndNestedNumberingAreKept() {
        val text = "a\nb\nc"
        val paragraphs = listOf(numbered, ParagraphStyle(list = ListKind.Numbered, indent = 1), numbered)
        assertThat(clip(text, paragraphs)).isEqualTo("1. a\n  1. b\n2. c")
    }

    @Test fun inlineObjectsAreDropped() {
        val text = "x${OBJECT_CHAR}y"
        assertThat(clip(text, listOf(bullet))).isEqualTo("• xy")
    }
}
