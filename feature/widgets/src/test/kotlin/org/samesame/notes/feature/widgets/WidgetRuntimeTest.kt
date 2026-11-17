package org.samesame.notes.feature.widgets

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WidgetRuntimeTest {
    @Test fun `a load that never finishes ends as failed instead of spinning`() = runBlocking {
        assertThat(loadWidgetData("hang", timeoutMs = 50) { awaitCancellation() }).isEqualTo(Loaded.Failed)
    }

    @Test fun `a load that throws ends as failed`() = runBlocking {
        assertThat(loadWidgetData<Int>("boom", timeoutMs = 1_000) { error("db gone") }).isEqualTo(Loaded.Failed)
    }

    @Test fun `a normal load is ready`() = runBlocking {
        assertThat(loadWidgetData("ok", timeoutMs = 1_000) { 42 }).isEqualTo(Loaded.Ready(42))
    }

    @Test fun `redraw requests bump the version`() {
        val before = WidgetSignals.version.value
        WidgetSignals.bump()
        assertThat(WidgetSignals.version.value).isEqualTo(before + 1)
    }
}
