package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchQueryTextTest {

    @Test fun typedTextIsUntouched() {
        assertThat(singleLineQuery("Numbered A")).isEqualTo("Numbered A")
        assertThat(singleLineQuery("")).isEmpty()
    }

    /** E4 (rc1 round 5): a pasted multi-line note body becomes one line, list markers included. */
    @Test fun pastedLinesBecomeOneLine() {
        assertThat(singleLineQuery("Numbered A\n\n\n\n\none\n1. Two\n2. three")).isEqualTo("Numbered A one 1. Two 2. three")
        assertThat(singleLineQuery("a\r\nb\rc d")).isEqualTo("a b c d")
    }
}
