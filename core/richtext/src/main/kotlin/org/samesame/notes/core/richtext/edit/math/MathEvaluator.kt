package org.samesame.notes.core.richtext.edit.math

import org.samesame.notes.core.model.Limits
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A number grouped with plain spaces: `1 000`, `12 345 678.5`. */
private val SPACE_GROUPED = Regex("""^\d{1,3}(?: \d{3})+(?:[.,]\d+)?$""")

/** A result that is ready to show: [text] is the number (and unit), [ops] how many operations produced it. */
data class MathResult(val text: String, val ops: Int)

/**
 * A recursive-descent calculator over [BigDecimal] for the "calculate as you type" suggestion.
 *
 * Grammar: `+ -` over `* / of` over `^` (right associative) over unary `+ -` over a number, a parenthesised
 * expression and postfix `%`. Numbers follow [MathConfig] (decimal and thousands separators), may carry a unit
 * from [MathUnits], and adjacent quantities of one dimension add up (`1h 30min`). Anything the grammar does not
 * cover is a hard failure: the caller then shows no suggestion rather than a wrong number.
 */
object MathEvaluator {
    private val HUNDRED = BigDecimal(100)
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private val AREA_M2 = MathUnit(MathDimension.Area, BigDecimal.ONE, "m²")
    private val METRE = MathUnit(MathDimension.Length, BigDecimal.ONE, "m")

    /** Evaluates a whole expression; null on any syntax or domain error (division by zero, mismatch, caps). */
    fun evaluate(expression: String, config: MathConfig = MathConfig.default()): MathResult? =
        evaluateInternal(expression, config)?.let { (value, ops) -> MathResult(render(value, config), ops) }

    /**
     * `5 km` converted `in m`: the source must be a single quantity and both units must measure the same thing.
     * The result keeps the unit spelling of [targetUnit].
     */
    fun convert(source: String, targetUnit: String, config: MathConfig = MathConfig.default()): MathResult? {
        val (value, ops) = evaluateInternal(source, config) ?: return null
        val quantity = value as? Num ?: return null
        val sourceUnit = quantity.unit ?: return null
        val (target, display) = MathUnits.parseUnit(targetUnit) ?: return null
        if (sourceUnit.dimension != target.dimension) return null
        val converted = target.fromBase(sourceUnit.toBase(quantity.value))
        return MathResult(render(Num(converted, target, display), config), ops + 1)
    }

    /**
     * `sum` / `avg` of [lines] (the lines directly above the caret, already collected): every line must be a plain
     * number or one quantity of one dimension. Mixed dimensions (or a line with an operation) make it null.
     */
    fun aggregate(lines: List<String>, average: Boolean, config: MathConfig = MathConfig.default()): MathResult? {
        if (lines.isEmpty() || lines.size > Limits.MATH_MAX_AGGREGATE_LINES) return null
        return try {
            var total: Num? = null
            for (line in lines) {
                val (value, ops) = evaluateInternal(line.trim(), config) ?: return null
                if (ops != 0) return null
                val quantity = value as? Num ?: return null
                total = if (total == null) quantity else add(total, quantity, subtract = false) as? Num ?: return null
            }
            var sum = total ?: return null
            if (average) {
                sum = divide(sum, Num(BigDecimal(lines.size))) as? Num ?: return null
            }
            MathResult(render(sum, config), 1)
        } catch (_: Failure) {
            null
        }
    }

    /** A number for display: at most [Limits.MATH_MAX_DECIMALS] decimals, no trailing zeros, never `-0`. */
    fun formatNumber(value: BigDecimal): String {
        val rounded = if (value.scale() > Limits.MATH_MAX_DECIMALS) {
            value.setScale(Limits.MATH_MAX_DECIMALS, RoundingMode.HALF_UP)
        } else {
            value
        }
        val text = rounded.stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    // ---------------------------------------------------------------- values

    private sealed interface Value
    private data class Num(val value: BigDecimal, val unit: MathUnit? = null, val display: String? = null) : Value
    private data class Percent(val value: BigDecimal) : Value

    private object Failure : RuntimeException() {
        private fun readResolve(): Any = Failure
        override fun fillInStackTrace(): Throwable = this
    }

    private fun fail(): Nothing = throw Failure

    private fun evaluateInternal(expression: String, config: MathConfig): Pair<Value, Int>? {
        val text = expression.trim()
        if (text.isEmpty() || text.length > Limits.MATH_MAX_EXPRESSION_LENGTH) return null
        if (text.any { it == '\n' || it == '=' }) return null
        if (looksLikeDate(text)) return null
        splitConversion(text)?.let { (source, target) ->
            val (value, ops) = evaluateInternal(source, config) ?: return null
            val quantity = value as? Num ?: return null
            val sourceUnit = quantity.unit ?: return null
            val (targetUnit, display) = MathUnits.parseUnit(target) ?: return null
            if (sourceUnit.dimension != targetUnit.dimension) return null
            val converted = targetUnit.fromBase(sourceUnit.toBase(quantity.value))
            return Num(converted, targetUnit, display) to (ops + 1)
        }
        return try {
            val tokens = Lexer(text, config).lex()
            val parser = Parser(tokens, config)
            parser.parseAll() to parser.ops
        } catch (_: Failure) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private val dateIso = Regex("""^\d{4}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2}$""")
    private val dateShort = Regex("""^\d{1,2}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{2,4}$""")
    private val dateDayMonth = Regex("""^(\d{1,4})\s*[/-]\s*(\d{1,2})$""")
    private val version = Regex("""^\d+(?:\.\d+){2,}$""")

    private fun looksLikeDate(body: String): Boolean {
        if (dateIso.matches(body) || dateShort.matches(body)) return true
        val dayMonth = dateDayMonth.matchEntire(body)
        if (dayMonth != null) {
            val first = dayMonth.groupValues[1].toIntOrNull() ?: 0
            val second = dayMonth.groupValues[2].toIntOrNull() ?: 0
            if (first > 12 || second > 12) return true
        }
        if (version.matches(body)) return true
        return false
    }

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

    private fun render(value: Value, config: MathConfig): String {
        val text = when (value) {
            is Num -> {
                val number = formatNumber(checked(value.value))
                val unit = value.display ?: value.unit?.symbol
                if (unit == null) number else "$number $unit"
            }
            is Percent -> formatNumber(checked(value.value)) + "%"
        }
        return if (config.decimalSeparator == '.') text else text.replace('.', config.decimalSeparator)
    }

    private fun checked(value: BigDecimal): BigDecimal {
        if (value.scale() > 400) fail()
        if (value.precision() - value.scale() > Limits.MATH_MAX_RESULT_DIGITS) fail()
        return value
    }

    // ---------------------------------------------------------------- arithmetic

    private fun add(a: Value, b: Value, subtract: Boolean): Value = when {
        a is Percent && b is Percent -> Percent(if (subtract) a.value - b.value else a.value + b.value)
        a is Num && b is Percent && a.unit == null -> {
            val factor = if (subtract) BigDecimal.ONE - b.value.divide(HUNDRED, MC) else BigDecimal.ONE + b.value.divide(HUNDRED, MC)
            Num(a.value.multiply(factor))
        }
        a is Num && b is Num -> addQuantities(a, b, subtract)
        else -> fail()
    }

    private fun addQuantities(a: Num, b: Num, subtract: Boolean): Num {
        if (a.unit == null && b.unit == null) return Num(if (subtract) a.value - b.value else a.value + b.value)
        if (a.unit == null || b.unit == null) fail()
        if (a.unit.dimension != b.unit.dimension) fail()
        if (a.unit.dimension == MathDimension.Temperature) {
            // Only a difference: the same temperature unit on both sides, added numerically.
            if (a.unit !== b.unit) fail()
            return Num(if (subtract) a.value - b.value else a.value + b.value, a.unit, a.display)
        }
        val base = a.unit.toBase(a.value) + (if (subtract) b.unit.toBase(b.value).negate() else b.unit.toBase(b.value))
        return Num(a.unit.fromBase(base), a.unit, a.display)
    }

    private fun multiply(a: Value, b: Value): Value {
        if (a is Percent && b is Percent) fail()
        if (a is Percent) return scale(b, a.value.divide(HUNDRED, MC))
        if (b is Percent) return scale(a, b.value.divide(HUNDRED, MC))
        a as Num
        b as Num
        if (a.unit?.dimension == MathDimension.Temperature || b.unit?.dimension == MathDimension.Temperature) fail()
        if (a.unit == null) return Num(a.value.multiply(b.value), b.unit, b.display)
        if (b.unit == null) return Num(a.value.multiply(b.value), a.unit, a.display)
        if (a.unit.dimension == MathDimension.Length && b.unit.dimension == MathDimension.Length) {
            val base = a.unit.toBase(a.value).multiply(b.unit.toBase(b.value))
            val area = if (a.display != null && a.display == b.display) {
                MathUnit(MathDimension.Area, a.unit.factor.multiply(b.unit.factor), "${a.display}²")
            } else {
                AREA_M2
            }
            return Num(area.fromBase(base), area, area.symbol)
        }
        fail()
    }

    private fun divide(a: Value, b: Value): Value {
        if (b is Percent) {
            if (b.value.signum() == 0) fail()
            return scale(a, HUNDRED.divide(b.value, MC))
        }
        if (a is Percent) {
            if (b is Percent) return Percent(a.value.divide(b.value, MC))
            b as Num
            if (b.value.signum() == 0 || b.unit != null) fail()
            return Percent(a.value.divide(b.value, MC))
        }
        a as Num
        b as Num
        if (b.value.signum() == 0) fail()
        if (b.unit == null) return Num(a.value.divide(b.value, MC), a.unit, a.display)
        if (a.unit == null) fail()
        if (a.unit.dimension == MathDimension.Temperature || b.unit.dimension == MathDimension.Temperature) fail()
        if (a.unit.dimension == b.unit.dimension) {
            return Num(a.unit.toBase(a.value).divide(b.unit.toBase(b.value), MC))
        }
        if (a.unit.dimension == MathDimension.Length && b.unit.dimension == MathDimension.Time) {
            val speedBase = a.unit.toBase(a.value).divide(b.unit.toBase(b.value), MC)
            val speed = speedUnit(a, b)
            return Num(speed.fromBase(speedBase), speed, speed.symbol)
        }
        if (a.unit.dimension == MathDimension.Area && b.unit.dimension == MathDimension.Length) {
            return Num(a.unit.toBase(a.value).divide(b.unit.toBase(b.value), MC), METRE, "m")
        }
        fail()
    }

    private fun speedUnit(length: Num, time: Num): MathUnit {
        val km = length.display == "km" || length.unit?.factor == BigDecimal(1000)
        val hour = time.display in setOf("h", "hr", "hrs", "hour", "hours")
        return if (km && hour) {
            MathUnit(MathDimension.Speed, BigDecimal(1000).divide(BigDecimal(3600), MathContext(34)), "km/h")
        } else {
            MathUnit(MathDimension.Speed, BigDecimal.ONE, "m/s")
        }
    }

    private fun scale(value: Value, factor: BigDecimal): Value = when (value) {
        is Num -> Num(value.value.multiply(factor), value.unit, value.display)
        is Percent -> Percent(value.value.multiply(factor))
    }

    private fun power(base: Value, exponent: Value): Value {
        val b = base as? Num ?: fail()
        val e = exponent as? Num ?: fail()
        if (b.unit != null || e.unit != null) fail()
        val stripped = e.value.stripTrailingZeros()
        if (stripped.scale() > 0) fail()
        if (stripped.abs() > BigDecimal(Limits.MATH_MAX_EXPONENT)) fail()
        val n = stripped.toInt()
        if (b.value.signum() == 0 && n < 0) fail()
        val rounded = b.value.round(MC)
        val result = if (n >= 0) {
            rounded.pow(n, MC)
        } else {
            BigDecimal.ONE.divide(rounded.pow(-n, MC), MC)
        }
        return Num(result)
    }

    private fun percentOf(value: Value): Value = when (value) {
        is Num -> if (value.unit != null) fail() else Percent(value.value)
        is Percent -> fail()
    }

    private fun of(percent: Value, quantity: Value): Value {
        val p = percent as? Percent ?: fail()
        val q = quantity as? Num ?: fail()
        return Num(q.value.multiply(p.value).divide(HUNDRED, MC), q.unit, q.display)
    }

    // ---------------------------------------------------------------- tokens

    private sealed interface Token {
        data class Number(val value: BigDecimal) : Token
        data class UnitToken(val unit: MathUnit, val display: String) : Token
        data class Operator(val char: Char) : Token
        data object LParen : Token
        data object RParen : Token
        data object PercentSign : Token
        data object Of : Token
    }

    /**
     * Splits the expression into numbers, units, operators and `of`. A unit is read right after a number (with or
     * without a space); an unknown word stops the parse, so `x = 5` or `a == b` never produce a number.
     */
    private class Lexer(private val text: String, private val config: MathConfig) {
        private var index = 0

        fun lex(): List<Token> {
            val tokens = ArrayList<Token>()
            while (index < text.length) {
                val c = text[index]
                when {
                    c.isWhitespace() -> index++
                    c.isDigit() -> tokens += Token.Number(readNumber() ?: fail())
                    c == '+' || c == '-' || c == '*' || c == '/' || c == '^' -> {
                        tokens += Token.Operator(c)
                        index++
                    }
                    c == '×' -> { tokens += Token.Operator('*'); index++ }
                    c == '÷' -> { tokens += Token.Operator('/'); index++ }
                    c == '(' -> { tokens += Token.LParen; index++ }
                    c == ')' -> { tokens += Token.RParen; index++ }
                    c == '%' -> { tokens += Token.PercentSign; index++ }
                    else -> {
                        val unit = MathUnits.matchAt(text, index)
                        if (unit != null) {
                            tokens += Token.UnitToken(unit.unit, unit.display)
                            index += unit.length
                        } else if (text.regionMatches(index, "of", 0, 2, ignoreCase = true) && boundary(index + 2)) {
                            tokens += Token.Of
                            index += 2
                        } else {
                            fail()
                        }
                    }
                }
                if (tokens.size > Limits.MATH_MAX_EXPRESSION_LENGTH) fail()
            }
            return tokens
        }

        private fun boundary(at: Int): Boolean =
            at >= text.length || !(text[at].isLetterOrDigit() || text[at] == '²' || text[at] == '³' || text[at] == '°')

        private fun readNumber(): BigDecimal? {
            val start = index
            while (index < text.length) {
                if (isNumberChar(text[index])) {
                    index++
                    continue
                }
                // "1 000": a plain space groups exactly three digits (see SPACE_GROUPED in parseNumber).
                if (text[index] == ' ' && spaceGroupAt(index)) {
                    index += 4
                    continue
                }
                break
            }
            return parseNumber(text.substring(start, index))
        }

        private fun spaceGroupAt(at: Int): Boolean {
            if (text[at] != ' ' || at + 4 > text.length) return false
            if (!text[at + 1].isDigit() || !text[at + 2].isDigit() || !text[at + 3].isDigit()) return false
            val after = at + 4
            return after >= text.length || !text[after].isDigit()
        }

        private fun isNumberChar(c: Char): Boolean =
            c.isDigit() || c == '.' || c == ',' || c == '\'' || c == '\u00A0' || c == '\u202F'

        /**
         * `1,234.56` (decimal point and grouping comma), `1.234,56` (the other way round), `1 000` typed with
         * narrow spaces and `1,5` on an English keyboard are all read. A separator that cannot be grouping is
         * taken as the decimal separator, which is what someone typing "1.5" on a comma locale means.
         */
        private fun parseNumber(raw: String): BigDecimal? {
            if (SPACE_GROUPED.matches(raw)) return parseNumber(raw.replace(" ", ""))
            val separators = charArrayOf('.', ',', ' ', '\u00A0', '\u202F', '\'')
            if (raw.none { it in separators }) return raw.toBigDecimalOrNull()
            val decimal = config.decimalSeparator
            val decimals = raw.count { it == decimal }
            var integer: String
            var fraction: String? = null
            if (decimals == 1) {
                val at = raw.indexOf(decimal)
                integer = raw.substring(0, at)
                fraction = raw.substring(at + 1)
            } else if (decimals > 1) {
                integer = raw
            } else {
                val others = raw.filter { it in separators }.toSet()
                if (others.size > 1) return null
                val separator = others.first()
                val parts = raw.split(separator)
                if (parts.size == 2 && parts[1].length != 3) {
                    integer = parts[0]
                    fraction = parts[1]
                } else {
                    integer = raw
                }
            }
            val digits = groupingDigits(integer) ?: return null
            if (digits.isEmpty() && fraction.isNullOrEmpty()) return null
            if (digits.length > 60) return null
            val frac = fraction
            if (frac != null && (frac.isEmpty() || !frac.all { it.isDigit() })) return null
            return buildString {
                append(if (digits.isEmpty()) "0" else digits)
                if (!frac.isNullOrEmpty()) {
                    append('.')
                    append(frac)
                }
            }.toBigDecimalOrNull()
        }

        /** The integer part without its grouping separators, or null when the groups are not thousands groups. */
        private fun groupingDigits(integer: String): String? {
            if (integer.isEmpty()) return ""
            if (integer == "0") return "0"
            val groups = integer.split('.', ',', ' ', '\u00A0', '\u202F', '\'')
            if (groups.size > 1) {
                if (groups.first().isEmpty() || groups.first().length > 3) return null
                if (groups.drop(1).any { it.length != 3 }) return null
            }
            val digits = groups.joinToString("")
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            return digits
        }
    }

    private class Parser(private val tokens: List<Token>, private val config: MathConfig) {
        private var index = 0
        private var depth = 0
        var ops = 0
            private set

        fun parseAll(): Value {
            val value = expression()
            if (index != tokens.size) fail()
            return value
        }

        private fun expression(): Value {
            var left = term()
            while (true) {
                val op = (peek() as? Token.Operator)?.char ?: return left
                if (op != '+' && op != '-') return left
                index++
                ops++
                left = add(left, term(), subtract = op == '-')
            }
        }

        private fun term(): Value {
            var left = unary()
            while (true) {
                val op = (peek() as? Token.Operator)?.char
                when {
                    op == '*' -> { index++; ops++; left = multiply(left, unary()) }
                    op == '/' -> { index++; ops++; left = divide(left, unary()) }
                    peek() is Token.Of -> { index++; ops++; left = of(left, unary()) }
                    else -> return left
                }
            }
        }

        private fun unary(): Value {
            val op = (peek() as? Token.Operator)?.char
            if (op == '-') {
                index++
                return when (val value = unary()) {
                    is Num -> Num(value.value.negate(), value.unit, value.display)
                    is Percent -> Percent(value.value.negate())
                }
            }
            if (op == '+') {
                index++
                return unary()
            }
            return powered()
        }

        private fun powered(): Value {
            val base = postfix()
            if ((peek() as? Token.Operator)?.char == '^') {
                index++
                ops++
                return power(base, unary())
            }
            return base
        }

        private fun postfix(): Value {
            var value = primary()
            while (peek() is Token.PercentSign) {
                index++
                // A percent alone is not a calculation ("15% =" stays quiet); `of`, `+`, `-`, `*` and `/` count.
                value = percentOf(value)
            }
            return value
        }

        private fun primary(): Value {
            when (val token = next()) {
                is Token.Number -> {
                    var value = withUnit(Num(token.value))
                    // Adjacent quantities of one dimension: "1h 30min", "1 m 20 cm".
                    while (peek() is Token.Number) {
                        val nextNumber = next() as Token.Number
                        val nextValue = withUnit(Num(nextNumber.value))
                        if (nextValue is Num && nextValue.unit != null) {
                            ops++
                            value = addQuantities(value as Num, nextValue, subtract = false)
                        } else {
                            fail()
                        }
                    }
                    return value
                }
                Token.LParen -> {
                    if (++depth > 32) fail()
                    val inner = expression()
                    depth--
                    if (next() !is Token.RParen) fail()
                    return withUnit(inner)
                }
                else -> fail()
            }
        }

        /** Attaches a unit written right after a number or a closing parenthesis. */
        private fun withUnit(value: Value): Value {
            val token = peek() as? Token.UnitToken ?: return value
            val quantity = value as? Num ?: fail()
            if (quantity.unit != null) fail()
            index++
            return Num(quantity.value, token.unit, token.display)
        }

        private fun peek(): Token? = tokens.getOrNull(index)

        private fun next(): Token = tokens.getOrNull(index++) ?: fail()
    }
}
