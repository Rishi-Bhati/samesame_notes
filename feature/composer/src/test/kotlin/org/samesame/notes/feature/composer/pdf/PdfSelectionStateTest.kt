package org.samesame.notes.feature.composer.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.pdf.PdfGlyph
import org.samesame.notes.core.model.pdf.PdfLine
import org.samesame.notes.core.model.pdf.PdfLinkAnnotation
import org.samesame.notes.core.model.pdf.PdfLinkDestination
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.PdfWord
import org.samesame.notes.core.model.pdf.RectPt

class PdfSelectionStateTest {

    private class FakeClipboard : ClipboardManager {
        var storedText: AnnotatedString? = null
        override fun getText(): AnnotatedString? = storedText
        override fun setText(annotatedString: AnnotatedString) {
            storedText = annotatedString
        }
        override fun hasText(): Boolean = storedText != null
    }

    private fun samplePageText(): PdfPageText {
        // "Hello World"
        val text = "Hello World"
        val glyphs = mutableListOf<PdfGlyph>()
        var x = 50f
        for ((i, ch) in text.withIndex()) {
            val bounds = if (ch.isWhitespace()) {
                RectPt(0f, 0f, 0f, 0f)
            } else {
                val b = RectPt(x, 100f, x + 10f, 120f)
                x += 10f
                b
            }
            if (ch.isWhitespace()) x += 5f
            glyphs.add(PdfGlyph(ch, bounds, i))
        }

        val word1 = PdfWord("Hello", RectPt(50f, 100f, 100f, 120f), 0, 5)
        val word2 = PdfWord("World", RectPt(105f, 100f, 155f, 120f), 6, 11)
        val line = PdfLine("Hello World", RectPt(50f, 100f, 155f, 120f), listOf(word1, word2))

        val link = PdfLinkAnnotation(
            boundsPt = RectPt(50f, 100f, 100f, 120f),
            destination = PdfLinkDestination.External("https://example.com"),
        )

        return PdfPageText(
            pageIndex = 0,
            text = text,
            glyphs = glyphs,
            lines = listOf(line),
            links = listOf(link),
        )
    }

    @Test
    fun hitTestWordIdentifiesTargetWord() {
        val state = PdfSelectionState()
        val pageText = samplePageText()

        val hit1 = state.hitTestWord(pageText, 60f, 110f)
        assertThat(hit1).isNotNull()
        assertThat(hit1!!.text).isEqualTo("Hello")

        val hit2 = state.hitTestWord(pageText, 120f, 110f)
        assertThat(hit2).isNotNull()
        assertThat(hit2!!.text).isEqualTo("World")

        val miss = state.hitTestWord(pageText, 20f, 20f)
        assertThat(miss).isNull()
    }

    @Test
    fun hitTestLinkIdentifiesLink() {
        val state = PdfSelectionState()
        val pageText = samplePageText()

        val linkHit = state.hitTestLink(pageText, 60f, 110f)
        assertThat(linkHit).isNotNull()
        assertThat((linkHit!!.destination as PdfLinkDestination.External).uri).isEqualTo("https://example.com")

        val linkMiss = state.hitTestLink(pageText, 200f, 200f)
        assertThat(linkMiss).isNull()
    }

    @Test
    fun selectWordSetsInitialSelection() {
        val state = PdfSelectionState()
        val pageText = samplePageText()

        val word = pageText.lines[0].words[0]
        state.selectWord(0, word)

        assertThat(state.hasSelection).isTrue()
        assertThat(state.selectedPage).isEqualTo(0)
        assertThat(state.selectionRange).isEqualTo(0 until 5)
        assertThat(state.selectedText(pageText)).isEqualTo("Hello")
    }

    @Test
    fun handleDragExpandsSelectionRange() {
        val state = PdfSelectionState()
        val pageText = samplePageText()

        state.selectWord(0, pageText.lines[0].words[0]) // "Hello", 0..4
        assertThat(state.selectionRange).isEqualTo(0 until 5)

        // Drag end handle to the center of 'd' in "World" (index 10, approx x = 150)
        state.isDraggingHandle = true
        state.isStartHandle = false
        state.updateDrag(pageText, 150f, 110f)

        assertThat(state.selectionRange).isEqualTo(0 until 11)
        assertThat(state.selectedText(pageText)).isEqualTo("Hello World")
    }

    @Test
    fun copyToClipboardCopiesSelectedText() {
        val state = PdfSelectionState()
        val pageText = samplePageText()
        val clip = FakeClipboard()

        state.selectWord(0, pageText.lines[0].words[1]) // "World"
        val copied = state.copyToClipboard(clip, pageText)

        assertThat(copied).isEqualTo("World")
        assertThat(clip.storedText?.text).isEqualTo("World")
    }

    @Test
    fun handleHitTestingDetectsHandles() {
        val state = PdfSelectionState()
        val startHandle = Offset(100f, 200f)
        val endHandle = Offset(300f, 200f)

        assertThat(state.hitTestHandle(Offset(105f, 205f), startHandle, endHandle)).isTrue()
        assertThat(state.hitTestHandle(Offset(295f, 195f), startHandle, endHandle)).isFalse()
        assertThat(state.hitTestHandle(Offset(500f, 500f), startHandle, endHandle)).isNull()
    }
}
