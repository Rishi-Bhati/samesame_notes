package org.samesame.notes.core.richtext

/** A link-like run in body text: web address, e-mail, phone number, date or time. [start] inclusive, [end] exclusive. */
data class TextLink(val start: Int, val end: Int, val kind: Kind, val target: String) {
    enum class Kind { Url, Email, Phone, Date, Time }
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
    private const val TRAILING = ".,;:!?)]}'\""

    fun find(text: CharSequence): List<TextLink> {
        val found = ArrayList<TextLink>()
        fun overlaps(s: Int, e: Int) = found.any { s < it.end && e > it.start }
        fun add(s: Int, e: Int, kind: TextLink.Kind, target: String) {
            if (e > s && !overlaps(s, e)) found += TextLink(s, e, kind, target)
        }

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
        return found.sortedBy { it.start }
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
