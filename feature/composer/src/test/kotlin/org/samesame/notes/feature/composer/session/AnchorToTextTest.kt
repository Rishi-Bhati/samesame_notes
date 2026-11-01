package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.ObjectLimits
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun

class AnchorToTextTest {
    @Test fun onlyImagesDrawingsAndVoiceAnchor() {
        assertThat(AnchorToText.supports(ImageObject("i", "media/a.png", 10f, 10f))).isTrue()
        assertThat(AnchorToText.supports(PaintingObject("p", "media/a.png", "media/a.drw", RectPu(0f, 0f, 1f, 1f)))).isTrue()
        assertThat(AnchorToText.supports(VoiceObject("v", "media/a.m4a", 1000L))).isTrue()
        assertThat(AnchorToText.supports(TextBoxObject("t", RichText()))).isFalse()
    }

    @Test fun lineFeedsReachAnObjectBelowTheText() {
        assertThat(AnchorToText.lineFeedsNeeded(textBottom = 500f, targetTop = 400f, lineHeight = 60f)).isEqualTo(0)
        assertThat(AnchorToText.lineFeedsNeeded(500f, 500f, 60f)).isEqualTo(0)
        assertThat(AnchorToText.lineFeedsNeeded(500f, 501f, 60f)).isEqualTo(1)
        assertThat(AnchorToText.lineFeedsNeeded(500f, 690f, 60f)).isEqualTo(4)
    }

    @Test fun anImageGoesInlineAtTheSizeItWasShown() {
        val f = FloatingObject(ImageObject("i", "media/a.png", 800f, 600f), RectPu(10f, 20f, 410f, 320f))
        val inline = AnchorToText.inlineCopy(f) as ImageObject
        assertThat(inline.widthPu).isEqualTo(400f)
        assertThat(inline.heightPu).isEqualTo(300f)
        assertThat(inline.id).isEqualTo("i")
    }

    @Test fun removingTheAnchorKeepsTheRatioAtTheShownWidth() {
        val img = ImageObject("i", "media/a.png", 800f, 600f)
        val f = AnchorToText.floatingFor(img, 100f, 200f, shownWidth = 400f)
        assertThat(f.rect).isEqualTo(RectPu(100f, 200f, 500f, 500f))
        assertThat(f.obj).isEqualTo(img)
        val voice = AnchorToText.floatingFor(VoiceObject("v", "media/a.m4a", 1L), 0f, 0f, 0f)
        assertThat(voice.rect.width).isEqualTo(ObjectLimits.VOICE_WIDTH_PU)
    }

    @Test fun objectOffsetsAndTheRemoveAnchorCondition() {
        val o = OBJECT_CHAR
        val rt = RichText("ab$o$o", listOf(TextRun(2, CharStyle()), TextRun(1, CharStyle(objectId = "x")), TextRun(1, CharStyle(objectId = "y"))))
        assertThat(AnchorToText.objectOffsets(rt, 10)).containsExactly(12 to "x", 13 to "y").inOrder()
        assertThat(AnchorToText.onlyObjects(rt)).isFalse()
        val only = RichText("$o", listOf(TextRun(1, CharStyle(objectId = "x"))))
        assertThat(AnchorToText.onlyObjects(only)).isTrue()
        assertThat(AnchorToText.onlyObjects(RichText())).isFalse()
    }

    // "ab<obj>cd": the object is offset 2.
    private val objAt2: (Int) -> Boolean = { it == 2 }

    @Test fun aTapOnEitherHalfOfAnInlineObjectFindsIt() {
        // Left half: the hit test gives the offset before the object; right half: the one after.
        assertThat(AnchorToText.objectOffsetAt(hit = 2, length = 5, isObject = objAt2, contains = { true })).isEqualTo(2)
        assertThat(AnchorToText.objectOffsetAt(hit = 3, length = 5, isObject = objAt2, contains = { true })).isEqualTo(2)
    }

    @Test fun aTapOnTextBesideAnInlineObjectIsNotTheObject() {
        // The hit lands next to the object but outside its drawn bounds.
        assertThat(AnchorToText.objectOffsetAt(hit = 3, length = 5, isObject = objAt2, contains = { false })).isNull()
        // Plain text far from the object.
        assertThat(AnchorToText.objectOffsetAt(hit = 5, length = 5, isObject = objAt2, contains = { true })).isNull()
        assertThat(AnchorToText.objectOffsetAt(hit = 0, length = 5, isObject = objAt2, contains = { true })).isNull()
    }

    @Test fun objectHitTestingHandlesEdgesAndEmptyBodies() {
        assertThat(AnchorToText.objectOffsetAt(hit = 0, length = 0, isObject = { true }, contains = { true })).isNull()
        // An object as the last character; the hit test may report the end of the text (or past it).
        assertThat(AnchorToText.objectOffsetAt(hit = 1, length = 1, isObject = { it == 0 }, contains = { true })).isEqualTo(0)
        assertThat(AnchorToText.objectOffsetAt(hit = 9, length = 1, isObject = { it == 0 }, contains = { true })).isEqualTo(0)
        assertThat(AnchorToText.objectOffsetAt(hit = -3, length = 1, isObject = { it == 0 }, contains = { true })).isEqualTo(0)
        // Two adjacent objects: the one after the hit offset wins (the left half of the second object).
        assertThat(AnchorToText.objectOffsetAt(hit = 1, length = 2, isObject = { true }, contains = { true })).isEqualTo(1)
        assertThat(AnchorToText.objectOffsetAt(hit = 1, length = 2, isObject = { true }, contains = { it == 0 })).isEqualTo(0)
    }

    @Test fun selectionMenuOffersRemoveAnchorWheneverTheSelectionHoldsAnObject() {
        val o = OBJECT_CHAR
        val mixed = RichText("ab$o", listOf(TextRun(2, CharStyle()), TextRun(1, CharStyle(objectId = "x"))))
        val only = RichText("$o", listOf(TextRun(1, CharStyle(objectId = "x"))))
        val text = RichText("abc", listOf(TextRun(3, CharStyle())))
        assertThat(AnchorToText.selectionMenu(viewState = false, textMode = true, slice = only)).isEqualTo(AnchorToText.SelectionMenu.InlineObjects)
        assertThat(AnchorToText.selectionMenu(false, true, mixed)).isEqualTo(AnchorToText.SelectionMenu.InlineObjects)
        assertThat(AnchorToText.selectionMenu(false, true, text)).isEqualTo(AnchorToText.SelectionMenu.None)
        // Pen mode etc.: no Remove anchor.
        assertThat(AnchorToText.selectionMenu(false, false, only)).isEqualTo(AnchorToText.SelectionMenu.None)
        // View state: the read-only rows, even over an object.
        assertThat(AnchorToText.selectionMenu(true, false, only)).isEqualTo(AnchorToText.SelectionMenu.ReadOnly)
        assertThat(AnchorToText.selectionMenu(true, false, RichText())).isEqualTo(AnchorToText.SelectionMenu.None)
        // A stray U+FFFC without an object id is not an anchored object.
        val stray = RichText("$o", listOf(TextRun(1, CharStyle())))
        assertThat(AnchorToText.selectionMenu(false, true, stray)).isEqualTo(AnchorToText.SelectionMenu.None)
    }
}
