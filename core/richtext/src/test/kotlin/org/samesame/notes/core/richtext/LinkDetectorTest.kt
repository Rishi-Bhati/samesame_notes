package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinkDetectorTest {
    private fun targets(s: String) = LinkDetector.find(s).map { it.target }

    private fun addresses(s: String) = LinkDetector.find(s)
        .filter { it.kind == TextLink.Kind.Address }
        .map { s.substring(it.start, it.end) }

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
    fun detectsStreetAddresses() {
        assertThat(addresses("221B Baker Street, London NW1 6XE")).containsExactly("221B Baker Street, London NW1 6XE")
        assertThat(addresses("12 MG Road, Bengaluru 560001")).containsExactly("12 MG Road, Bengaluru 560001")
        assertThat(addresses("1600 Amphitheatre Pkwy")).containsExactly("1600 Amphitheatre Pkwy")
        assertThat(addresses("Flat 4, 21 Park Avenue, Mumbai 400001"))
            .containsExactly("Flat 4, 21 Park Avenue, Mumbai 400001")
        assertThat(addresses("350 Fifth Avenue, New York, NY 10118"))
            .containsExactly("350 Fifth Avenue, New York, NY 10118")
    }

    @Test
    fun addressTargetOpensMaps() {
        val link = LinkDetector.find("221B Baker Street, London NW1 6XE").single()
        assertThat(link.kind).isEqualTo(TextLink.Kind.Address)
        assertThat(link.target).isEqualTo("geo:0,0?q=221B+Baker+Street%2C+London+NW1+6XE")
    }

    @Test
    fun houseNumberFormsAreKept() {
        assertThat(addresses("12/4 Baker Street")).containsExactly("12/4 Baker Street")
        assertThat(addresses("Flat 3 Baker Street")).containsExactly("Flat 3 Baker Street")
        assertThat(addresses("221b baker street")).containsExactly("221b baker street")
    }

    @Test
    fun plainNumbersAndSentencesAreNotAddresses() {
        val text = "Room 101\nDay 2\nMilk 60\nRent 12000\nSt. John said 3 things\nChapter 12 Drive safely"
        assertThat(addresses(text)).isEmpty()
    }

    @Test
    fun amountsVersionsDatesAndTimesAreNotAddresses() {
        val text = "₹1,00,000 $50 2k 1.2.3 2026-11-18 12/11/2026 7:30"
        assertThat(addresses(text)).isEmpty()
    }

    @Test
    fun phoneNextToAnAddressOnAnotherLineStaysAPhone() {
        val text = "call +44 20 7946 0958\n221B Baker Street, London NW1 6XE"
        val links = LinkDetector.find(text)
        assertThat(links.filter { it.kind == TextLink.Kind.Phone }).hasSize(1)
        assertThat(links.filter { it.kind == TextLink.Kind.Address }).hasSize(1)
        val address = links.first { it.kind == TextLink.Kind.Address }
        assertThat(text.substring(address.start, address.end)).isEqualTo("221B Baker Street, London NW1 6XE")
    }

    @Test
    fun addressNeverCrossesANewline() {
        val text = "12 MG Road\nBengaluru 560001"
        assertThat(addresses(text)).containsExactly("12 MG Road")
    }

    @Test
    fun unicodeTextIsNotMangled() {
        val text = "café 会议 at 5:30 pm — موعد 12/03/2026"
        val links = LinkDetector.find(text)
        assertThat(links.map { it.kind }).containsExactly(TextLink.Kind.Time, TextLink.Kind.Date).inOrder()
        assertThat(text.substring(links[0].start, links[0].end)).isEqualTo("5:30 pm")
    }
}
