package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchHistoryCodecTest {
    @Test
    fun roundTripsAndIgnoresGarbage() {
        val list = listOf(RecentSearch("meeting notes", 30), RecentSearch("a, b; c", 20))
        assertThat(SearchHistoryCodec.decode(SearchHistoryCodec.encode(list))).isEqualTo(list)
        assertThat(SearchHistoryCodec.decode(null)).isEmpty()
        assertThat(SearchHistoryCodec.decode("no separator")).isEmpty()
    }

    @Test
    fun newestFirstWithoutDuplicates() {
        var list = emptyList<RecentSearch>()
        list = SearchHistoryCodec.add(list, " Plan ", 1)
        list = SearchHistoryCodec.add(list, "todo", 2)
        list = SearchHistoryCodec.add(list, "plan", 3)
        assertThat(list.map { it.keyword }).containsExactly("plan", "todo").inOrder()
        assertThat(SearchHistoryCodec.add(list, "   ", 4)).isEqualTo(list)
    }

    @Test
    fun keepsOnlyTheLatestEntries() {
        var list = emptyList<RecentSearch>()
        repeat(SearchHistoryCodec.MAX_ENTRIES + 5) { list = SearchHistoryCodec.add(list, "q$it", it.toLong()) }
        assertThat(list).hasSize(SearchHistoryCodec.MAX_ENTRIES)
        assertThat(list.first().keyword).isEqualTo("q${SearchHistoryCodec.MAX_ENTRIES + 4}")
    }
}
