package org.samesame.notes.core.richtext.edit.math

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MathDetectorTest {
    private val en = MathConfig.English

    private fun suggest(text: String, config: MathConfig = en): String? =
        MathDetector.suggest(text, text.length, config)?.text

    /** Expressions that must show a result, as the whole line with the caret after the `=`. */
    private val positives: List<Pair<String, String>> = listOf(
        // arithmetic
        "1 + 1 =" to " 2",
        "1+1 =" to " 2",
        "2 + 3 =" to " 5",
        "10 - 4 =" to " 6",
        "6 * 7 =" to " 42",
        "12 / 4 =" to " 3",
        "2 × 3 =" to " 6",
        "12 ÷ 3 =" to " 4",
        "2 ^ 10 =" to " 1024",
        "(2 + 3) * 4 =" to " 20",
        "-5 + 2 =" to " -3",
        "1.5 + 2.5 =" to " 4",
        "0.1 + 0.2 =" to " 0.3",
        "10 / 3 =" to " 3.3333333333",
        "2 ^ 0 =" to " 1",
        "100 - 25 * 2 =" to " 50",
        "1 + 2 * 3 =" to " 7",
        "8 / 2 / 2 =" to " 2",
        "2 * 3 + 4 * 5 =" to " 26",
        "1 - -2 =" to " 3",
        "365 * 24 =" to " 8760",
        "60 * 60 * 24 =" to " 86400",
        "999 + 1 =" to " 1000",
        "7 * 8 =" to " 56",
        "((2 + 3) * 2) =" to " 10",
        // percentages
        "15% of 80 =" to " 12",
        "80 + 15% =" to " 92",
        "80 - 20% =" to " 64",
        "200 * 10% =" to " 20",
        "50% * 4 =" to " 2",
        "15% + 5% =" to " 20%",
        "100 / 20% =" to " 500",
        "10% of 250 km =" to " 25 km",
        "40% of 30 =" to " 12",
        "250 - 10% =" to " 225",
        // length
        "1km + 2km =" to " 3 km",
        "1km + 102m =" to " 1.102 km",
        "1.102 km - 102 m =" to " 1 km",
        "5 km in m =" to " 5000 m",
        "2 km to m =" to " 2000 m",
        "2000 m as km =" to " 2 km",
        "5 mi in km =" to " 8.04672 km",
        "12 in in cm =" to " 30.48 cm",
        "3 ft in cm =" to " 91.44 cm",
        "7 yd in ft =" to " 21 ft",
        "1 m + 20 cm =" to " 1.2 m",
        "3 km - 500 m =" to " 2.5 km",
        "2 m * 3 m =" to " 6 m²",
        "100 m² / 5 m =" to " 20 m",
        "1 sq m in cm² =" to " 10000 cm²",
        // mass
        "1 kg + 500 g =" to " 1.5 kg",
        "2 lb + 8 oz =" to " 2.5 lb",
        "5 kg + 500 g =" to " 5.5 kg",
        "1 g + 500 mg =" to " 1.5 g",
        "1 t in kg =" to " 1000 kg",
        "1 oz in g =" to " 28.349523125 g",
        // volume
        "1 l - 250 ml =" to " 0.75 l",
        "2 tbsp in tsp =" to " 6 tsp",
        "1 gal in l =" to " 3.785411784 l",
        "1 cup in ml =" to " 236.5882365 ml",
        "1 cl in ml =" to " 10 ml",
        // time
        "1h 30min =" to " 1.5 h",
        "1 h 30 min =" to " 1.5 h",
        "1 week in d =" to " 7 d",
        "2 d in h =" to " 48 h",
        "500 ms in s =" to " 0.5 s",
        "1 hr in min =" to " 60 min",
        "1 sec in ms =" to " 1000 ms",
        "1 min in s =" to " 60 s",
        // speed
        "100 km / 2 h =" to " 50 km/h",
        "10 m / 2 s =" to " 5 m/s",
        "60 mph in km/h =" to " 96.56064 km/h",
        // area
        "1 km² in ha =" to " 100 ha",
        "3 m² in cm² =" to " 30000 cm²",
        "1 ha in m² =" to " 10000 m²",
        // data
        "1.5 GB to MB =" to " 1500 MB",
        "512 MB + 512 MB =" to " 1024 MB",
        "1 KB in B =" to " 1000 B",
        "1 KiB in B =" to " 1024 B",
        "1 GB in MiB =" to " 953.6743164063 MiB",
        "2 GB / 2 =" to " 1 GB",
        "1024 MB in GB =" to " 1.024 GB",
        // temperature
        "100 F to C =" to " 37.7777777778 C",
        "32 F to C =" to " 0 C",
        "0 C to K =" to " 273.15 K",
        "100 C to F =" to " 212 F",
        "20 C + 5 C =" to " 25 C",
        // scaling and mixed
        "1 km * 2 =" to " 2 km",
        "2 * 1 km =" to " 2 km",
        "1 m / 2 =" to " 0.5 m",
        "0.5 km + 500 m =" to " 1 km",
        // grouping separators
        "1,000 + 1 =" to " 1001",
        "1 000 + 1 =" to " 1001",
        // aggregates
        "2\n3\nsum =" to " 5",
        "1 km\n2 km\nsum =" to " 3 km",
        "1 km\n500 m\ntotal =" to " 1.5 km",
        "2\n4\navg =" to " 3",
        "1.5\n2.5\naverage =" to " 2",
        "10\n-2\nsum =" to " 8",
    )

    /** Lines that must stay quiet: dates, times, phone numbers, versions, assignments, code, lone values, errors. */
    private val negatives: List<String> = listOf(
        "5 =",
        "42 =",
        "5 km =",
        "1.5 GB =",
        "2.5 km =",
        "2026-11-17 =",
        "17/11/2026 =",
        "17.11.2026 =",
        "12/3/20 =",
        "10:30 =",
        "23:59 =",
        "555-1234 =",
        "(555) 123-4567 =",
        "+44 20 7946 0958 =",
        "011 234 5678 =",
        "1.2.3 =",
        "v1.2.3 =",
        "a == b =",
        "x = 5 =",
        "hello =",
        "=",
        "= ",
        "5 + =",
        "5 / 0 =",
        "5 / (2-2) =",
        "1 km + 1 kg =",
        "1 km * 1 kg =",
        "1 kg in km =",
        "20 C + 5 F =",
        "2 ^ 100 =",
        "2 ^ 1.5 =",
        "0 ^ -1 =",
        "- 5 =",
        "- 5 + 3 =",
        "1. 5 + 5 =",
        "* 5 + 5 =",
        "\u2022 5 + 5 =",
        "5 + 5 == ",
        "5 - 3 = 2",
        "sum =",
        "one\ntwo\nsum =",
        "1 km\n2 kg\nsum =",
        "2 + 2\n\nsum =",
        "5 + ",
        "5 + 5",
        "https://example.com =",
        "note 5 + 5 =",
        "5 % 3 =",
        "5,5,5 + 1 =",
        "5..5 + 1 =",
        "(5 + 2 =",
        "5 + 5)",
    )

    @Test
    fun corpusHasAtLeastOneHundredTwentyCases() {
        assertThat(positives.size + negatives.size).isAtLeast(120)
    }

    @Test
    fun expressionsShowTheirResult() {
        for ((line, expected) in positives) {
            assertThat(suggest(line)).isEqualTo(expected)
        }
    }

    @Test
    fun falseTriggersStayQuiet() {
        for (line in negatives) {
            assertThat(suggest(line)).isNull()
        }
    }

    @Test
    fun onlyTheLineBeforeTheCaretIsRead() {
        val text = "a\n5 + 5 =\nb"
        assertThat(MathDetector.suggest(text, 9, en)?.text).isEqualTo(" 10")
        assertThat(MathDetector.suggest(text, 10, en)).isNull()
        assertThat(MathDetector.suggest(text, 0, en)).isNull()
    }

    @Test
    fun aLeadingSpaceIsOnlyAddedWhenTheUserDidNotTypeOne() {
        assertThat(MathDetector.suggest("5 + 5 =", 7, en)?.text).isEqualTo(" 10")
        assertThat(MathDetector.suggest("5 + 5 = ", 8, en)?.text).isEqualTo("10")
    }

    @Test
    fun commaDecimalLocaleAndResultText() {
        assertThat(MathDetector.suggest("1,5 + 2,5 =", 11, MathConfig.CommaDecimal)?.text).isEqualTo(" 4")
        assertThat(MathDetector.suggest("1.234,5 + 1 =", 13, MathConfig.CommaDecimal)?.text).isEqualTo(" 1235,5")
    }

    @Test
    fun selectionMenuCalculation() {
        assertThat(MathDetector.calculateSelection("5 + 5", en)).isEqualTo("5 + 5 = 10")
        assertThat(MathDetector.calculateSelection("5 + 5 =", en)).isEqualTo("5 + 5 = 10")
        assertThat(MathDetector.calculateSelection("1km + 102m", en)).isEqualTo("1km + 102m = 1.102 km")
        assertThat(MathDetector.calculateSelection("5 km in m", en)).isEqualTo("5 km in m = 5000 m")
        assertThat(MathDetector.calculateSelection("5", en)).isNull()
        assertThat(MathDetector.calculateSelection("2026-11-17", en)).isNull()
        assertThat(MathDetector.calculateSelection("5 + 5\n6 + 6", en)).isNull()
        assertThat(MathDetector.calculateSelection("hello", en)).isNull()
    }

    @Test
    fun longParagraphWithoutNewlineReturnsNullWithoutException() {
        val text = "a".repeat(50_000)
        assertThat(MathDetector.suggest(text, text.length, en)).isNull()
    }
}
