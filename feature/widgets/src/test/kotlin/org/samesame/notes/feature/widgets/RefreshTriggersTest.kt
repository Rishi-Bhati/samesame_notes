package org.samesame.notes.feature.widgets

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshTriggersTest {
    @Test fun `a burst collapses into one trigger with the last value`() = runTest {
        val out = flow {
            emit(1) // initial state: no redraw
            emit(2); delay(100); emit(3); delay(100); emit(4)
            delay(5_000)
        }.refreshTriggers(1_500).toList()
        assertThat(out).containsExactly(4)
    }

    @Test fun `unchanged values and the first value trigger nothing`() = runTest {
        val out = flow { emit(1); emit(1); delay(5_000); emit(1); delay(5_000) }.refreshTriggers(1_500).toList()
        assertThat(out).isEmpty()
    }

    @Test fun `separate changes give separate triggers`() = runTest {
        val out = flow { emit(1); emit(2); delay(3_000); emit(3); delay(3_000) }.refreshTriggers(1_500).toList()
        assertThat(out).containsExactly(2, 3).inOrder()
    }
}
