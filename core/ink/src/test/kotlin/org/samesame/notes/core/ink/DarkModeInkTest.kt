package org.samesame.notes.core.ink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// Only the Int overload: Brush/Stroke need the native ink library (instrumented tests).
class DarkModeInkTest {
    @Test
    fun `black becomes white and alpha is kept`() {
        assertThat(DarkModeInk.invert(0xFF000000.toInt())).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(DarkModeInk.invert(0x73000000)).isEqualTo(0x73FFFFFF)
    }

    @Test
    fun `colors at or above mid lightness are shown as picked`() {
        for (argb in listOf(0xFF335FFF, 0xFFFFB426, 0xFFFFFFFF, 0xFFEA4335, 0xFF4285F4, 0x73FFFFFF)) {
            assertThat(DarkModeInk.invert(argb.toInt())).isEqualTo(argb.toInt())
        }
    }

    @Test
    fun `dark neutral ink gets light`() {
        assertThat(DarkModeInk.invert(0xFF252525.toInt())).isEqualTo(0xFFDADADA.toInt())
    }

    @Test
    fun `dark blue becomes light blue`() {
        val inverted = DarkModeInk.invert(0xFF0000AA.toInt())
        assertThat(inverted).isEqualTo(0xFF5555FF.toInt())
    }
}
