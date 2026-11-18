package org.samesame.notes.core.richtext.edit.math

import org.samesame.notes.core.model.Limits

/** The inline result at [anchor]; [text] is inserted on acceptance, including any leading space. */
data class MathSuggestion(
    val anchor: Int,
    val text: String,
    /** Counted amounts as inclusive body offsets; empty for an ordinary expression. */
    val countedRanges: List<IntRange> = emptyList(),
    /** Enter accepts only keyword totals, including `Total =`. */
    val keywordLine: Boolean = false,
)

/**
 * Recognises a finished expression behind the caret's `=` and computes its result, without ever touching the text.
 *
 * Expressions require a trailing `=`. Standalone total keywords read the item block above without one.
 * Dates, times, phone numbers, versions, assignments and bare quantities never trigger on their own.
 */
object MathDetector {
    private val keyword = Regex("""^\s*(grand\s+total|subtotal|average|total|sum|avg|count|max|min)\s*[:=]?\s*$""", RegexOption.IGNORE_CASE)
    private val resultLine = Regex("""^\s*(grand\s+total|subtotal|average|total|sum|avg|count|max|min)(?:\s*[:=]\s*|\s+)(.+)$""", RegexOption.IGNORE_CASE)
    private val heading = Regex("""^(?:#{1,6}\s+.*|[-=]{3,}|\*\*[^*]+\*\*)$""")

    private fun kind(word: String): AggregateKind = when (word.lowercase().replace(Regex("""\s+"""), " ")) {
        "avg", "average" -> AggregateKind.Average
        "count" -> AggregateKind.Count
        "max" -> AggregateKind.Max
        "min" -> AggregateKind.Min
        "subtotal" -> AggregateKind.Subtotal
        "grand total" -> AggregateKind.GrandTotal
        else -> AggregateKind.Total
    }

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
     * (except for total keywords, which read the item block directly above).
     */
    fun suggest(text: CharSequence, caret: Int, config: MathConfig = MathConfig.default()): MathSuggestion? {
        val end = caret.coerceIn(0, text.length)
        var lineStart = end
        while (lineStart > 0 && text[lineStart - 1] != '\n') {
            lineStart--
            if (end - lineStart > Limits.MATH_MAX_EXPRESSION_LENGTH) return null
        }
        val prefix = text.subSequence(lineStart, end).toString()

        val trimmed = prefix.trimEnd(' ', '\t')
        val keywordMatch = keyword.matchEntire(prefix)
        if (keywordMatch != null) {
            // A keyword must occupy the whole line, even when the caret is before existing text.
            if (end < text.length && text[end] != '\n') return null
            val aggregateKind = kind(keywordMatch.groupValues[1])
            val amounts = precedingAmounts(text, lineStart, aggregateKind) ?: return null
            val result = MathEvaluator.aggregate(amounts, aggregateKind) ?: return null
            val lead = if (prefix.length == trimmed.length) " " else ""
            return MathSuggestion(end, lead + result.text, amounts.map { it.sourceRange }, keywordLine = true)
        }
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

        val conversion = splitConversion(body)
        val converted = conversion?.let { (source, target) -> MathEvaluator.convert(source, target, config) }
        val result = converted ?: MathEvaluator.evaluate(body, config) ?: return null
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

    /**
     * Reads at most 200 block lines backwards without copying the document prefix. Blank, colon-ended,
     * Markdown heading/rule and uppercase heading lines end a block; numberless notes are skipped.
     * Earlier keywords/results end normal totals. Grand total crosses accepted subtotals and uses only
     * their results when present. An unfinished subtotal, ambiguous counted amount or oversized block fails closed.
     */
    private fun precedingAmounts(text: CharSequence, lineStart: Int, aggregateKind: AggregateKind): List<Amount>? {
        val items = ArrayList<Amount>()
        val subtotals = ArrayList<Amount>()
        var end = lineStart - 1
        var read = 0
        var invalidItem = false
        while (end >= 0) {
            var start = end
            while (start > 0 && text[start - 1] != '\n') start--
            val line = text.subSequence(start, end).toString()
            val trimmed = line.trim()
            val standalone = keyword.matchEntire(line)
            val previous = resultLine.matchEntire(line)
            if (trimmed.isEmpty() || heading.matches(trimmed) ||
                (standalone == null && trimmed.any { it.isLetter() } && trimmed.none { it.isDigit() } && trimmed == trimmed.uppercase())
            ) break
            if (standalone != null) {
                if (aggregateKind == AggregateKind.GrandTotal && kind(standalone.groupValues[1]) == AggregateKind.Subtotal) return null
                break
            }
            if (previous != null && kind(previous.groupValues[1]) != AggregateKind.Subtotal) break
            if (previous != null && aggregateKind != AggregateKind.GrandTotal) break
            if (trimmed.endsWith(':')) break
            if (read >= Limits.MATH_BLOCK_MAX_LINES) return null
            read++
            if (previous != null) {
                val result = previous.groups[2] ?: return null
                val amount = LineAmount.parse(result.value) ?: return null
                if (amount.sourceRange.first != result.value.indexOfFirst { !it.isWhitespace() } ||
                    amount.sourceRange.last != result.value.indexOfLast { !it.isWhitespace() }
                ) return null
                subtotals += amount.copy(sourceRange =
                    (start + result.range.first + amount.sourceRange.first)..(start + result.range.first + amount.sourceRange.last))
            } else {
                when (val parsed = LineAmount.read(line)) {
                    is LineAmount.Read.Parsed -> items += parsed.amount.copy(sourceRange =
                        (start + parsed.amount.sourceRange.first)..(start + parsed.amount.sourceRange.last))
                    LineAmount.Read.Invalid -> invalidItem = true
                    LineAmount.Read.Skipped -> Unit
                }
            }
            end = start - 1
        }
        if (subtotals.isEmpty() && invalidItem) return null
        return (if (subtotals.isNotEmpty()) subtotals else items).asReversed()
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
