package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OrderRanksTest {
    private fun ranks(items: List<OrderRanks.Ranked>, changes: Map<String, Long>): List<Long> =
        items.map { changes[it.id] ?: it.rank }

    @Test fun `between finds the gap at both ends and in the middle`() {
        assertThat(OrderRanks.between(null, 1024)).isEqualTo(512)
        assertThat(OrderRanks.between(1024, 3072)).isEqualTo(2048)
        assertThat(OrderRanks.between(3072, null)).isEqualTo(4096)
        assertThat(OrderRanks.between(null, null)).isEqualTo(OrderRanks.STEP)
    }

    @Test fun `between gives up when the neighbours are adjacent`() {
        assertThat(OrderRanks.between(1024, 1025)).isNull()
        assertThat(OrderRanks.between(null, 1)).isNull()
    }

    @Test fun `moving down only changes the moved row`() {
        val items = listOf(OrderRanks.Ranked("a", 1024), OrderRanks.Ranked("b", 2048), OrderRanks.Ranked("c", 3072), OrderRanks.Ranked("d", 4096))
        val changes = OrderRanks.move(items, from = 0, to = 2)
        assertThat(changes).containsExactly("a", 3584L)
        assertThat(ranks(listOf(items[1], items[2], items[0], items[3]), changes)).containsExactly(2048L, 3072L, 3584L, 4096L).inOrder()
    }

    @Test fun `moving to the top uses half the first rank and moving to the end adds a step`() {
        val items = listOf(OrderRanks.Ranked("a", 1024), OrderRanks.Ranked("b", 2048), OrderRanks.Ranked("c", 3072))
        assertThat(OrderRanks.move(items, from = 2, to = 0)).containsExactly("c", 512L)
        assertThat(OrderRanks.move(items, from = 0, to = 2)).containsExactly("a", 4096L)
    }

    @Test fun `no-op and out-of-range moves change nothing`() {
        val items = listOf(OrderRanks.Ranked("a", 1024), OrderRanks.Ranked("b", 2048))
        assertThat(OrderRanks.move(items, from = 1, to = 1)).isEmpty()
        assertThat(OrderRanks.move(items, from = 5, to = 0)).isEmpty()
        assertThat(OrderRanks.move(emptyList(), from = 0, to = 0)).isEmpty()
    }

    @Test fun `moving into a pair without a gap renumbers the whole section`() {
        val items = listOf(OrderRanks.Ranked("a", 1024), OrderRanks.Ranked("b", 1025), OrderRanks.Ranked("c", 1026))
        val changes = OrderRanks.move(items, from = 0, to = 1)
        assertThat(changes).containsExactly("b", 1024L, "a", 2048L, "c", 3072L)
        assertThat(ranks(listOf(items[1], items[0], items[2]), changes)).containsExactly(1024L, 2048L, 3072L).inOrder()
    }

    @Test fun `renumbered ranks are evenly spaced`() {
        assertThat(OrderRanks.renumbered(3)).containsExactly(1024L, 2048L, 3072L).inOrder()
        assertThat(OrderRanks.renumbered(0)).isEmpty()
    }
}
