package org.samesame.notes.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.data.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** A search the user ran, newest first in [SearchHistoryRepository.recents]. */
data class RecentSearch(val keyword: String, val time: Long)

/** Recent searches shown on the empty search screen, and the "Search settings" switch that turns them on or off. */
interface SearchHistoryRepository {
    /** Whether searches are remembered (default on). */
    val enabled: Flow<Boolean>
    val recents: Flow<List<RecentSearch>>

    /** Remembers [keyword] (moves it to the top when already there). Does nothing when history is off or the keyword is blank. */
    suspend fun add(keyword: String)
    suspend fun remove(keyword: String)
    suspend fun clear()

    /** Turning history off also clears it. */
    suspend fun setEnabled(enabled: Boolean)
}

/** Pure list handling behind [SearchHistoryRepository]; one entry is `keyword US time`, entries are separated by RS. */
internal object SearchHistoryCodec {
    const val MAX_ENTRIES = 20
    private const val US = '\u001F'
    private const val RS = '\u001E'

    fun decode(raw: String?): List<RecentSearch> = raw.orEmpty().split(RS).mapNotNull { entry ->
        val i = entry.lastIndexOf(US)
        if (i <= 0) return@mapNotNull null
        val time = entry.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
        RecentSearch(entry.substring(0, i), time)
    }

    fun encode(list: List<RecentSearch>): String = list.joinToString(RS.toString()) { "${it.keyword}$US${it.time}" }

    /** [keyword] (trimmed, control characters removed) goes first; an equal earlier entry (ignoring case) is dropped. */
    fun add(list: List<RecentSearch>, keyword: String, now: Long): List<RecentSearch> {
        val clean = keyword.filter { it != US && it != RS }.trim()
        if (clean.isEmpty()) return list
        return (listOf(RecentSearch(clean, now)) + list.filterNot { it.keyword.equals(clean, ignoreCase = true) }).take(MAX_ENTRIES)
    }
}

private val Context.searchHistoryStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

private object SearchKeys {
    val enabled = booleanPreferencesKey("enabled")
    val entries = stringPreferencesKey("entries")
}

@Singleton
internal class DataStoreSearchHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : SearchHistoryRepository {
    private val store get() = context.searchHistoryStore

    override val enabled: Flow<Boolean> = context.searchHistoryStore.data.map { it[SearchKeys.enabled] ?: true }
    override val recents: Flow<List<RecentSearch>> = context.searchHistoryStore.data.map { SearchHistoryCodec.decode(it[SearchKeys.entries]) }

    override suspend fun add(keyword: String) {
        store.edit { p ->
            if (p[SearchKeys.enabled] == false) return@edit
            p[SearchKeys.entries] = SearchHistoryCodec.encode(SearchHistoryCodec.add(SearchHistoryCodec.decode(p[SearchKeys.entries]), keyword, clock.now()))
        }
    }

    override suspend fun remove(keyword: String) {
        store.edit { p ->
            p[SearchKeys.entries] = SearchHistoryCodec.encode(SearchHistoryCodec.decode(p[SearchKeys.entries]).filterNot { it.keyword == keyword })
        }
    }

    override suspend fun clear() { store.edit { it.remove(SearchKeys.entries) } }

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { p ->
            p[SearchKeys.enabled] = enabled
            if (!enabled) p.remove(SearchKeys.entries)
        }
    }
}
