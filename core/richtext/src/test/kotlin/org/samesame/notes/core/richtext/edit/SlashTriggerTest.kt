package org.samesame.notes.core.richtext.edit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlashTriggerTest {
    private fun m(text: String) = SlashTrigger.detect(text)

    @Test fun bareToken() = assertThat(m("///")).isEqualTo(SlashTrigger.Match(0, ""))

    @Test fun withQuery() = assertThat(m("///pho")).isEqualTo(SlashTrigger.Match(0, "pho"))

    @Test fun afterText() = assertThat(m("a ///x")).isEqualTo(SlashTrigger.Match(2, "x"))

    @Test fun caretFollowsQuery() = assertThat(m("a ///x")?.caret).isEqualTo(6)

    @Test fun queryWithSingleSpace() = assertThat(m("///my pho")).isEqualTo(SlashTrigger.Match(0, "my pho"))

    @Test fun secondSpaceCloses() = assertThat(m("///my  ")).isNull()

    @Test fun fileScheme() = assertThat(m("file:///")).isNull()

    @Test fun httpScheme() = assertThat(m("see http:///")).isNull()

    @Test fun plusScheme() = assertThat(m("x+y:///")).isNull()

    @Test fun schemeWithQuery() = assertThat(m("file:///sdcard")).isNull()

    @Test fun fourSlashes() = assertThat(m("////")).isNull()

    @Test fun fiveSlashes() = assertThat(m("/////")).isNull()

    @Test fun slashRunAfterTextIsNotAToken() = assertThat(m("a //// b")).isNull()

    @Test fun colonAfterSpaceIsNotAScheme() = assertThat(m(": ///")).isEqualTo(SlashTrigger.Match(2, ""))

    @Test fun previousLineIgnored() = assertThat(m("///a\nb")).isNull()

    @Test fun tokenOnNewLine() = assertThat(m("one\n///")).isEqualTo(SlashTrigger.Match(4, ""))

    @Test fun noToken() = assertThat(m("//")).isNull()

    @Test fun latestTokenWins() = assertThat(m("///one then ///two")).isEqualTo(SlashTrigger.Match(12, "two"))
}
