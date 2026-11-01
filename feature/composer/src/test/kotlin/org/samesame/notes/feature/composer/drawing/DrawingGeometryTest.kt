package org.samesame.notes.feature.composer.drawing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.RectPu

class DrawingGeometryTest {
    @Test fun sheetIsPageWidthTimesOnePointFive() {
        assertThat(DrawingGeometry.sheet.width).isEqualTo(1080f)
        assertThat(DrawingGeometry.sheet.height).isEqualTo(1620f)
    }

    @Test fun contentIsTheUnionPlusMarginClampedToTheSheet() {
        val r = DrawingGeometry.contentRect(listOf(RectPu(100f, 200f, 300f, 400f), RectPu(250f, 350f, 600f, 900f)))!!
        assertThat(r).isEqualTo(RectPu(76f, 176f, 624f, 924f))
        val edge = DrawingGeometry.contentRect(listOf(RectPu(5f, 5f, 1075f, 1615f)))!!
        assertThat(edge).isEqualTo(RectPu(0f, 0f, 1080f, 1620f))
        assertThat(DrawingGeometry.contentRect(emptyList())).isNull()
    }

    @Test fun doneSavesOnlyWhenThereIsSomethingToKeep() {
        assertThat(DrawingGeometry.outcome(changed = false, strokeCount = 3)).isEqualTo(DrawingGeometry.Outcome.Unchanged)
        assertThat(DrawingGeometry.outcome(changed = false, strokeCount = 0)).isEqualTo(DrawingGeometry.Outcome.Unchanged)
        assertThat(DrawingGeometry.outcome(changed = true, strokeCount = 0)).isEqualTo(DrawingGeometry.Outcome.NothingToSave)
        assertThat(DrawingGeometry.outcome(changed = true, strokeCount = 2)).isEqualTo(DrawingGeometry.Outcome.Save)
    }

    @Test fun backDiscardsAndOnlyAsksWhenSomethingWouldBeLost() {
        assertThat(DrawingGeometry.discardOutcome(changed = false, strokeCount = 0)).isEqualTo(DrawingGeometry.DiscardOutcome.Silent)
        assertThat(DrawingGeometry.discardOutcome(changed = true, strokeCount = 0)).isEqualTo(DrawingGeometry.DiscardOutcome.Silent)
        assertThat(DrawingGeometry.discardOutcome(changed = true, strokeCount = 3)).isEqualTo(DrawingGeometry.DiscardOutcome.Confirm)
        // A re-edit with no new strokes leaves the existing drawing alone.
        assertThat(DrawingGeometry.discardOutcome(changed = false, strokeCount = 5)).isEqualTo(DrawingGeometry.DiscardOutcome.Silent)
    }
}
