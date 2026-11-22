package org.samesame.notes.core.richtext.edit.math

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** 0.4.0 regression: a trailing expression after a text label ("Udupi -> manglore: 100*7 =") showed no result. */
class LabelledMathTest {
    private fun suggest(text: String): String? = MathDetector.suggest(text, text.length, MathConfig.English)?.text

    private val cases: List<Pair<String, String?>> = listOf(
        // the owner's line, as typed and after "->" became an arrow
        "Udupi -> manglore: 100*7 =" to " 700",
        "Udupi → manglore: 100*7 =" to " 700",
        "100*7 =" to " 700",
        // separators
        "Taxi: 100 * 7 =" to " 700",
        "Taxi - 100*7 =" to " 700",
        "Taxi → 100*7 =" to " 700",
        "Taxi => 100*7 =" to " 700",
        "Taxi 100*7 =" to " 700",
        "Taxi, 100 + 7 =" to " 107",
        // a label that ends in a digit keeps its digit
        "Room 4: 100*7 =" to " 700",
        // operators
        "Fuel: 100 x 7 =" to " 700",
        "Fuel: 100 × 7 =" to " 700",
        "Fuel: 700 / 7 =" to " 100",
        "Fuel: 700 ÷ 7 =" to " 100",
        "Fuel: 100 + 7 =" to " 107",
        "Fuel: 100 - 7 =" to " 93",
        "Fuel: 100 − 7 =" to " 93",
        "Tip: 15% of 80 =" to " 12",
        "Trip: (100 + 50) * 2 =" to " 300",
        "Snacks: 2.5 * 4 =" to " 10",
        "Rent: 1,000 * 12 =" to " 12000",
        "Rent: ₹1,000 * 12 =" to " ₹12000",
        "Lunch: $12.50 + $7.50 =" to " $20",
        "Hotel: €80 * 3 =" to " €240",
        "Train: £10 + 5 =" to " £15",
        "100 x 7 =" to " 700",
        "Udupi: 100*7 =   " to "700",
        // no ghost
        "Udupi: 100*7" to null,
        "Udupi: 700 =" to null,
        "Due: 12/11/2026 =" to null,
        "Meeting: 10:30 =" to null,
        "Meeting at 10:30 + 5 =" to null,
        "Call 555-1234 =" to null,
        "Phone: +91 98450 12345 =" to null,
        "App 1.2.3 =" to null,
        "Box x 2 =" to null,
        "A4 x 2 =" to null,
        "x = 5 + 3 =" to null,
        "Mixed: ₹10 + $5 =" to null,
        "Udupi -> manglore =" to null,
    )

    @Test fun labelledExpressions() {
        for ((line, expected) in cases) {
            assertWithMessage(line).that(suggest(line)).isEqualTo(expected)
        }
    }

    @Test fun labelledResultIsNotAKeywordLine() {
        val s = MathDetector.suggest("Udupi → manglore: 100*7 =", 25, MathConfig.English)
        assertWithMessage("ghost").that(s?.keywordLine).isEqualTo(false)
        assertWithMessage("enter").that(MathGhostInput.enterAccepts(s)).isFalse()
    }
}
