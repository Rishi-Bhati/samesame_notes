package org.samesame.notes.core.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.samesame.notes.core.model.PenSettings
import org.samesame.notes.core.model.PenType
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

// Instrumented: androidx.ink is backed by native code that only loads on a device or emulator.
@RunWith(AndroidJUnit4::class)
class StrokeCodecTest {
    @Test
    fun roundTripKeepsPenAndInputs() {
        val pen = PenSettings(type = PenType.Calligraphy, color = 0xFF1A73E8, size = 6f, pressureSensitive = true)
        val inputs = MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x = 0f, y = 0f, elapsedTimeMillis = 0, pressure = 0.5f)
            .add(InputToolType.STYLUS, x = 10f, y = 12f, elapsedTimeMillis = 16, pressure = 0.7f)
        val stroke = Stroke(PenBrushes.brushFor(pen), inputs)

        val bytes = ByteArrayOutputStream().also { StrokeCodec.encode(listOf(stroke), it) }.toByteArray()
        val decoded = StrokeCodec.decode(ByteArrayInputStream(bytes)).single()

        assertThat(PenBrushes.penFor(decoded)).isEqualTo(pen)
        assertThat(decoded.inputs.size).isEqualTo(2)
    }
}
