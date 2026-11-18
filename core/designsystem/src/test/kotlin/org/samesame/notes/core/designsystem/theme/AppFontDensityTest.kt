package org.samesame.notes.core.designsystem.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppFontDensityTest {
    // A nonlinear conversion exposes scaling of the sp input instead of the system dp result.
    private val system = object : Density {
        override val density = 2f
        override val fontScale = 1.5f
        override fun TextUnit.toDp(): Dp = (value * value / 10f).dp
        override fun Dp.toSp(): TextUnit = kotlin.math.sqrt(value * 10f).sp
    }

    @Test fun appAndSectionScaleTheSystemDpResultLinearly() {
        val density = AppFontDensity(system, 1.5f * 1.5f)
        val base = with(system) { 18.sp.toDp().value }
        assertThat(with(density) { 18.sp.toDp().value }).isWithin(0.0001f).of(base * 2.25f)
        assertThat(with(density) { 18.sp.toDp().toSp().value }).isWithin(0.0001f).of(18f)
        assertThat(with(density) { 18.sp.toPx() }).isWithin(0.0001f).of(base * 2.25f * 2f)
        assertThat(with(density) { 18.sp.toPx().toSp().value }).isWithin(0.0001f).of(18f)
    }

    @Test fun defaultsPreserveTheExactSystemConversionInBothDirections() {
        val density = AppFontDensity(system, 1f)
        for (size in listOf(12f, 18f, 34f)) {
            assertThat(with(density) { size.sp.toDp() }).isEqualTo(with(system) { size.sp.toDp() })
            assertThat(with(density) { size.dp.toSp() }).isEqualTo(with(system) { size.dp.toSp() })
        }
    }

    @Test fun fixedSizesIgnoreSystemScaleAndApplyExtraLinearly() {
        val density = AppFontDensity(system, 1.5f)
        val fixed = with(density) { (18f * density.extra).dp.toSp() }
        assertThat(with(density) { fixed.toDp().value }).isWithin(0.0001f).of(27f)
    }

    @Test fun cappedSizesCapTheEntireScaleAndKeepTheOldDefaultContract() {
        for (systemScale in listOf(0.85f, 1f, 1.5f, 2f)) {
            for (extra in listOf(1f, 1.5f, 2.25f)) {
                val density = AppFontDensity(Density(2f, systemScale), extra)
                val target = cappedFontDp(16f, density.fontScale, 1.1f)
                assertThat(target).isAtMost(17.6f)
                val capped = with(density) { target.dp.toSp() }
                assertThat(with(density) { capped.toDp().value }).isWithin(0.001f).of(target)
                if (extra == 1f) assertThat(target).isEqualTo(16f * minOf(systemScale, 1.1f))
            }
        }
    }
}
