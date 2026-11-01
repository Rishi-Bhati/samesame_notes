package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinkDetectorTest {
    private fun targets(s: String) = LinkDetector.find(s).map { it.target }

    @Test
    fun findsUrlsAndAddsSchemeToWww() {
        assertThat(targets("see https://example.com/a?b=1 and www.example.org")).containsExactly(
            "https://example.com/a?b=1", "https://www.example.org",
        ).inOrder()
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheLink() {
        val links = LinkDetector.find("(go to https://example.com/page.)")
        assertThat(links).hasSize(1)
        assertThat("(go to https://example.com/page.)".substring(links[0].start, links[0].end)).isEqualTo("https://example.com/page")
    }

    @Test
    fun findsEmailAndPhone() {
        assertThat(targets("mail me@example.com or call +1 (555) 123-4567")).containsExactly(
            "mailto:me@example.com", "tel:+15551234567",
        ).inOrder()
    }

    @Test
    fun findsSpacedInternationalPhone() {
        assertThat(targets("call +1 555 123 4567 now")).containsExactly("tel:+15551234567")
        assertThat(targets("+1 555 123 4567")).containsExactly("tel:+15551234567")
        val t = "link https://example.com/page me@example.com +1 555 123 4567 and 3 November 2026 at 5:30 pm"
        val phone = LinkDetector.find(t).first { it.kind == TextLink.Kind.Phone }
        assertThat(t.substring(phone.start, phone.end)).isEqualTo("+1 555 123 4567")
    }

    @Test
    fun emailInsideUrlIsNotDoubleCounted() {
        assertThat(LinkDetector.find("https://user@example.com/x")).hasSize(1)
    }

    @Test
    fun plainNumbersAndTextAreNotLinks() {
        assertThat(LinkDetector.find("no links here, 12345 or 1/2 or 1.2.3.4")).isEmpty()
    }

    @Test
    fun detectsNumericDatesDayFirstAndYearFirst() {
        assertThat(LinkDetector.find("due 12/03/2026 and 2026-11-03").map { it.kind })
            .containsExactly(TextLink.Kind.Date, TextLink.Kind.Date).inOrder()
        assertThat(LinkDetector.find("on 31.02.2026").filter { it.kind == TextLink.Kind.Date }).isEmpty()
    }

    @Test
    fun detectsMonthNameDates() {
        val links = LinkDetector.find("meet 3 Nov, then Nov 5 and 21st March 2027")
        assertThat(links.map { it.kind }).containsExactly(
            TextLink.Kind.Date, TextLink.Kind.Date, TextLink.Kind.Date,
        ).inOrder()
        assertThat(links.map { it.target }).containsExactly("3 Nov", "Nov 5", "21st March 2027").inOrder()
    }

    @Test
    fun detectsTimes() {
        val links = LinkDetector.find("at 5:30 pm, 17:45 or 9am")
        assertThat(links.map { it.kind }).containsExactly(
            TextLink.Kind.Time, TextLink.Kind.Time, TextLink.Kind.Time,
        ).inOrder()
        assertThat(LinkDetector.find("version 1:23:45 build 99:99").filter { it.kind == TextLink.Kind.Time })
            .hasSize(1)
    }

    @Test
    fun datesAndTimesAreNotPhoneNumbers() {
        assertThat(LinkDetector.find("2026-11-03 12/03/2026 at 5:30").none { it.kind == TextLink.Kind.Phone }).isTrue()
    }

    @Test
    fun linksAreSortedAndDoNotOverlap() {
        val text = "www.example.com 12/03/2026 5:30 pm me@example.com +44 20 7946 0958"
        val links = LinkDetector.find(text)
        assertThat(links.map { it.start }).isInOrder()
        links.zipWithNext().forEach { (a, b) -> assertThat(a.end <= b.start).isTrue() }
    }

    @Test
    fun longTextWithoutLinksIsHandledQuickly() {
        val text = "lorem ipsum ".repeat(20000)
        val started = System.nanoTime()
        assertThat(LinkDetector.find(text)).isEmpty()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertThat(millis).isLessThan(2000L)
    }

    @Test
    fun unicodeTextIsNotMangled() {
        val text = "café 会议 at 5:30 pm — موعد 12/03/2026"
        val links = LinkDetector.find(text)
        assertThat(links.map { it.kind }).containsExactly(TextLink.Kind.Time, TextLink.Kind.Date).inOrder()
        assertThat(text.substring(links[0].start, links[0].end)).isEqualTo("5:30 pm")
    }
}
