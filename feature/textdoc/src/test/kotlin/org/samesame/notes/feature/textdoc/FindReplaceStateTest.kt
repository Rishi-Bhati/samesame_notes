package org.samesame.notes.feature.textdoc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FindReplaceStateTest {

    @Test
    fun findsEveryHitIgnoringCase() {
        val hits = FindReplace.matches("One two one", "one")

        assertThat(hits).containsExactly(0 until 3, 8 until 11).inOrder()
    }

    @Test
    fun navigationWrapsAround() {
        assertThat(FindReplace.step(0, 3, forward = true)).isEqualTo(1)
        assertThat(FindReplace.step(2, 3, forward = true)).isEqualTo(0)
        assertThat(FindReplace.step(0, 3, forward = false)).isEqualTo(2)
        assertThat(FindReplace.step(-1, 3, forward = true)).isEqualTo(0)
        assertThat(FindReplace.step(0, 0, forward = true)).isEqualTo(-1)
    }

    @Test
    fun replacesTheActiveMatch() {
        val text = "one two one"
        val hits = FindReplace.matches(text, "one")

        assertThat(FindReplace.replaceCurrent(text, hits[1], "1")).isEqualTo("one two 1")
    }

    @Test
    fun replacesEveryMatchCaseInsensitively() {
        assertThat(FindReplace.replaceAll("One two one ONE", "one", "1")).isEqualTo("1 two 1 1")
    }

    @Test
    fun aBlankQueryFindsAndReplacesNothing() {
        assertThat(FindReplace.matches("abc", "")).isEmpty()
        assertThat(FindReplace.replaceAll("abc", "", "x")).isEqualTo("abc")
    }

    @Test
    fun hitsAreCapped() {
        assertThat(FindReplace.matches("aaaa", "aa", limit = 2)).hasSize(2)
    }
}
