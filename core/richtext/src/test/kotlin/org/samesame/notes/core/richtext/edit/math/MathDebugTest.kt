package org.samesame.notes.core.richtext.edit.math

import org.junit.Test

class MathDebugTest {
    @Test
    fun debug() {
        val en = MathConfig.English
        for (e in listOf("5 km", "5 km + 2 km", "80 + 15%", "15%", "1 MB", "5 mi", "100 F", "1h 30min", "2", "-2")) {
            println("eval [$e] -> " + MathEvaluator.evaluate(e, en))
        }
        println("convert 5 km -> m : " + MathEvaluator.convert("5 km", "m", en))
        println("unit m: " + MathUnits.parseUnit("m"))
        println("unit MB: " + MathUnits.parseUnit("MB"))
        println("detector: " + MathDetector.suggest("1 + 1 =", 7, en))
        println("detector units: " + MathDetector.suggest("1km + 2km =", 11, en))
    }
}
