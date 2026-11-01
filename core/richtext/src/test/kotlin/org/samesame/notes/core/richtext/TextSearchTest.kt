package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextSearchTest {
    @Test fun emptyQueryHasNoHits() {
        assertThat(TextSearch.find("abc", "")).isEmpty()
    }

    @Test fun caseInsensitiveInOrder() {
        assertThat(TextSearch.find("Foo bar FOO\nfoo", "foo")).containsExactly(0..2, 8..10, 12..14).inOrder()
    }

    @Test fun hitsDoNotOverlap() {
        assertThat(TextSearch.find("aaaa", "aa")).containsExactly(0..1, 2..3).inOrder()
    }

    @Test fun queryLongerThanTextOrAbsent() {
        assertThat(TextSearch.find("ab", "abc")).isEmpty()
        assertThat(TextSearch.find("hello", "xyz")).isEmpty()
    }

    @Test fun hitAtTheVeryEnd() {
        assertThat(TextSearch.find("see end", "end")).containsExactly(4..6)
    }

    @Test fun queryIsUsedAsTyped() {
        assertThat(TextSearch.find("a b  c", " ")).hasSize(3)
        assertThat(TextSearch.find("a.b", ".")).containsExactly(1..1)
    }
}
