package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.richtext.edit.LinkTyping

class FileLinkDetectionTest {
    private val id = "11111111-2222-4333-8444-555555555555"
    private val uri = "samesame://file/$id?n=a%20b.pdf"

    @Test
    fun rawFileLinkIsOneLinkAndItsDigitsAreNoPhoneOrDate() {
        val text = "see $uri."
        val links = LinkDetector.find(text)
        assertThat(links).hasSize(1)
        assertThat(links[0].kind).isEqualTo(TextLink.Kind.FileLink)
        assertThat(links[0].target).isEqualTo(uri)
        assertThat(text.substring(links[0].start, links[0].end)).isEqualTo(uri)
        assertThat(LinkDetector.findFileLinks("($uri)").single().target).isEqualTo(uri)
        assertThat(LinkDetector.findFileLinks("no links 1111-2222")).isEmpty()
    }

    @Test
    fun storedRunsJoinPerUriAndKnowTheirKind() {
        val other = "https://example.com"
        val rt = RichText(
            "ab cd ef",
            listOf(
                TextRun(1, CharStyle(link = uri)), TextRun(1, CharStyle(bold = true, link = uri)),
                TextRun(1, CharStyle()), TextRun(2, CharStyle(link = other)), TextRun(3, CharStyle()),
            ),
        )
        val links = LinkDetector.explicitLinks(rt)
        assertThat(links).containsExactly(
            TextLink(0, 2, TextLink.Kind.FileLink, uri, explicit = true),
            TextLink(3, 5, TextLink.Kind.Url, other, explicit = true),
        ).inOrder()
        assertThat(LinkDetector.kindOfUri("mailto:a@b.c")).isEqualTo(TextLink.Kind.Email)
        assertThat(LinkDetector.kindOfUri("tel:+123")).isEqualTo(TextLink.Kind.Phone)
        // Two different links side by side stay two links.
        assertThat(LinkDetector.joinLinkRuns(listOf(Triple(0, 2, uri), Triple(2, 4, other)))).hasSize(2)
    }

    @Test
    fun linkTypingRule() {
        // Pure insertion: the caller chose the style already.
        assertThat(LinkTyping.linkedLength(uri, count = 0, after = 1, linkContinues = false)).isEqualTo(1)
        // Retyping the selected label keeps the link.
        assertThat(LinkTyping.linkedLength(uri, count = 5, after = 3, linkContinues = false)).isEqualTo(3)
        // A keyboard re-composing the last word of a link and appending: the appended part is plain.
        assertThat(LinkTyping.linkedLength(uri, count = 3, after = 4, linkContinues = false)).isEqualTo(3)
        // The same inside the link (more link follows): all of it stays in the link.
        assertThat(LinkTyping.linkedLength(uri, count = 3, after = 4, linkContinues = true)).isEqualTo(4)
        assertThat(LinkTyping.linkedLength(null, count = 3, after = 4, linkContinues = false)).isEqualTo(4)
    }
}
