package org.samesame.notes.core.richtext.edit.math

import org.junit.Test

class ScratchTest {
    @Test
    fun dump() {
        val cases = listOf(
            "80 + 15%", "15% of 80", "80 - 20%", "15% + 5%", "200 * 10%",
            "5%", "0 + 15%", "80 + 0%", "80 * 100%", "80 + 15 %", "80 + (15%)",
            "1 MB in KB", "1 MB", "1 km + 2 km", "2 km", "5 km in m",
            "100 F to C", "100 F", "2026-11-17", "1h 30min", "1 h 30 min",
        )
        for (c in cases) {
            println("SCRATCH <$c> => ${MathEvaluator.evaluate(c, MathConfig.English)?.text}")
        }
        println("SCRATCH convert <5 km, m> => ${MathEvaluator.convert("5 km", "m", MathConfig.English)}")
        println("SCRATCH unit <m> => ${MathUnits.parseUnit("m")}")
        println("SCRATCH unit <MB> => ${MathUnits.parseUnit("MB")}")
    }
}
