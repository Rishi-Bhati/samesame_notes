package org.samesame.notes.core.richtext

import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.richtext.RichText

/**
 * A link-like run in body text: web address, e-mail, phone number, date, time, street address or library file. [start]
 * inclusive, [end] exclusive. [explicit] is true for a stored link run (`CharStyle.link`, format v8): its text is a
 * label and [target] the URI; detected links ([LinkDetector.find]) are derived from the text itself.
 */
data class TextLink(val start: Int, val end: Int, val kind: Kind, val target: String, val explicit: Boolean = false) {
    enum class Kind { Url, Email, Phone, Date, Time, FileLink, Address }
}

/**
 * Finds links in plain text for the "Show links in notes" setting. Pure Kotlin (no `android.util.Patterns`), so it is
 * JVM-testable. Nothing is stored in the note: links are derived from the text each time it changes.
 *
 * Rules mirror the reference app's linkify: web addresses, e-mail, phone numbers (at least nine digits, no two
 * separators in a row), numeric dates in day-first or year-first order, month-name dates and times.
 */
object LinkDetector {
    private val url = Regex("""(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<![\w+])\+?\d[\d ().\-]{5,18}\d(?![\w])""")
    private val numericDate = Regex(
        """(?<![\w./-])(\d{1,4})([./-])(\d{1,2})\2(\d{1,4})(?![\w./-])""",
    )
    private val month = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|" +
        "Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?"
    private val dayMonth = Regex("""(?<![\w])(\d{1,2})(?:st|nd|rd|th)?\s+($month)(?:,?\s+(\d{2,4}))?(?![\w])""", RegexOption.IGNORE_CASE)
    private val monthDay = Regex("""(?<![\w])($month)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{2,4}))?(?![\w])""", RegexOption.IGNORE_CASE)
    private val time = Regex(
        """(?<![\w:])(?:(?:[01]?\d|2[0-3]):[0-5]\d(?:\s?(?:am|pm))?|(?:1[0-2]|0?[1-9])\s?(?:am|pm))(?![\w])""",
        RegexOption.IGNORE_CASE,
    )
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")

    // Street addresses are the most false-positive-prone kind, so the rules are deliberately narrow: a house number
    // close before a street word, and the street word ends the candidate (or is followed by a comma or a postal code).
    private val streetWord = Regex(
        """(?<![\p{L}\d])(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Boulevard|Blvd|Drive|Dr|Way|Court|Ct|Place|Pl|""" +
            """Square|Sq|Marg|Nagar|Colony|Sector|Highway|Hwy|Terrace|Close|Crescent|Parkway|Pkwy)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )
    private val houseNumber = Regex(
        """(?<![\p{L}\d.,/\\₹$€£-])\d{1,5}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?(?![\p{L}\d.,/\\-])""",
    )
    private val flatWithNumber = Regex(
        """\b(?:Flat|Unit|Apartment|Apt|Suite|House|Plot)\s+\d{1,5}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?\s*,?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val flatWord = Regex("""\b(?:Flat|Unit|Apartment|Apt|Suite|House|Plot)\s+$""", RegexOption.IGNORE_CASE)
    private val words = Regex("""[\p{L}\d]+""")
    private val postalCode = Regex(
        """^(?:\d{5}(?:-\d{4})?|\d{6}|[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2})(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )
    private const val TRAILING = ".,;:!?)]}'\""

    fun find(text: CharSequence): List<TextLink> {
        val found = ArrayList<TextLink>()
        fun overlaps(s: Int, e: Int) = found.any { s < it.end && e > it.start }
        fun add(s: Int, e: Int, kind: TextLink.Kind, target: String) {
            if (e > s && !overlaps(s, e)) found += TextLink(s, e, kind, target)
        }

        // First, so the digits of a file id are never taken for a phone number or a date.
        found += findFileLinks(text)

        for (m in url.findAll(text)) {
            var end = m.range.last + 1
            while (end > m.range.first && text[end - 1] in TRAILING) end--
            val raw = text.substring(m.range.first, end)
            if (raw.length <= 4 || raw.endsWith("://")) continue
            val target = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            add(m.range.first, end, TextLink.Kind.Url, target)
        }
        for (m in email.findAll(text)) {
            add(m.range.first, m.range.last + 1, TextLink.Kind.Email, "mailto:" + m.value)
        }
        for (m in numericDate.findAll(text)) {
            val a = m.groupValues[1]
            val month = m.groupValues[3]
            val c = m.groupValues[4]
            if (isValidNumericDate(a, month, c)) add(m.range.first, m.range.last + 1, TextLink.Kind.Date, m.value)
        }
        for (m in dayMonth.findAll(text)) {
            if (!overlaps(m.range.first, m.range.last + 1)) {
                add(m.range.first, m.range.last + 1, TextLink.Kind.Date, m.value)
            }
        }
        for (m in monthDay.findAll(text)) {
            if (!overlaps(m.range.first, m.range.last + 1)) {
                add(m.range.first, m.range.last + 1, TextLink.Kind.Date, m.value)
            }
        }
        for (m in time.findAll(text)) {
            if (!overlaps(m.range.first, m.range.last + 1)) {
                add(m.range.first, m.range.last + 1, TextLink.Kind.Time, m.value)
            }
        }
        for (m in phone.findAll(text)) {
            val s = m.range.first
            val e = m.range.last + 1
            val digits = m.value.count { it.isDigit() }
            if (digits < 9 || digits > 15 || isoDate.matches(m.value)) continue
            add(s, e, TextLink.Kind.Phone, "tel:" + m.value.filter { it.isDigit() || it == '+' })
        }
        // Last: an address is the loosest shape, so a phone number, date or time inside the same words wins.
        for (link in findAddresses(text)) add(link.start, link.end, link.kind, link.target)
        return found.sortedBy { it.start }
    }

    /** Street addresses, one line at a time (a candidate never crosses a newline). */
    private fun findAddresses(text: CharSequence): List<TextLink> {
        val out = ArrayList<TextLink>()
        var lineStart = 0
        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val line = text.subSequence(lineStart, lineEnd)
            val range = addressInLine(line)
            if (range != null) {
                val (s, e) = range
                val raw = line.substring(s, e)
                out += TextLink(
                    lineStart + s, lineStart + e, TextLink.Kind.Address,
                    "geo:0,0?q=" + java.net.URLEncoder.encode(raw, "UTF-8"),
                )
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    /**
     * The `[start, end)` of the first address in [line], or null. A house number (optionally with a flat/unit prefix)
     * must sit within four words before a street word, and the street word must end the line or be followed by a comma
     * or a postal code; only then are the comma parts taken as the rest of the address.
     */
    private fun addressInLine(line: CharSequence): Pair<Int, Int>? {
        for (street in streetWord.findAll(line)) {
            val streetStart = street.range.first
            val number = houseNumber.findAll(line.subSequence(0, streetStart)).lastOrNull() ?: continue
            val between = line.subSequence(number.range.last + 1, streetStart)
            if (words.findAll(between).count() > 3) continue
            if (!addressEndsAfterStreet(line, street.range.last + 1)) continue

            var start = number.range.first
            val before = line.subSequence(0, start)
            flatWithNumber.find(before)?.takeIf { it.range.last + 1 == start }?.let { start = it.range.first }
            flatWord.find(before)?.takeIf { it.range.last + 1 == start }?.let { start = it.range.first }

            var end = line.length
            while (end > start && (line[end - 1].isWhitespace() || line[end - 1] in TRAILING)) end--
            if (end > start) return start to end
        }
        return null
    }

    /** True when the text after a street word is a line end, a comma part or a postal code (and nothing else). */
    private fun addressEndsAfterStreet(line: CharSequence, from: Int): Boolean {
        val rest = line.subSequence(from, line.length).toString().trim()
        if (rest.isEmpty() || rest.startsWith(",")) return true
        if (rest.trimStart('.').isEmpty()) return true
        val postal = postalCode.find(rest) ?: return false
        if (postal.range.first != 0) return false
        val after = rest.substring(postal.range.last + 1).trim()
        return after.isEmpty() || after.startsWith(",")
    }

    /**
     * Raw library file links (`samesame://file/<id>...`) typed or pasted as plain text, so they stay tappable without
     * the paste interception. Trailing sentence punctuation is not part of the link. Found whatever the "Show links"
     * setting: they are the app's own links, not smart detection.
     */
    fun findFileLinks(text: CharSequence): List<TextLink> {
        if (!text.contains("://")) return emptyList()
        val out = ArrayList<TextLink>()
        for (m in FileLinks.PATTERN.findAll(text)) {
            var end = m.range.last + 1
            while (end > m.range.first && text[end - 1] in TRAILING) end--
            out += TextLink(m.range.first, end, TextLink.Kind.FileLink, text.substring(m.range.first, end))
        }
        return out
    }

    /** The kind a stored link URI ([TextLink.explicit]) opens as. */
    fun kindOfUri(uri: String): TextLink.Kind = when {
        FileLinks.parse(uri) != null -> TextLink.Kind.FileLink
        uri.startsWith("mailto:", ignoreCase = true) -> TextLink.Kind.Email
        uri.startsWith("tel:", ignoreCase = true) -> TextLink.Kind.Phone
        else -> TextLink.Kind.Url
    }

    /**
     * Joins character ranges `[start, end)` carrying a link URI (one per styled run, in text order) into links: adjacent
     * runs with the same URI (a link whose label is partly bold, say) are one link.
     */
    fun joinLinkRuns(runs: List<Triple<Int, Int, String>>): List<TextLink> {
        val out = ArrayList<TextLink>()
        for ((s, e, uri) in runs.sortedBy { it.first }) {
            if (e <= s) continue
            val last = out.lastOrNull()
            if (last != null && last.target == uri && last.end == s) out[out.lastIndex] = last.copy(end = e)
            else out += TextLink(s, e, kindOfUri(uri), uri, explicit = true)
        }
        return out
    }

    /** The stored link runs of [rt] (`CharStyle.link`), joined as by [joinLinkRuns]. */
    fun explicitLinks(rt: RichText): List<TextLink> {
        val runs = ArrayList<Triple<Int, Int, String>>()
        var pos = 0
        for (run in rt.runs) {
            run.style.link?.let { runs += Triple(pos, pos + run.length, it) }
            pos += run.length
        }
        return joinLinkRuns(runs)
    }

    /** `12/03/2026`, `3-11-26` and `2026.11.03` are dates; `1/2` alone is not. */
    private fun isValidNumericDate(a: String, b: String, c: String): Boolean {
        val first = a.toIntOrNull() ?: return false
        val month = b.toIntOrNull() ?: return false
        val third = c.toIntOrNull() ?: return false
        if (month !in 1..12) return false
        val day: Int
        val year: Int
        if (a.length >= 3) {
            year = first
            day = third
        } else {
            day = first
            year = if (c.length <= 2) 2000 + third else third
        }
        if (day !in 1..31) return false
        if (year !in 1900..2099) return false
        return day <= daysInMonth(year, month)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

}
