package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FtsQueryTest {
    @Test
    fun `words become quoted prefix terms`() {
        assertThat(toFtsQuery("meeting  agen")).isEqualTo("\"meeting\"* \"agen\"*")
    }

    @Test
    fun `quotes and blank input cannot break the query`() {
        assertThat(toFtsQuery("say \"hi\"")).isEqualTo("\"say\"* \"hi\"*")
        assertThat(toFtsQuery("   ")).isEmpty()
    }

    @Test
    fun `tags normalize case and hash prefix`() {
        assertThat(normalizeTag("  #Work ")).isEqualTo("work")
    }
}
