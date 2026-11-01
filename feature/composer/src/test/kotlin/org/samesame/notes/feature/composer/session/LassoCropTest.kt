package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.Pt
import org.samesame.notes.core.model.document.RectPu

class LassoCropTest {
    private val square = listOf(Pt(0.2f, 0.2f), Pt(0.6f, 0.2f), Pt(0.6f, 0.7f), Pt(0.2f, 0.7f))

    @Test fun boundsOfTheOutlineAreTheKeptBox() {
        val c = LassoCrop.bounds(square, 0.02f, 0.02f)!!
        assertThat(c.left).isEqualTo(0.2f); assertThat(c.top).isEqualTo(0.2f)
        assertThat(c.right).isEqualTo(0.6f); assertThat(c.bottom).isEqualTo(0.7f)
        assertThat(LassoCrop.area(square)).isWithin(1e-5f).of(0.2f)
    }

    @Test fun degenerateOutlinesAreRefused() {
        assertThat(LassoCrop.bounds(square.take(2), 0.02f, 0.02f)).isNull()
        // A straight scribble: a box but no area.
        assertThat(LassoCrop.bounds(listOf(Pt(0.1f, 0.1f), Pt(0.5f, 0.5f), Pt(0.9f, 0.9f)), 0.02f, 0.02f)).isNull()
        assertThat(LassoCrop.bounds(listOf(Pt(0.1f, 0.1f), Pt(0.11f, 0.1f), Pt(0.11f, 0.11f)), 0.02f, 0.02f)).isNull()
    }

    @Test fun pointsStayInsideTheImageAndThinOut() {
        assertThat(LassoCrop.clamp(Pt(-0.2f, 1.4f))).isEqualTo(Pt(0f, 1f))
        assertThat(LassoCrop.accepts(null, Pt(0f, 0f))).isTrue()
        assertThat(LassoCrop.accepts(Pt(0.5f, 0.5f), Pt(0.501f, 0.501f))).isFalse()
        assertThat(LassoCrop.accepts(Pt(0.5f, 0.5f), Pt(0.51f, 0.5f))).isTrue()
    }

    @Test fun outlineIsMappedIntoTheKeptBox() {
        val c = LassoCrop.bounds(square, 0.02f, 0.02f)!!
        val inside = LassoCrop.inCrop(square, c)
        assertThat(inside.first()).isEqualTo(Pt(0f, 0f))
        assertThat(inside[2].x).isWithin(1e-5f).of(1f)
        assertThat(inside[2].y).isWithin(1e-5f).of(1f)
    }

    @Test fun drawingCropKeepsItsStrokesAndNarrowsTheSheetRegion() {
        val f = FloatingObject(PaintingObject("p", "media/a.png", "media/a.drw", RectPu(100f, 200f, 500f, 600f)), RectPu(0f, 0f, 200f, 200f))
        val out = ImageCrop.apply(f, CropFraction(0.25f, 0f, 0.75f, 0.5f), "media/b.png")
        val p = out.obj as PaintingObject
        assertThat(p.media).isEqualTo("media/b.png")
        assertThat(p.ink).isEqualTo("media/a.drw")
        assertThat(p.content).isEqualTo(RectPu(200f, 200f, 400f, 400f))
        assertThat(out.rect).isEqualTo(RectPu(50f, 0f, 150f, 100f))
    }

    @Test fun editedImageKeepsWidthAndCentreAndTakesTheNewRatio() {
        val f = FloatingObject(ImageObject("i", "media/a.jpg", 400f, 300f), RectPu(100f, 100f, 300f, 250f), rotationDeg = 30f)
        val out = ImageReplace.apply(f, "media/b.jpg", 1000, 1000)
        assertThat(out.rect).isEqualTo(RectPu(100f, 75f, 300f, 275f))
        assertThat(out.rotationDeg).isEqualTo(30f)
        val img = out.obj as ImageObject
        assertThat(img.media).isEqualTo("media/b.jpg")
        assertThat(img.widthPu).isEqualTo(400f)
        assertThat(img.heightPu).isEqualTo(400f)
    }
}
