package org.samesame.notes.core.richtext.edit.math

import org.samesame.notes.core.model.Limits

/** The inline result to draw after the `=` at [anchor]: [text] is what acceptance inserts (leading space included). */
data class MathSuggestion(val anchor: Int, val text: String)

/**
 * Recognises a finished expression behind the caret's `=` and computes its result, without ever touching the text.
 *
 * The trigger is strict on purpose (F6): a trail of `=` after an arithmetic expression, a unit expression, or
 * `sum` / `total` / `avg` with numbers above. Dates, times, phone numbers, version strings, list markers,
 * assignments and bare numbers or quantities never produce a suggestion.
 */
object MathDetector {
    private val AGGREGATES = setOf("sum", "total", "avg", "average")

    private val dateIso = Regex("""^\d{4}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2}$""")
    private val dateShort = Regex("""^\d{1,2}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{2,4}$""")
    /** "17/11", "2026-11": read as a date when a side can only be a day, month or year (never "1/2"). */
    private val dateDayMonth = Regex("""^(\d{1,4})\s*[/-]\s*(\d{1,2})$""")
    private val version = Regex("""^\d+(?:\.\d+){2,}$""")
    private val timeOfDay = Regex("""\d{1,2}:\d{2}""")
    private val phoneDashed = Regex("""^\d{3}-\d{4}$""")
    private val phoneGrouped = Regex("""^\+?\d{1,4}(?:[ -]\d{2,4}){2,}$""")
    private val phoneParen = Regex("""^\(\d{2,4}\)\s*\d{3,4}(?:[ -]\d{3,4})?$""")
    private val listMarker = Regex("""^\s*(?:[-*\u2022]|\d{1,3}[.)])\s""")

    /**
     * The suggestion for [text] with the caret at [caret], or null. Only the current line before the caret is read
     * (except for `sum` / `avg`, which read the lines directly above).
     */
    fun suggest(text: CharSequence, caret: Int, config: MathConfig = MathConfig.default()): MathSuggestion? {
        val end = caret.coerceIn(0, text.length)
        var lineStart = end
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val prefix = text.subSequence(lineStart, end).toString()
        if (prefix.length > Limits.MATH_MAX_EXPRESSION_LENGTH) return null

        val trimmed = prefix.trimEnd(' ', '\t')
        if (!trimmed.endsWith('=')) return null
        if (trimmed.length >= 2) {
            val before = trimmed[trimmed.length - 2]
            if (before == '=' || before == '!' || before == '<' || before == '>') return null
        }
        val body = trimmed.dropLast(1).trim()
        if (body.isEmpty() || body.length > Limits.MATH_MAX_EXPRESSION_LENGTH) return null
        if (body.any { it == '=' }) return null
        if (listMarker.containsMatchIn(prefix)) return null
        if (!plausible(body)) return null

        val result = if (body.lowercase() in AGGREGATES) {
            val lines = precedingLines(text, lineStart)
            val average = body.lowercase().let { it == "avg" || it == "average" }
            MathEvaluator.aggregate(lines, average, config)
        } else {
            val conversion = splitConversion(body)
            val converted = conversion?.let { (source, target) -> MathEvaluator.convert(source, target, config) }
            converted ?: MathEvaluator.evaluate(body, config)
        } ?: return null
        // A lone number or quantity is not a calculation: "5 =" and "5 km =" stay quiet.
        if (result.ops == 0) return null

        val lead = if (prefix.length == trimmed.length) " " else ""
        return MathSuggestion(end, lead + result.text)
    }

    /**
     * "Calculate" in the selection menu: the text to put in place of [selected] (`5 + 5` or `5 + 5 =` become
     * `5 + 5 = 10`), or null when the selection is not a single calculable expression.
     */
    fun calculateSelection(selected: String, config: MathConfig = MathConfig.default()): String? {
        val text = selected.trim()
        if (text.isEmpty() || text.contains('\n')) return null
        val body = text.removeSuffix("=").trimEnd()
        if (body.isEmpty() || body.any { it == '=' }) return null
        if (!plausible(body)) return null
        val result = splitConversion(body)
            ?.let { (source, target) -> MathEvaluator.convert(source, target, config) }
            ?: MathEvaluator.evaluate(body, config)
        if (result == null || result.ops == 0) return null
        return "$body = ${result.text}"
    }

    /** False-trigger guards shared by the typing trigger and the selection menu. */
    private fun plausible(body: String): Boolean {
        if (dateIso.matches(body) || dateShort.matches(body)) return false
        val dayMonth = dateDayMonth.matchEntire(body)
        if (dayMonth != null) {
            val first = dayMonth.groupValues[1].toIntOrNull() ?: 0
            val second = dayMonth.groupValues[2].toIntOrNull() ?: 0
            if (first > 12 || second > 12) return false
        }
        if (version.matches(body)) return false
        if (timeOfDay.containsMatchIn(body)) return false
        if (phoneDashed.matches(body) || phoneGrouped.matches(body) || phoneParen.matches(body)) return false
        return true
    }

    /** The contiguous block of numeric lines directly above the current line, newest first (at most the cap). */
    private fun precedingLines(text: CharSequence, lineStart: Int): List<String> {
        if (lineStart <= 0) return emptyList()
        val before = text.subSequence(0, lineStart - 1).toString()
        if (before.isEmpty()) return emptyList()
        val lines = before.split('\n')
        val collected = ArrayList<String>()
        for (line in lines.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) break
            if (!startsLikeNumber(trimmed)) break
            collected += trimmed
            if (collected.size >= Limits.MATH_MAX_AGGREGATE_LINES) break
        }
        return collected
    }

    private fun startsLikeNumber(line: String): Boolean {
        val first = line.firstOrNull() ?: return false
        if (first.isDigit()) return true
        if (first != '-' && first != '+' && first != '.') return false
        return line.length > 1 && line[1].isDigit()
    }

    /**
     * `5 km in m`, `100 F to C`, `90 min as h`: the last conversion word outside parentheses whose right side is a
     * unit alone. Returns null when there is none (a unit `in` such as "5 in + 2 in" then simply does not split).
     */
    private fun splitConversion(expression: String): Pair<String, String>? {
        for (keyword in listOf(" to ", " as ", " in ")) {
            var from = expression.length
            while (from > 0) {
                val at = expression.lastIndexOf(keyword, from - 1, ignoreCase = true)
                if (at < 0) break
                if (depthAt(expression, at) == 0) {
                    val source = expression.substring(0, at).trim()
                    val target = expression.substring(at + keyword.length).trim()
                    if (source.isNotEmpty() && target.isNotEmpty() && MathUnits.parseUnit(target) != null) {
                        return source to target
                    }
                }
                from = at
            }
        }
        return null
    }

    private fun depthAt(text: String, index: Int): Int {
        var depth = 0
        for (i in 0 until index) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        return depth
    }
}
