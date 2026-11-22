package org.samesame.notes.core.richtext.spans

import android.text.SpannableStringBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.edit.math.MathConfig
import org.samesame.notes.core.richtext.edit.math.MathDetector

/** Arrows are drawn bigger and bold by a display-only span; the text, runs and offsets never change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArrowGlyphSpanTest {

    /** Offsets covered by an [ArrowGlyphSpan], each checked to be exactly one character long. */
    private fun arrowOffsets(text: CharSequence): List<Int> {
        val sp = text as android.text.Spanned
        return sp.getSpans(0, sp.length, ArrowGlyphSpan::class.java).map {
            val s = sp.getSpanStart(it)
            assertThat(sp.getSpanEnd(it) - s).isEqualTo(1)
            s
        }.sorted()
    }

    private fun expectedOffsets(text: String) = text.indices.filter { ArrowGlyphs.isStyledArrowAt(text, it) }

    @Test fun everyShortcutArrowAndItsSiblingsAreArrows() {
        for (c in "→←↔⇒⇔↑↓⇐⇑⇓↕⇕↖↗↘↙") assertThat(ArrowGlyphs.isArrow(c)).isTrue()
        for (c in "->=<abc 1+*–—↠⟶😀") assertThat(ArrowGlyphs.isArrow(c)).isFalse()
    }

    @Test fun openingANoteStylesExactlyTheArrows() {
        val text = "Udupi → Mangalore ⇒ done ← ↔ ⇔ ↑↓ ⇐ plain -> text"
        val body = BodyText(RichText.of(text))
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(6, 18, 25, 27, 29, 31, 32, 34))
        assertThat(arrowOffsets(body.editable)).isEqualTo(expectedOffsets(text))
        assertThat(body.toRichText()).isEqualTo(RichText.of(text))
    }

    @Test fun arrowsNextToEmojiKeepTheirOffsetsAndTheEmojiIsUntouched() {
        val text = "😀→😀←👍⇒"
        val body = BodyText(RichText.of(text))
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(2, 5, 8))
        assertThat(body.editable.toString()).isEqualTo(text)
        assertThat(body.length).isEqualTo(text.length)
    }

    @Test fun emojiPresentationArrowIsLeftAlone() {
        val body = BodyText(RichText.of("a ↔️ b ↔"))
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(7))
        // Removing the selector makes it a text arrow again.
        body.setSelection(4)
        body.deleteBackward()
        assertThat(body.editable.toString()).isEqualTo("a ↔ b ↔")
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(2, 6))
    }

    @Test fun typedShortcutGetsTheSpanAndBackspaceRestoresPlainText() {
        val body = BodyText()
        body.setSelection(0)
        for (c in "a -") body.commitText(c.toString())
        body.commitText(">")
        assertThat(body.editable.toString()).isEqualTo("a →")
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(2))
        body.deleteBackward()
        assertThat(body.editable.toString()).isEqualTo("a ->")
        assertThat(arrowOffsets(body.editable)).isEmpty()
        assertThat(body.selection.min).isEqualTo(4)
    }

    @Test fun typingAroundAnArrowDoesNotSpreadTheSpan() {
        val body = BodyText(RichText.of("a→b"))
        body.setSelection(2)
        body.commitText("x")
        body.setSelection(1)
        body.commitText("y")
        assertThat(body.editable.toString()).isEqualTo("ay→xb")
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(2))
        body.setSelection(3)
        body.deleteBackward()
        assertThat(body.editable.toString()).isEqualTo("ayxb")
        assertThat(arrowOffsets(body.editable)).isEmpty()
    }

    @Test fun pastedAndUndoneArrowsStayInStep() {
        val events = ArrayList<TextEditEvent>()
        val body = BodyText(RichText.of("start "))
        body.addEditListener { events += it }
        body.setSelection(6)
        body.insertRich(RichText.of("x ⇒ y ⇔ z"))
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(8, 12))
        body.undo(events.last())
        assertThat(body.editable.toString()).isEqualTo("start ")
        assertThat(arrowOffsets(body.editable)).isEmpty()
        body.redo(events.last())
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(8, 12))
    }

    @Test fun explicitRunStylesAreKeptAndNotTouchedByTheSpan() {
        val bold = CharStyle(bold = true, fontSize = 30f)
        val rt = RichText("a → b", listOf(TextRun(2, CharStyle()), TextRun(1, bold), TextRun(2, CharStyle())))
        val body = BodyText(rt)
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(2))
        assertThat(body.toRichText()).isEqualTo(RichTextOps.normalise(rt))
        assertThat(body.toRichText().runs.map { it.style }).contains(bold)
        assertThat(body.slice(0, 5).text).isEqualTo("a → b")
        assertThat(body.plainTextWithListMarkers(0, 5)).isEqualTo("a → b")
    }

    @Test fun mathGhostIsUnaffected() {
        val text = "Udupi → Mangalore: 100*7 ="
        val body = BodyText(RichText.of(text))
        assertThat(arrowOffsets(body.editable)).isEqualTo(listOf(6))
        assertThat(MathDetector.suggest(body.editable, body.length, MathConfig.English)?.text).isEqualTo(" 700")
    }

    @Test fun refreshRemovesStaleSpansAndIsIdempotent() {
        val ssb = SpannableStringBuilder("a→b")
        ArrowGlyphs.refresh(ssb, 0, ssb.length)
        ArrowGlyphs.refresh(ssb, 0, ssb.length)
        assertThat(arrowOffsets(ssb)).isEqualTo(listOf(1))
        // A stray span on a non-arrow character is dropped.
        ssb.setSpan(ArrowGlyphSpan(), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ArrowGlyphs.refresh(ssb, 0, ssb.length)
        assertThat(arrowOffsets(ssb)).isEqualTo(listOf(1))
    }

    @Test fun arrowPaintIsRelativeToTheRunSizeAndLeavesTheRunPaintAlone() {
        // Robolectric's Paint doesn't keep typeface/fake-bold state, so the weight is checked on the device; the size is not.
        assertThat(ArrowGlyphSpan.SCALE).isIn(com.google.common.collect.Range.closed(1.15f, 1.2f))
        for (size in listOf(40f, 75f)) {
            val base = android.text.TextPaint().apply { textSize = size; isUnderlineText = true }
            val p = ArrowGlyphSpan.arrowPaint(base)
            assertThat(p.textSize).isWithin(0.01f).of(size * ArrowGlyphSpan.SCALE)
            assertThat(p).isNotSameInstanceAs(base)
            assertThat(base.textSize).isEqualTo(size)
        }
    }
}
