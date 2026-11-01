package org.samesame.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportDedupTest {
    @Test fun remembersAndForgets() {
        val d = ImportDedup<String, String>()
        assertThat(d.get("content://a")).isNull()
        d.put("content://a", "note1")
        assertThat(d.get("content://a")).isEqualTo("note1")
        d.forget("content://a")
        assertThat(d.get("content://a")).isNull()
    }

    @Test fun keepsOnlyTheMostRecentlyUsed() {
        val d = ImportDedup<Int, Int>(capacity = 2)
        d.put(1, 1); d.put(2, 2)
        d.get(1) // touch 1, so 2 is the eldest
        d.put(3, 3)
        assertThat(d.get(2)).isNull()
        assertThat(d.get(1)).isEqualTo(1)
        assertThat(d.get(3)).isEqualTo(3)
    }
}
