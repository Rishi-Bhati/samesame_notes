package org.samesame.notes.core.ink.brush

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.model.PenType

@RunWith(AndroidJUnit4::class)
class PenCatalogTest {
    @Test
    fun everyFamilyBuildsAndKeepsItsKey() {
        for (spec in PenCatalog.pens + PenCatalog.highlighter) for (pressure in listOf(true, false)) {
            val family = spec.build(pressure)
            assertThat(family.developerComment)
                .isEqualTo("samesame/${spec.type.name}/${if (pressure) "pressure" else "fixed"}")
            assertThat(PenBrushes.familyForKey(family.developerComment)).isSameInstanceAs(family)
            val inputs = MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, x = 0f, y = 0f, elapsedTimeMillis = 0, pressure = 0.5f)
                .add(InputToolType.STYLUS, x = 30f, y = 10f, elapsedTimeMillis = 16, pressure = 0.8f)
                .add(InputToolType.STYLUS, x = 60f, y = 0f, elapsedTimeMillis = 32, pressure = 0.4f)
            val stroke = Stroke(spec.brush(30, pressure = pressure), inputs)
            assertThat(stroke.shape.computeBoundingBox()).isNotNull()
        }
    }

    @Test
    fun orderAndWidthCurve() {
        assertThat(PenCatalog.pens.map { it.type }).containsExactly(
            PenType.Fountain, PenType.Calligraphy, PenType.Pen, PenType.Pencil, PenType.Brush,
        ).inOrder()
        assertThat(PenCatalog.pens[0].widthPu(30)).isWithin(0.01f).of(5f)
        assertThat(PenCatalog.pens[0].widthPu(1)).isLessThan(PenCatalog.pens[0].widthPu(100))
        assertThat(PenCatalog.highlighter.fixedAlpha).isEqualTo(115)
        assertThat(PenCatalog.pens[3].fixedAlpha).isEqualTo(160)
    }

    @Test
    fun grainTextureIsGenerated() {
        assertThat(GrainTextureStore.get(GrainTextureStore.GRAIN_ID)!!.width).isEqualTo(GrainTextureStore.GRAIN_SIZE_PX)
        assertThat(GrainTextureStore.get("other")).isNull()
    }
}
