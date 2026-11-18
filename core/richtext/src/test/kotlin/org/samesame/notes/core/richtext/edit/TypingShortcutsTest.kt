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
        assertThat(TypingShortcuts.blockMarker("[] ")).isEqualTo(BlockMarker("[] ", ListKind.None, false, true))
        assertThat(TypingShortcuts.blockMarker("[ ] ")).isEqualTo(BlockMarker("[ ] ", ListKind.None, false, true))
        assertThat(TypingShortcuts.blockMarker("[x] ")).isEqualTo(BlockMarker("[x] ", ListKind.None, true, true))
        assertThat(TypingShortcuts.blockMarker("[X] ")).isEqualTo(BlockMarker("[X] ", ListKind.None, true, true))
    }

    /** B1: a marker only sets its own part, so typing "1. " on a checkbox line keeps the checkbox and adds numbering. */
    @Test fun markersAddressOnlyTheirOwnPartOfTheMarker() {
        for (m in listOf("[] ", "[ ] ", "[x] ", "[X] ")) {
            with(TypingShortcuts.blockMarker(m)!!) {
                assertThat(checkbox).isTrue()
                assertThat(list).isEqualTo(ListKind.None)
            }
        }
        for (m in listOf("- ", "* ", "\u2022 ", "1. ", "12) ")) {
            with(TypingShortcuts.blockMarker(m)!!) {
                assertThat(checkbox).isFalse()
                assertThat(checked).isFalse()
                assertThat(list).isNotEqualTo(ListKind.None)
            }
        }
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

    @Test fun arrowsAreRecognisedAtLineStartAndMidSentence() {
        // Line start
        assertThat(TypingShortcuts.arrow("->")).isEqualTo(ArrowReplacement(2, "→", "->"))
        assertThat(TypingShortcuts.arrow("<-")).isEqualTo(ArrowReplacement(2, "←", "<-"))
        assertThat(TypingShortcuts.arrow("<->")).isEqualTo(ArrowReplacement(3, "↔", "<->"))
        assertThat(TypingShortcuts.arrow("←>")).isEqualTo(ArrowReplacement(2, "↔", "<->"))
        assertThat(TypingShortcuts.arrow("=>")).isEqualTo(ArrowReplacement(2, "⇒", "=>"))
        assertThat(TypingShortcuts.arrow("<=>")).isEqualTo(ArrowReplacement(3, "⇔", "<=>"))

        // Mid-sentence with space
        assertThat(TypingShortcuts.arrow("a ->")).isEqualTo(ArrowReplacement(2, "→", "->"))
        assertThat(TypingShortcuts.arrow("a <-")).isEqualTo(ArrowReplacement(2, "←", "<-"))
        assertThat(TypingShortcuts.arrow("a <->")).isEqualTo(ArrowReplacement(3, "↔", "<->"))
        assertThat(TypingShortcuts.arrow("a ←>")).isEqualTo(ArrowReplacement(2, "↔", "<->"))
        assertThat(TypingShortcuts.arrow("a =>")).isEqualTo(ArrowReplacement(2, "⇒", "=>"))
        assertThat(TypingShortcuts.arrow("a <=>")).isEqualTo(ArrowReplacement(3, "⇔", "<=>"))

        // Mid-sentence attached to text
        assertThat(TypingShortcuts.arrow("x->")).isEqualTo(ArrowReplacement(2, "→", "->"))
        assertThat(TypingShortcuts.arrow("x<-")).isEqualTo(ArrowReplacement(2, "←", "<-"))
        assertThat(TypingShortcuts.arrow("x<->")).isEqualTo(ArrowReplacement(3, "↔", "<->"))
        assertThat(TypingShortcuts.arrow("x←>")).isEqualTo(ArrowReplacement(2, "↔", "<->"))
        assertThat(TypingShortcuts.arrow("x=>")).isEqualTo(ArrowReplacement(2, "⇒", "=>"))
        assertThat(TypingShortcuts.arrow("x<=>")).isEqualTo(ArrowReplacement(3, "⇔", "<=>"))

        // Overlapping prefixes: ==> converts last two chars =>, --> converts last two chars ->
        assertThat(TypingShortcuts.arrow("==>")).isEqualTo(ArrowReplacement(2, "⇒", "=>"))
        assertThat(TypingShortcuts.arrow("-->")).isEqualTo(ArrowReplacement(2, "→", "->"))
    }

    @Test fun arrowNonConversionsAreIgnored() {
        assertThat(TypingShortcuts.arrow("--")).isNull()
        assertThat(TypingShortcuts.arrow("<=")).isNull()
        assertThat(TypingShortcuts.arrow(">=")).isNull()
        assertThat(TypingShortcuts.arrow("<--")).isNull()
        assertThat(TypingShortcuts.arrow("foo<--")).isNull()
        assertThat(TypingShortcuts.arrow("a-")).isNull()
        assertThat(TypingShortcuts.arrow("b>")).isNull()
        assertThat(TypingShortcuts.arrow("")).isNull()
        assertThat(TypingShortcuts.arrow("-")).isNull()
        assertThat(TypingShortcuts.arrow(">")).isNull()
        assertThat(TypingShortcuts.arrow("=")).isNull()
    }

    @Test fun urlsDoNotConvertArrows() {
        assertThat(TypingShortcuts.arrow("http://a.b/c->")).isNull()
        assertThat(TypingShortcuts.arrow("https://example.com/foo->")).isNull()
        assertThat(TypingShortcuts.arrow("www.x.com/->")).isNull()
        assertThat(TypingShortcuts.arrow("see http://a.b/c->")).isNull()
        assertThat(TypingShortcuts.arrow("visit www.x.com/->")).isNull()
        assertThat(TypingShortcuts.arrow("ftp://example.com/a<-")).isNull()

        // After whitespace, arrow is outside the URL token
        assertThat(TypingShortcuts.arrow("http://example.com ->")).isEqualTo(ArrowReplacement(2, "→", "->"))
        assertThat(TypingShortcuts.arrow("www.example.com ->")).isEqualTo(ArrowReplacement(2, "→", "->"))
    }
}
