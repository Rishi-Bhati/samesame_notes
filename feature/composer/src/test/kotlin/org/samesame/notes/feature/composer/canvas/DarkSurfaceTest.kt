package org.samesame.notes.feature.composer.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef

/**
 * Issue 7: on a PDF page (a picture as the page background) in a dark note the ink and text colours were adjusted for a dark
 * page, so black pen strokes and light text sat on white paper and could not be seen. Such pages must not take the adjustment.
 */
class DarkSurfaceTest {
    private val plain = PageSpec("a")
    private val ruled = PageSpec("b", TemplateRef.Builtin(PageTemplate.Blank))
    private val pdf = PageSpec("c", TemplateRef.Image("media/a.png"))

    @Test fun plainPagesFollowTheDarkAdjustment() {
        assertThat(adjustsForDark(plain, darkAdjust = true)).isTrue()
        assertThat(adjustsForDark(ruled, darkAdjust = true)).isTrue()
        assertThat(adjustsForDark(plain, darkAdjust = false)).isFalse()
    }

    @Test fun picturePagesNeverTakeIt() {
        assertThat(adjustsForDark(pdf, darkAdjust = true)).isFalse()
        assertThat(adjustsForDark(pdf, darkAdjust = false)).isFalse()
    }

    @Test fun aPicturePageKeepsALightSurfaceInADarkNote() {
        val dark = pageColor(pdf, null, darkTheme = true, darkAdjust = adjustsForDark(pdf, true))
        val normal = pageColor(plain, null, darkTheme = true, darkAdjust = adjustsForDark(plain, true))
        assertThat(dark).isEqualTo(CanvasColors.LightPage)
        assertThat(normal).isEqualTo(CanvasColors.DarkPage)
    }
}
