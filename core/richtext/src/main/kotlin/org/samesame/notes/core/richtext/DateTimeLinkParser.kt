package org.samesame.notes.core.richtext

/** Fields parsed from a date/time link. Missing parts are filled in by the caller (today / current year). */
data class ParsedDateTime(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
)

/**
 * Turns the raw text of a detected date/time link into calendar fields for the "insert event" intent.
 * Only the formats produced by [LinkDetector] are accepted; anything else is rejected.
 */
object DateTimeLinkParser {
    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9, "oct" to 10,
        "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )
    private val yearToken = Regex("""\b(19|20)\d{2}\b|\b\d{2}\b""")
    private val dayToken = Regex("""(?<![\d])(\d{1,2})(?![\d])""")
    private val timeToken = Regex("""(?<![\w:])(\d{1,2})(?::(\d{2}))?\s?(am|pm)?(?![\w])""", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("""^(\d{1,4})[./-](\d{1,2})[./-](\d{1,4})$""")

    fun parse(text: String): ParsedDateTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return parseNumericDate(trimmed) ?: parseMonthDate(trimmed) ?: parseTime(trimmed)
    }

    private fun parseNumericDate(text: String): ParsedDateTime? {
        val m = numericDate.matchEntire(text) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val c = m.groupValues[3].toInt()
        val month = b
        if (month !in 1..12) return null
        return if (m.groupValues[1].length >= 3) {
            val year = a
            val day = c
            if (!valid(year, month, day)) null else ParsedDateTime(year = year, month = month, day = day)
        } else {
            val day = a
            val year = if (m.groupValues[3].length <= 2) 2000 + c else c
            if (!valid(year, month, day)) null else ParsedDateTime(year = year, month = month, day = day)
        }
    }

    private fun parseMonthDate(text: String): ParsedDateTime? {
        val words = text.replace(",", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val monthIndex = words.indexOfFirst { clean(it).lowercase() in months }
        if (monthIndex < 0) return null
        val month = months[clean(words[monthIndex]).lowercase()] ?: return null
        val rest = words.filterIndexed { i, _ -> i != monthIndex }.map { clean(it) }
        val day = rest.firstOrNull { it.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull() in 1..31 }
            ?.removeSuffix("st")?.removeSuffix("nd")?.removeSuffix("rd")?.removeSuffix("th")?.toIntOrNull() ?: return null
        var year = rest.firstOrNull { yearToken.matches(it) && it.toIntOrNull() != null && it != day.toString() }?.toIntOrNull()
        if (year != null && year < 100) year += 2000
        if (!valid(year ?: 2024, month, day)) return null
        return ParsedDateTime(year = year, month = month, day = day)
    }

    private fun parseTime(text: String): ParsedDateTime? {
        val m = timeToken.matchEntire(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val suffix = m.groupValues[3].lowercase()
        if (minute !in 0..59) return null
        if (suffix == "pm" && hour in 1..11) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        if (hour !in 0..23) return null
        return ParsedDateTime(hour = hour, minute = minute)
    }

    private fun valid(year: Int, month: Int, day: Int): Boolean {
        if (year !in 1900..2099 || month !in 1..12 || day < 1) return false
        val max = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= max
    }

    private fun clean(word: String): String = word.trim().trimEnd('.', ',')
}
