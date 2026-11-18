package org.samesame.notes.core.richtext.edit.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class LineAmountTest {
    private fun value(line: String) = LineAmount.parse(line)?.value?.stripTrailingZeros()?.toPlainString()

    @Test
    fun spacedMinusIsASeparatorAndAttachedMinusIsASign() {
        for (line in listOf("Rent - 12000", "Rent\t-\t12000", "- Rent - 12000")) assertEquals(line, "12000", value(line))
        for (line in listOf("Refund -200", "Refund: -200", "-200 refund", "Refund = -200", "Refund -₹200", "Refund ₹-200", "Refund-200", "Refund-₹200")) {
            assertEquals(line, "-200", value(line))
        }
        assertEquals("200", value("Refund - 200"))
    }

    @Test
    fun lastAmountWinsOverNumbersInLabels() {
        assertEquals("500", value("Day 2 500"))
        assertEquals("1000", value("Room 101 ₹1,000"))
        assertEquals("500", value("Flat 3B 500"))
        // An otherwise bare trailing label number is intentionally counted.
        assertEquals("2", value("Day 2"))
        assertEquals("101", value("Room 101"))
        assertNull(value("Flat 3B"))
    }

    @Test
    fun groupingShorthandAndExpressionsAreExact() {
        assertEquals("150000", value("Deposit 1.5L"))
        assertEquals("2000", value("Hotel 2k"))
        assertEquals("144", value("Eggs 2 × 72"))
        assertEquals("-144", value("Refund -2 * 72"))
        assertEquals(GroupingStyle.Indian, LineAmount.parse("₹1,00,000")?.grouping)
        assertEquals(GroupingStyle.Western, LineAmount.parse("$1,000.25")?.grouping)
        assertEquals(GroupingStyle.None, LineAmount.parse("1000")?.grouping)
        assertNull(LineAmount.parse("₹1,000,00"))
        assertNull(LineAmount.parse("1,5"))
    }

    @Test
    fun durationsKeepExactValuesAndMetresKeepTheirDimension() {
        val duration = LineAmount.parse("Work 7h 30m")!!
        assertEquals(MathDimension.Time, duration.unit?.dimension)
        assertEquals(0, duration.value.compareTo(BigDecimal("7.5")))
        assertEquals("7.5", value("Work 7h30m"))
        assertEquals(MathDimension.Length, LineAmount.parse("Walk 30 m")?.unit?.dimension)
        assertEquals(MathDimension.Volume, LineAmount.parse("Water 1.5 L")?.unit?.dimension)
    }

    @Test
    fun sourceRangesUseOriginalLineOffsets() {
        for ((line, expected) in listOf(
            "  1) [x] Eggs 2 x 72" to "2 x 72", "- Rent - ₹12,000" to "₹12,000", "Refund: -200" to "-200",
            "60 milk" to "60", "Work 7h 30m" to "7h 30m",
        )) {
            val range = LineAmount.parse(line)!!.sourceRange
            assertEquals(line, expected, line.substring(range.first, range.last + 1))
        }
    }
}
