package org.samesame.notes.core.richtext.edit.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MathEvaluatorTest {
    private val en = MathConfig.English

    private fun text(expression: String, config: MathConfig = en) = MathEvaluator.evaluate(expression, config)?.text

    @Test
    fun precedenceAndParentheses() {
        assertThat(text("1 + 2 * 3")).isEqualTo("7")
        assertThat(text("(1 + 2) * 3")).isEqualTo("9")
        assertThat(text("2 * 3 + 4 * 5")).isEqualTo("26")
        assertThat(text("8 / 2 / 2")).isEqualTo("2")
        assertThat(text("2 ^ 3 ^ 2")).isEqualTo("512")
        assertThat(text("-5 + 2")).isEqualTo("-3")
        assertThat(text("1 - -2")).isEqualTo("3")
    }

    @Test
    fun roundingStripsTrailingZeros() {
        assertThat(text("0.1 + 0.2")).isEqualTo("0.3")
        assertThat(text("10 / 3")).isEqualTo("3.3333333333")
        assertThat(text("1.500 + 1.500")).isEqualTo("3")
        assertThat(text("4 / 2")).isEqualTo("2")
    }

    @Test
    fun decimalSeparatorsFollowTheLocale() {
        assertThat(text("1,5 + 2,5", MathConfig.CommaDecimal)).isEqualTo("4")
        assertThat(text("1.234,5 + 1", MathConfig.CommaDecimal)).isEqualTo("1235,5")
        assertThat(text("1.5 + 2.5", MathConfig.CommaDecimal)).isEqualTo("4")
        assertThat(text("1,5 + 2,5")).isEqualTo("4")
        assertThat(text("1 000 + 1")).isEqualTo("1001")
        assertThat(text("1,000 + 1")).isEqualTo("1001")
        assertThat(text("12 345 678.5 + 0.5")).isEqualTo("12345679")
    }

    @Test
    fun percentages() {
        assertThat(text("15% of 80")).isEqualTo("12")
        assertThat(text("80 + 15%")).isEqualTo("92")
        assertThat(text("80 - 20%")).isEqualTo("64")
        assertThat(text("15% + 5%")).isEqualTo("20%")
        assertThat(text("10% of 250 km")).isEqualTo("25 km")
        assertThat(text("200 * 10%")).isEqualTo("20")
    }

    @Test
    fun unitsAddWithinOneDimensionInTheFirstOperandsUnit() {
        assertThat(text("1km + 2km")).isEqualTo("3 km")
        assertThat(text("1km + 102m")).isEqualTo("1.102 km")
        assertThat(text("1.102 km - 102 m")).isEqualTo("1 km")
        assertThat(text("1 m + 20 cm")).isEqualTo("1.2 m")
        assertThat(text("1 kg + 500 g")).isEqualTo("1.5 kg")
        assertThat(text("2 lb + 8 oz")).isEqualTo("2.5 lb")
        assertThat(text("1 l - 250 ml")).isEqualTo("0.75 l")
        assertThat(text("1h 30min")).isEqualTo("1.5 h")
        assertThat(text("1 m 20 cm")).isEqualTo("1.2 m")
    }

    @Test
    fun conversionsKeepTheTypedTargetUnit() {
        assertThat(text("5 km in m")).isEqualTo("5000 m")
        assertThat(text("5 km to m")).isEqualTo("5000 m")
        assertThat(text("2 km as m")).isEqualTo("2000 m")
        assertThat(text("90 min in h")).isEqualTo("1.5 h")
        assertThat(text("1.5 GB to MB")).isEqualTo("1500 MB")
        assertThat(text("1.5 GB to MB")).isNotNull()
        assertThat(MathEvaluator.convert("5 km", "m", en)?.text).isEqualTo("5000 m")
        assertThat(MathEvaluator.convert("1.5 GB", "MB", en)?.text).isEqualTo("1500 MB")
        assertThat(MathEvaluator.convert("1 km", "kg", en)).isNull()
    }

    @Test
    fun derivedDimensions() {
        assertThat(text("2 m * 3 m")).isEqualTo("6 m²")
        assertThat(text("100 km / 2 h")).isEqualTo("50 km/h")
        assertThat(text("10 m / 2 s")).isEqualTo("5 m/s")
        assertThat(text("100 m² / 5 m")).isEqualTo("20 m")
    }

    @Test
    fun temperatureIsAffine() {
        assertThat(text("100 F to C")).isEqualTo("37.7777777778 C")
        assertThat(text("32 F to C")).isEqualTo("0 C")
        assertThat(text("0 C to K")).isEqualTo("273.15 K")
        assertThat(text("100 C to F")).isEqualTo("212 F")
        assertThat(text("300 K to C")).isEqualTo("26.85 C")
        assertThat(text("20 C + 5 C")).isEqualTo("25 C")
        assertThat(text("20 C + 5 F")).isNull()
    }

    @Test
    fun dataUsesTheWrittenUnitAndItsOwnFactors() {
        assertThat(text("1 MB in KB")).isEqualTo("1000 KB")
        assertThat(text("1 KiB in B")).isEqualTo("1024 B")
        assertThat(text("1024 MB in GB")).isEqualTo("1.024 GB")
        assertThat(text("5 mb + 1 MB")).isNull()
    }

    @Test
    fun errorsGiveNull() {
        assertThat(text("5 / 0")).isNull()
        assertThat(text("5 / (2 - 2)")).isNull()
        assertThat(text("5 +")).isNull()
        assertThat(text("(5 + 2")).isNull()
        assertThat(text("5 km + 2 kg")).isNull()
        assertThat(text("5 km * 2 kg")).isNull()
        assertThat(text("5 km + 2")).isNull()
        assertThat(text("5 ^ (1/2)")).isNull()
        assertThat(text("2 ^ 65")).isNull()
        assertThat(text("0 ^ -1")).isNull()
        assertThat(text("2026-11-17")).isNull() // the detector rejects dates; the parser alone sees a subtraction
        assertThat(text("x + 5")).isNull()
        assertThat(text("5 apple")).isNull()
        assertThat(text("")).isNull()
    }

    @Test
    fun aggregation() {
        assertThat(MathEvaluator.aggregate(listOf("2", "3"), average = false, en)?.text).isEqualTo("5")
        assertThat(MathEvaluator.aggregate(listOf("1 km", "500 m"), average = false, en)?.text).isEqualTo("1.5 km")
        assertThat(MathEvaluator.aggregate(listOf("2", "4"), average = true, en)?.text).isEqualTo("3")
        assertThat(MathEvaluator.aggregate(listOf("1 km", "2 kg"), average = false, en)).isNull()
        assertThat(MathEvaluator.aggregate(listOf("2 + 2"), average = false, en)).isNull()
        assertThat(MathEvaluator.aggregate(emptyList(), average = false, en)).isNull()
    }

    @Test
    fun parsedAggregatesValidateBeforeCountingAndCheckOverflow() {
        val items = listOf(LineAmount.parse("Rice 1 kg")!!, LineAmount.parse("Rice 500 g")!!)
        assertThat(MathEvaluator.aggregate(items, AggregateKind.Max)?.text).isEqualTo("1 kg")
        assertThat(MathEvaluator.aggregate(items, AggregateKind.Min)?.text).isEqualTo("0.5 kg")
        assertThat(MathEvaluator.aggregate(items, AggregateKind.Count)?.text).isEqualTo("2")
        assertThat(MathEvaluator.aggregate(listOf(LineAmount.parse("$50")!!, LineAmount.parse("₹50")!!), AggregateKind.Count)).isNull()
        val large = Amount(java.math.BigDecimal("9".repeat(40)), sourceRange = 0..39)
        assertThat(MathEvaluator.aggregate(listOf(large, large), AggregateKind.Total)).isNull()
        val tiny = listOf("0.00000000004", "0.00000000004", "0.00000000004").map { LineAmount.parse(it)!! }
        assertThat(MathEvaluator.aggregate(tiny, AggregateKind.Total)?.text).isEqualTo("0.0000000001")
    }

    @Test
    fun formatNumberNeverPrintsNegativeZero() {
        assertThat(MathEvaluator.formatNumber(java.math.BigDecimal("-0.0"))).isEqualTo("0")
        assertThat(MathEvaluator.formatNumber(java.math.BigDecimal("1.2300"))).isEqualTo("1.23")
    }
}
