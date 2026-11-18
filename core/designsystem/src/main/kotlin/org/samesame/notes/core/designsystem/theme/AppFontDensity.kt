package org.samesame.notes.core.designsystem.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Preserve the system's accessibility conversion, then apply the app multiplier linearly. */
internal class AppFontDensity(private val system: Density, val extra: Float) : Density {
    override val density: Float get() = system.density
    override val fontScale: Float get() = system.fontScale * extra

    override fun TextUnit.toDp(): Dp = with(system) { this@toDp.toDp().value * extra }.dp
    override fun Dp.toSp(): TextUnit = with(system) { (this@toSp.value / extra).dp.toSp() }
}

/** Capped controls retain their original dp sizing contract, including at the default app scale. */
internal fun cappedFontDp(sp: Float, combinedScale: Float, max: Float): Float = sp * minOf(combinedScale, max)
