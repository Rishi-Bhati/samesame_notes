package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewFailureStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a render that never ended blocks that revision in a new process`() {
        val dir = tmp.newFolder("previews")
        PreviewFailureStore(dir).begin("note/../x", 42) // process dies here
        val next = PreviewFailureStore(dir)
        assertThat(next.isBlocked("note/../x", 42)).isTrue()
        assertThat(next.isBlocked("note/../x", 43)).isFalse() // edited since: one new attempt
        assertThat(dir.listFiles()!!.single().name).matches("[0-9a-f]{64}")
    }

    @Test fun `a render that returned leaves nothing behind`() {
        val store = PreviewFailureStore(tmp.newFolder("previews"))
        store.begin("n", 1)
        store.end("n")
        assertThat(store.isBlocked("n", 1)).isFalse()
    }
}
