package org.samesame.notes.core.model.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** E4: the plain-text marker rules shared by the .txt export and the system clipboard. */
class ListMarkerPrefixTest {

    @Test fun `plain paragraphs get no marker`() {
        assertThat(listMarkerPrefix(ParagraphStyle(), null)).isEmpty()
    }

    @Test fun `bullet and numbered markers`() {
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Bullet), null)).isEqualTo("• ")
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Numbered), 3)).isEqualTo("3. ")
        // No number available falls back to 1, like the export always did.
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Numbered), null)).isEqualTo("1. ")
    }

    @Test fun `checklist markers follow the checked state`() {
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Checklist), null)).isEqualTo("[ ] ")
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Checklist, checked = true), null)).isEqualTo("[x] ")
    }

    @Test fun `indentation is two spaces per level and negative levels are clamped`() {
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Bullet, indent = 2), null)).isEqualTo("    • ")
        assertThat(listMarkerPrefix(ParagraphStyle(list = ListKind.Bullet, indent = -3), null)).isEqualTo("• ")
    }
}
