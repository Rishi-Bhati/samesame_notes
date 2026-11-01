package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StaticTextVersionTest {
    private val layout = Any()

    @Test fun sameInputsKeepTheVersion() {
        val v = StaticTextVersion()
        val first = v.update(3, 1, layout, links = true, hint = null)
        assertThat(v.update(3, 1, layout, links = true, hint = null)).isEqualTo(first)
    }

    @Test fun everyInputBumpsIt() {
        val v = StaticTextVersion()
        var last = v.update(3, 1, layout, links = true, hint = null)
        fun bumped(next: Long): Boolean = (next > last).also { last = next }
        assertThat(bumped(v.update(4, 1, layout, true, null))).isTrue() // text / spans
        assertThat(bumped(v.update(4, 2, layout, true, null))).isTrue() // colours / geometry
        assertThat(bumped(v.update(4, 2, Any(), true, null))).isTrue() // new layout object
        val l2 = Any()
        v.update(4, 2, l2, true, null); last = v.version
        assertThat(bumped(v.update(4, 2, l2, false, null))).isTrue() // links off
        assertThat(bumped(v.update(4, 2, l2, false, "Hint"))).isTrue() // hint shown
        assertThat(bumped(v.update(4, 2, l2, false, "Hint"))).isFalse()
    }

    @Test fun differentStatesNeverShareAVersion() {
        // A hash of (layoutVersion, drawRevision) could collide, (1, 0) vs (0, 31); a counter cannot.
        val v = StaticTextVersion()
        val seen = HashSet<Long>()
        for (lv in 0L..5L) for (dr in 0L..5L) seen += v.update(lv, dr, layout, false, null)
        assertThat(seen).hasSize(36)
    }

    @Test fun equalHintTextIsTheSameHint() {
        val v = StaticTextVersion()
        val a = v.update(0, 0, layout, false, String(charArrayOf('H', 'i')))
        assertThat(v.update(0, 0, layout, false, "Hi")).isEqualTo(a)
    }
}
