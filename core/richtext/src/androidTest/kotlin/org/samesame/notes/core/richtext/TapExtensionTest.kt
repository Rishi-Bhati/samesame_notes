package org.samesame.notes.core.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.layout.SimplePageGeometry

@RunWith(AndroidJUnit4::class)
class TapExtensionTest {

    private val pages = SimplePageGeometry(pageCount = 4, pageHeight = 400f)

    @Test fun tappingBelowTheTextExtendsTheBodyToThatLine() {
        val body = BodyText(RichText.of("one\ntwo"))
        val e = BodyTextEngine(body, pages)
        val before = body.length
        val end = e.caret(before)
        val content = pages.contentRect(0)

        val offset = e.hitTestForTap(content.left + 100f, end.bottom + 40f)

        assertThat(offset).isEqualTo(body.length)
        assertThat(body.length).isGreaterThan(before)
        val after = e.caret(body.length)
        assertThat(after.pageIndex).isEqualTo(0)
        assertThat(after.top).isAtLeast(end.bottom)
    }

    @Test fun tappingALaterPagePutsTheCaretThere() {
        val body = BodyText(RichText.of("one\ntwo"))
        val e = BodyTextEngine(body, pages)
        val target = pages.contentRect(2)
        val tapY = target.top + target.height / 2f

        val offset = e.hitTestForTap(target.left + 100f, tapY)

        assertThat(offset).isEqualTo(body.length)
        val caret = e.caret(offset)
        assertThat(caret.pageIndex).isEqualTo(2)
        assertThat(caret.bottom).isAtLeast(tapY)
        // The text now needs every page up to the tapped one, so it survives reopen/export on that page.
        assertThat(e.requiredPageCount).isEqualTo(3)
    }

    @Test fun tappingTextDoesNotAddLines() {
        val body = BodyText(RichText.of("one\ntwo"))
        val e = BodyTextEngine(body, pages)
        val before = body.length
        val c = e.caret(0)

        val offset = e.hitTestForTap(c.x + 1f, (c.top + c.bottom) / 2f)

        assertThat(offset).isEqualTo(0)
        assertThat(body.length).isEqualTo(before)
    }
}
