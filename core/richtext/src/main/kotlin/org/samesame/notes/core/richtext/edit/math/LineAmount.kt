package org.samesame.notes.core.richtext.edit.math

import org.samesame.notes.core.model.Limits
import java.math.BigDecimal

enum class GroupingStyle { None, Western, Indian }

/** An exact amount and its `[first, last]` character range in the original item line. */
data class Amount(
    val value: BigDecimal,
    val currency: String? = null,
    val grouping: GroupingStyle = GroupingStyle.None,
    val unit: MathUnit? = null,
    val sourceRange: IntRange,
    val unitDisplay: String? = null,
)

/**
 * Reads an everyday item without guessing at malformed amounts. Typed list markers are excluded.
 * The last number-like token wins over earlier label numbers (`Day 2 500`); `Day 2` itself counts as 2.
 * Multiplication joins amount tokens, so `Eggs 2 x 72` counts as 144, not 72.
 * A minus with whitespace on both sides is a separator (`Rent - 12000`). A minus touching the
 * digits is a sign (`Refund -200`, `Refund: -200`, `-200 refund`). Currency may sit inside the sign.
 * Attached `k` means thousand and attached `L` means lakh; spaced `L` remains the volume unit.
 * In a sequence containing hours only, `m` means minutes; an ordinary `5 m` remains metres.
 */
object LineAmount {
    private val marker = Regex("""^\s*(?:[-*•]|\d+[.)]|\[[ xX✓]\]|[☐☑])\s+""")
    private val number = Regex("""\d[\d.,]*|\.\d[\d.,]*""")
    private val western = Regex("""\d{1,3}(?:,\d{3})+""")
    private val indian = Regex("""\d{1,2}(?:,\d{2})+,\d{3}""")
    private val currencyPrefix = Regex("""(?i)(?:\p{Sc}|\bRs\.?)\s*$""")
    private val currencies = Regex("""(?i)\p{Sc}|\bRs\.?(?=\s|\d|-)""")
    private val datePair = Regex("""(?<!\d)(\d{1,4})\s*[/-]\s*(\d{1,2})(?!\d)""")
    private val labelNumber = Regex("""(?i)\b(?:flat|room|day)\s*$""")
    private val groupedPhone = Regex("""\(?\d{2,4}\)?(?:[ -]\d{2,4}){2,}\b""")
    private val excluded = listOf(
        Regex("""\d{1,2}:\d{2}"""),
        Regex("""\d{4}\s*[-.]\s*\d{1,2}\s*[-.]\s*\d{1,2}"""),
        Regex("""\d{1,2}\s*[-.]\s*\d{1,2}\s*[-.]\s*\d{2,4}"""),
        Regex("""\d+(?:\.\d+){2,}"""),
        Regex("""\d\s*[%％]"""),
        Regex("""(?<![\d.,])\d{8,}(?![\d.,])"""),
        Regex("""(?<!\d)\d{3}-\d{4}(?!\d)"""),
    )

    internal sealed interface Read {
        data class Parsed(val amount: Amount) : Read
        data object Skipped : Read
        data object Invalid : Read
    }

    fun parse(line: String): Amount? = (read(line) as? Read.Parsed)?.amount

    private data class Token(
        val start: Int,
        val end: Int,
        val value: BigDecimal,
        val currency: String?,
        val grouping: GroupingStyle,
        val unit: MathUnit?,
        val display: String?,
    )

    internal fun read(line: String): Read {
        if (line.length > Limits.MATH_MAX_EXPRESSION_LENGTH) {
            return if (line.any { it.isDigit() }) Read.Invalid else Read.Skipped
        }
        var base = 0
        while (true) {
            val match = marker.find(line.substring(base)) ?: break
            base += match.value.length
        }
        val text = line.substring(base)
        if (excluded.any { it.containsMatchIn(text) } ||
            datePair.findAll(text).any {
                val first = it.groupValues[1].toInt()
                val second = it.groupValues[2].toInt()
                (first in 1000..9999 && second in 1..12) ||
                    (first in 1..31 && second in 1..12) ||
                    (first in 1..12 && second in 1..31)
            } || groupedPhone.findAll(text).any { it.value.count(Char::isDigit) >= 8 }
        ) return Read.Skipped
        val tokens = ArrayList<Token>()
        var consumed = 0
        for (match in number.findAll(text)) {
            if (match.range.first < consumed) continue
            val at = match.range.first
            var start = at
            var end = match.range.last + 1
            var negative = false
            if (start > 0 && text[start - 1] == '-') { start--; negative = true }
            val cur = currencyPrefix.find(text.substring(0, start))
            val currency = cur?.value?.trim()?.let { if (it.startsWith("Rs", true)) "₹" else it }
            if (cur != null) start = cur.range.first
            if (start > 0 && text[start - 1] == '-') {
                if (negative) return Read.Invalid
                start--
                negative = true
            }
            if (negative && start > 0 && text[start - 1].isDigit()) return Read.Invalid
            if (!negative && start > 0 && text[start - 1].isLetterOrDigit() &&
                !(start == consumed && tokens.lastOrNull()?.unit != null) &&
                !(text[start - 1] == 'x' && tokens.lastOrNull()?.let {
                    text.substring(it.end, start).trim() == "x"
                } == true)
            ) continue

            if (start > 0 && text[start - 1] == '-') return Read.Invalid
            if (labelNumber.containsMatchIn(text.substring(0, start)) &&
                text.getOrNull(end)?.isLetter() == true
            ) continue
            val raw = match.value
            val parts = raw.split('.')
            if (parts.size > 2 || parts.last().isEmpty()) return Read.Invalid
            val integer = parts.first()
            val grouping = when {
                ',' !in integer -> GroupingStyle.None
                indian.matches(integer) -> GroupingStyle.Indian
                western.matches(integer) -> GroupingStyle.Western
                else -> return Read.Invalid
            }
            if (parts.size == 2 && (parts[1].isEmpty() || parts[1].any { !it.isDigit() })) return Read.Invalid
            var value = raw.replace(",", "").toBigDecimalOrNull() ?: return Read.Invalid
            var unit: MathUnit? = null
            var display: String? = null
            val suffix = text.getOrNull(end)
            if ((suffix == 'k' || suffix == 'L') && text.getOrNull(end + 1)?.isLetterOrDigit() != true) {
                value = value.multiply(if (suffix == 'k') BigDecimal(1000) else BigDecimal(100000))
                end++
            } else {
                var unitAt = end
                while (text.getOrNull(unitAt)?.isWhitespace() == true) unitAt++
                val found = MathUnits.matchAt(text, unitAt)
                if (found != null) {
                    unit = found.unit
                    display = found.display
                    end = unitAt + found.length
                } else if (text.getOrNull(end)?.isLetter() == true &&
                    !(text[end] == 'x' && text.substring(end + 1).trimStart().firstOrNull()?.isDigit() == true)
                ) {
                    return Read.Invalid
                }
            }
            if (negative) value = value.negate()
            tokens += Token(start, end, value, currency, grouping, unit, display)
            consumed = end
        }
        if (tokens.isEmpty()) return Read.Skipped
        val allCurrencies = currencies.findAll(text).map {
            if (it.value.startsWith("Rs", true)) "₹" else it.value
        }.toSet()
        if (allCurrencies.size > 1) return Read.Invalid

        var first = tokens.lastIndex
        while (first > 0) {
            val left = tokens[first - 1]
            val right = tokens[first]
            val gap = text.substring(left.end, right.start)
            val adjacent = gap.isBlank() && left.unit != null && right.unit != null
            if (gap.trim() in setOf("*", "x", "×") || adjacent) {
                first--
            } else break
        }
        val selected = tokens.subList(first, tokens.size)
        val minutesAlias = selected.any {
            it.unit?.dimension == MathDimension.Time && it.unit.factor.compareTo(BigDecimal(3600)) == 0
        } && selected.all { it.unit == null || it.unit.dimension == MathDimension.Time || it.display == "m" }
        val head = text.substring(0, selected.first().start).trimEnd()
        val tail = text.substring(selected.last().end).trimStart()
        // Dangling operators and unsupported expressions must not become a plausible last operand.
        if (head.lastOrNull() in listOf('*', '×', '+', '/', '^', '(', '.', ',') ||
            tail.firstOrNull() in listOf('*', '×', '+', '/', '^', '-', '.', ',', ')') ||
            (head.endsWith('-') &&
                (text.getOrNull(selected.first().start - 1)?.isWhitespace() != true ||
                    text.getOrNull(head.lastIndex - 1)?.isWhitespace() != true))
        ) return Read.Invalid
        if (head == "x" || head.endsWith(" x")) return Read.Invalid
        if (tail.any { it.isDigit() } || tail == "x" || tail.startsWith("x ") ||
            tail.startsWith("%") || tail.startsWith("％")
        ) return Read.Invalid

        // A leading sign on a compound quantity could apply to one component or the whole amount.
        if (selected.first().value.signum() < 0 && selected.drop(1).any { it.value.signum() > 0 } &&
            selected.zipWithNext().any { (left, right) -> text.substring(left.end, right.start).isBlank() }
        ) return Read.Invalid
        val expression = buildString {
            for ((index, token) in selected.withIndex()) {
                if (index > 0) {
                    val gap = text.substring(selected[index - 1].end, token.start)
                    append(if (gap.isBlank()) " + " else " * ")
                }
                append(token.value.toPlainString())
                val spelling = if (minutesAlias && token.display == "m") "min" else token.display
                if (spelling != null) { append(' '); append(spelling) }
            }
        }
        val quantity = MathEvaluator.readQuantity(expression) ?: return Read.Invalid
        val selectedCurrencies = selected.mapNotNull { it.currency }.toSet()
        if (selected.count { it.currency != null } > 1) return Read.Invalid
        if (selectedCurrencies.isEmpty() && allCurrencies.isNotEmpty()) return Read.Invalid
        if (selectedCurrencies.isNotEmpty() && quantity.second != null) return Read.Invalid
        val grouping = selected.maxOf { it.grouping }
        return Read.Parsed(Amount(
            quantity.first, selectedCurrencies.firstOrNull(), grouping, quantity.second,
            (base + selected.first().start) until (base + selected.last().end),
            if (quantity.second !== selected.first().unit) quantity.second?.symbol else selected.first().display,
        ))
    }
}
