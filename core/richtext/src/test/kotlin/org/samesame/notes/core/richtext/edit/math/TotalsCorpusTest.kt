package org.samesame.notes.core.richtext.edit.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.samesame.notes.core.model.Limits

class TotalsCorpusTest {
    private fun suggestion(text: String) = MathDetector.suggest(text, text.length, MathConfig.English)

    private val corpus = listOf(
        "Milk 60\nEggs 2 x 72\nTotal" to " 204",
        "Milk 60\nBread 40\nSum" to " 100",
        "Rent: ₹12,000\nPower: ₹1,500\nTOTAL:" to " ₹13,500",
        "Hotel 2k\nTaxi 500\nTotal" to " 2500",
        "Deposit 1.5L\nFee 2k\nTotal" to " 152000",
        "Maths 78\nScience 82\nAverage" to " 80",
        "Pushups 20\nSquats 30\nTotal" to " 50",
        "Monday 7h 30m\nTuesday 8h\nTotal" to " 15h 30m",
        "Food $50\nTaxi ₹200\nTotal" to null,
        "Purchase 1000\nRefund -200\nTotal" to " 800",
        "Rent - 12000\nRefund: -200\nTotal" to " 11800",
        "-200 refund\n1000 purchase\nTotal" to " 800",
        "- Milk 60\n• Eggs 144\nTotal" to " 204",
        "[ ] Milk 60\n[x] Bread 40\nTotal" to " 100",
        "1. Milk 60\n2) Bread 40\nTotal" to " 100",
        "☐ Milk 60\n☑ Bread 40\nTotal" to " 100",
        "Old 500\n\nMilk 60\nTotal" to " 60",
        "Old 500\n# Shopping\nMilk 60\nTotal" to " 60",
        "Old 500\nShopping:\nMilk 60\nTotal" to " 60",
        "Old 500\nTotal 500\nMilk 60\nTotal" to " 60",
        "Milk 60\nTotally" to null,
        "Milk 60\nTOTAL:" to " 60",
        "Food 100\nSubtotal 100\nTaxi 50\nSubtotal: 50\nGrand total" to " 150",
        "Food 100\nTaxi 50\nGrand total" to " 150",
        "Maths 78\nScience 82\nAvg =" to " 80",
        "Milk 60\nEggs 2 × 72\nCount" to " 2",
        "Rent 12000\nWater 200\nMax" to " 12000",
        "Rent 12000\nWater 200\nMin" to " 200",
        "Milk\nReminder to buy bread\nTotal" to null,
        "Milk 60\nBuy organic\nBread 40\nTotal" to " 100",
        "Rent = 12000\nWater\t200\nTotal =" to " 12200",
        "60 milk\n40 bread\nTotal" to " 100",
        "Price ₹1,000\nFee ₹100\nTotal" to " ₹1,100",
        "Rs 500 hotel\nTaxi Rs. 100\nTotal" to " ₹600",
        "Savings ₹1,00,000\nBonus ₹12,000\nTotal" to " ₹1,12,000",
        "First 1,000\nSecond 100\nTotal" to " 1,100",
        "First 1000\nSecond 100\nTotal" to " 1100",
        "Eggs 2 * 72\nBread 40\nSubtotal" to " 184",
        "Day 2 500\nRoom 101 1000\nTotal" to " 1500",
        "Day 2\nRoom 101\nTotal" to " 103",
        "Flat 3B\nMilk 60\nTotal" to " 60",
        "Meet 7:30\nMilk 60\nTotal" to " 60",
        "Trip 12/11\nMilk 60\nTotal" to " 60",
        "Date 2026-11-18\nMilk 60\nTotal" to " 60",
        "Phone 9876543210\nMilk 60\nTotal" to " 60",
        "Version 1.2.3\nMilk 60\nTotal" to " 60",
        "Discount 15%\nMilk 60\nTotal" to " 60",
        "Walk 1 km\nRun 500 m\nTotal" to " 1.5 km",
        "Walk 1 km\nRice 2 kg\nTotal" to null,
        "Rice 2 kg\nMilk 60\nTotal" to null,
        "Rice 1 kg\nRice 500 g\nAverage" to " 0.75 kg",
        "Price 1,00\nMilk 60\nTotal" to null,
        "Eggs 2 x\nMilk 60\nTotal" to null,
        "Power 0.1\nWater 0.2\nTotal" to " 0.3",
        "First -20\nSecond -5\nMax" to " -5",
        "First -20\nSecond -5\nMin" to " -20",
        "Work 30 min\nWork 90 min\nTotal" to " 2h",
        "First $1,000.50\nSecond $0.50\nTotal" to " $1,001",
        "First ₹1,00,000.25\nSecond ₹1,000.25\nTotal" to " ₹1,01,000.5",
        "Food 100\nSubtotal = 100\nTaxi 50\nSubtotal = 50\ngRaNd ToTaL =" to " 150",
        "Milk 60\n Total :  " to "60",
        "Old 100\n---\nMilk 60\nTotal" to " 60",
        "Old 100\nBILLS\nWater 20\nTotal" to " 20",
        "Old 100\n**Bills**\nWater 20\nTotal" to " 20",
        "Eggs 2x72\nMilk 60\nTotal" to " 204",
        "Eggs 2 x72\nMilk 60\nTotal" to " 204",
        "Work 30m 7h\nWork 8h\nTotal" to " 15h 30m",
        "Work 7h 30m 30s\nTotal" to " 7h 30.5m",
        "Refund -90min\nTotal" to " -90min",
        "Subtotal -90min\nSubtotal 30min\nGrand total" to " -60min",
        "Walk 1 m 20 cm\nTotal" to " 1.2 m",
        "Walk 1 m 2 kg\nTotal" to null,
        "First €50\nSecond £20\nTotal" to null,
        "First ₩50\nSecond $20\nTotal" to null,
        "Food 2 x ₹72\nTotal" to " ₹144",
        "Floor 2 m * 3 m\nTotal" to " 6 m²",
        "Floor 2 cm * 3 cm\nTotal" to " 6 cm²",
        "Distance and time 2 m 30 min\nTotal" to null,
        "Work 7h 30m\nWork 8h\nAverage" to " 7h 45m",
        "Refund -1h 30m\nWork 8h\nTotal" to null,
        "First -₹1,00,000\nSecond ₹-1000\nTotal" to " ₹-1,01,000",
        "Price 1,00\nSubtotal 100\nSubtotal 50\nGrand total" to " 150",
    )

    @Test
    fun everydayLists() {
        assertTrue(corpus.size >= 40)
        for ((text, expected) in corpus) assertEquals(text, expected, suggestion(text)?.text)
    }

    @Test
    fun nonAmountsAlsoStayQuietWhenTheyAreTheOnlyItems() {
        val lines = listOf(
            "7:30", "23:59", "12/11", "17-11", "2026-11", "2026-11-18", "17/11/2026", "17.11.2026", "12/3/20",
            "9876543210", "555-1234", "(555) 123-4567", "+44 20 7946 0958", "011 234 5678",
            "1.2.3", "v1.2.3", "15%", "Flat 3B",
        )
        for (line in lines) {
            assertNull(line, suggestion("Item $line\nTotal"))
            assertEquals(line, " 60", suggestion("Item $line\nMilk 60\nTotal")?.text)
        }
    }

    @Test
    fun incompleteOrAmbiguousAmountsSuppressTheWholeTotal() {
        for (line in listOf(
            "Price 1,00", "Price 1,234,56", "Price 5..5", "Price 5,5,5", "Price 200 +", "Price 2 / 0",
            "Price 2 + 5", "Price (2 * 5)", "Price 2 x", "Price ₹100 $50", "Price --200", "Price 100 -", "Price .5.1", "Price 2 x x 72", "Price 20xyz",
            "Price ₹2 x ₹72", "Price- 200", "Price 12000-200",
        )) assertNull(line, suggestion("Milk 60\n$line\nTotal"))
    }

    @Test
    fun exactKeywordMustOccupyTheWholeLine() {
        for (keyword in listOf("Total", "Sum", "Subtotal", "Grand total", "Average", "Avg", "Count", "Max", "Min")) {
            for (suffix in listOf("", ":", "=", " : ", " = ")) {
                val ghost = suggestion("Milk 60\n${keyword.uppercase()}$suffix")
                assertTrue(keyword + suffix, ghost != null && ghost.keywordLine)
            }
        }
        for (line in listOf("Totally", "Total milk", "Total::", "Total ==", "Grand totals")) {
            assertNull(line, suggestion("Milk 60\n$line"))
        }
        val text = "Milk 60\nTotally"
        assertNull(MathDetector.suggest(text, text.length - 2, MathConfig.English))
    }

    @Test
    fun countedRangesCoverAmountsAndExcludeMarkersAndLabels() {
        val text = "1. Milk ₹1,000\n- Refund: -200\n[x] Eggs 2 x 72\nTotal"
        val ghost = suggestion(text)!!
        assertEquals(" ₹944", ghost.text)
        assertEquals(listOf("₹1,000", "-200", "2 x 72"), ghost.countedRanges.map { text.substring(it.first, it.last + 1) })
        assertTrue(MathGhostInput.enterAccepts(ghost))
        assertFalse(MathGhostInput.enterAccepts(suggestion("2 + 2 =")))
        assertFalse(MathGhostInput.enterAccepts(null))
    }

    @Test
    fun grandTotalUnderlinesSubtotalResultsOnly() {
        val text = "Milk 60\nSubtotal = ₹60\nTaxi 100\nSubtotal: ₹100\nGrand total"
        val ghost = suggestion(text)!!
        assertEquals(" ₹160", ghost.text)
        assertEquals(listOf("₹60", "₹100"), ghost.countedRanges.map { text.substring(it.first, it.last + 1) })
        assertNull(suggestion("Milk 60\nSubtotal\nTaxi 100\nGrand total"))
        assertNull(suggestion("Subtotal 100 200\nSubtotal 50\nGrand total"))
        assertEquals(" 100", suggestion("Milk 60\nSubtotal = 60\nTaxi 100\nTotal")?.text)
    }

    @Test
    fun capCountsSkippedLinesAndNeverShowsAPartialSum() {
        val items = List(Limits.MATH_BLOCK_MAX_LINES) { "Milk 1" }.joinToString("\n")
        assertEquals(" 200", suggestion("$items\nTotal")?.text)
        assertNull(suggestion("Milk 5\n$items\nTotal"))
        assertEquals(" 200", suggestion("Older 999\n\n$items\nTotal")?.text)
        val labels = List(Limits.MATH_BLOCK_MAX_LINES) { "reminder" }.joinToString("\n")
        assertNull(suggestion("Milk 60\n$labels\nTotal"))
    }

    @Test
    fun totalsAreIndependentOfDeviceNumberConventions() {
        val text = "Rent ₹1,000.50\nWater ₹100.25\nTotal"
        assertEquals(" ₹1,100.75", MathDetector.suggest(text, text.length, MathConfig.CommaDecimal)?.text)
    }
}
