package org.samesame.notes.core.richtext.edit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.richtext.ListKind

class TypingShortcutsTest {
    @Test fun bulletMarkersAreConsumed() {
        for (marker in listOf("- ", "* ", "\u2022 ")) {
            assertThat(TypingShortcuts.blockMarker(marker))
                .isEqualTo(BlockMarker(marker, ListKind.Bullet, false))
        }
    }

    @Test fun numberedMarkersAreConsumed() {
        assertThat(TypingShortcuts.blockMarker("1. ")).isEqualTo(BlockMarker("1. ", ListKind.Numbered, false))
        assertThat(TypingShortcuts.blockMarker("12) ")).isEqualTo(BlockMarker("12) ", ListKind.Numbered, false))
    }

    @Test fun checklistMarkersAreConsumed() {
        assertThat(TypingShortcuts.blockMarker("[] ")).isEqualTo(BlockMarker("[] ", ListKind.Checklist, false))
        assertThat(TypingShortcuts.blockMarker("[ ] ")).isEqualTo(BlockMarker("[ ] ", ListKind.Checklist, false))
        assertThat(TypingShortcuts.blockMarker("[x] ")).isEqualTo(BlockMarker("[x] ", ListKind.Checklist, true))
    }

    @Test fun incompleteOrWrongMarkersAreIgnored() {
        for (text in listOf("-", "-x ", "1.", "1 ", "[x]", "a- ", " - ", "", "1234. ")) {
            assertThat(TypingShortcuts.blockMarker(text)).isNull()
        }
    }

    @Test fun boldItalicAndStrikeAreRecognised() {
        assertThat(TypingShortcuts.inline("say *hello*"))
            .isEqualTo(InlineMarker(5, 10, InlineMarker.Kind.Bold))
        assertThat(TypingShortcuts.inline("an _italic_"))
            .isEqualTo(InlineMarker(4, 10, InlineMarker.Kind.Italic))
        assertThat(TypingShortcuts.inline("a ~gone~"))
            .isEqualTo(InlineMarker(3, 7, InlineMarker.Kind.Strikethrough))
        assertThat(TypingShortcuts.inline("*bold*"))
            .isEqualTo(InlineMarker(1, 5, InlineMarker.Kind.Bold))
    }

    @Test fun snakeCaseAndArithmeticAreNotFormatted() {
        assertThat(TypingShortcuts.inline("snake_case_")).isNull()
        assertThat(TypingShortcuts.inline("2*3*4")).isNull()
        assertThat(TypingShortcuts.inline("a*1*2")).isNull()
    }

    @Test fun emptyAndWhitespaceSpansAreNotFormatted() {
        assertThat(TypingShortcuts.inline("**")).isNull()
        assertThat(TypingShortcuts.inline("a* *")).isNull()
        assertThat(TypingShortcuts.inline("a__b")).isNull()
    }

    @Test fun urlsAreNotFormatted() {
        assertThat(TypingShortcuts.inline("see https://example.com/a_b_")).isNull()
        assertThat(TypingShortcuts.inline("www.example.com/_x_")).isNull()
    }

    @Test fun inlineContentWithSpacesIsAllowed() {
        assertThat(TypingShortcuts.inline("a *two words*"))
            .isEqualTo(InlineMarker(3, 12, InlineMarker.Kind.Bold))
    }

    @Test fun autoIndentKeepsSpacesAndTabs() {
        assertThat(TypingShortcuts.autoIndent("    hello")).isEqualTo("    ")
        assertThat(TypingShortcuts.autoIndent("\t\tx")).isEqualTo("\t\t")
        assertThat(TypingShortcuts.autoIndent("hello")).isEqualTo("")
        assertThat(TypingShortcuts.autoIndent("")).isEqualTo("")
    }
}
