package org.samesame.notes.core.export

import org.junit.Test
import org.samesame.notes.core.export.render.DARK_PREVIEW_PAGE_ARGB
import org.samesame.notes.core.export.render.previewPageColor

class PreviewPageColorTest {

    private val identity: (Int) -> Int = { it }
    private val invert: (Int) -> Int = { c ->
        // DarkModeInk.invert logic (pure arithmetic, no Android dep)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val shift = 255 - maxOf(r, g, b) - minOf(r, g, b)
        (c and 0xFF000000.toInt()) or ((r + shift) shl 16) or ((g + shift) shl 8) or (b + shift)
    }

    @Test fun `no stored colour + dark = the reference grey`() {
        val result = previewPageColor(null, darkTheme = true, identity)
        assert(result == DARK_PREVIEW_PAGE_ARGB) {
            "Expected #171717 (${DARK_PREVIEW_PAGE_ARGB.toUInt().toString(16)}), got ${result.toUInt().toString(16)}"
        }
    }

    @Test fun `no stored colour + light = white`() {
        val white = 0xFFFFFFFF.toInt()
        val result = previewPageColor(null, darkTheme = false, identity)
        assert(result == white) {
            "Expected white (ffffffff), got ${result.toUInt().toString(16)}"
        }
    }

    @Test fun `stored default white + dark = the reference grey`() {
        val resultWhite = previewPageColor(0xFFFFFFFFL, darkTheme = true, invert)
        assert(resultWhite == DARK_PREVIEW_PAGE_ARGB) {
            "Expected #171717 for 0xFFFFFFFF, got ${resultWhite.toUInt().toString(16)}"
        }
        val resultReferenceWhite = previewPageColor(0xFFFCFCFCL, darkTheme = true, invert)
        assert(resultReferenceWhite == DARK_PREVIEW_PAGE_ARGB) {
            "Expected #171717 for 0xFFFCFCFC, got ${resultReferenceWhite.toUInt().toString(16)}"
        }
    }

    @Test fun `stored custom colour + dark = inverted`() {
        val beige = 0xFFF4E4D7L
        val result = previewPageColor(beige, darkTheme = true, invert)
        val expected = invert(beige.toInt() or (0xFF shl 24))
        assert(result == expected) {
            "Expected inverted beige ($expected), got $result"
        }
    }

    @Test fun `stored custom colour + light = identity`() {
        val beige = 0xFFF4E4D7L
        val result = previewPageColor(beige, darkTheme = false, identity)
        assert(result == beige.toInt() or (0xFF shl 24)) {
            "Expected beige passthrough, got ${result.toUInt().toString(16)}"
        }
    }

    @Test fun `DARK_PREVIEW_PAGE_ARGB is 0xFF171717`() {
        assert(DARK_PREVIEW_PAGE_ARGB == 0xFF171717.toInt()) {
            "Constant mismatch: ${DARK_PREVIEW_PAGE_ARGB.toUInt().toString(16)}"
        }
    }
}
