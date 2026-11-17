package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharedTextTest {
    @Test fun urlStaysAUrlAndSubjectIsTheTitle() {
        val t = SharedText.of("Page title", listOf("https://example.com/a?b=1"))!!
        assertThat(t.title).isEqualTo("Page title")
        assertThat(t.body).isEqualTo("https://example.com/a?b=1")
    }

    @Test fun noSubjectMeansUntitled() {
        assertThat(SharedText.of(null, listOf("hello"))!!.title).isEmpty()
        assertThat(SharedText.of("  ", listOf("hello"))!!.title).isEmpty()
    }

    @Test fun blankIsNothing() {
        assertThat(SharedText.of("s", listOf(null, "  \n "))).isNull()
        assertThat(SharedText.of("s", emptyList())).isNull()
    }

    @Test fun multipleTextsAndLineBreaks() {
        val t = SharedText.of(null, listOf("a\r\nb", null, "c"))!!
        assertThat(t.body).isEqualTo("a\nb\n\nc")
    }
}
