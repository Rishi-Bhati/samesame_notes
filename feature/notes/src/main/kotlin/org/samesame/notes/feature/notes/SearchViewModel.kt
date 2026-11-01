package org.samesame.notes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.RecentSearch
import org.samesame.notes.core.data.repository.SearchHistoryRepository
import org.samesame.notes.core.model.Note
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    notes: NoteRepository,
    private val history: SearchHistoryRepository,
) : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<List<Note>> = query
        .flatMapLatest { if (it.isBlank()) flowOf(emptyList()) else notes.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recent searches, newest first; empty while history is switched off. */
    val recents: StateFlow<List<RecentSearch>> = combine(history.enabled, history.recents) { on, list -> if (on) list else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyEnabled: StateFlow<Boolean> = history.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Called when the user submits the keyboard's search action or opens a result. */
    fun remember(keyword: String) { viewModelScope.launch { history.add(keyword) } }
    fun forget(keyword: String) { viewModelScope.launch { history.remove(keyword) } }
    fun clearHistory() { viewModelScope.launch { history.clear() } }
    fun setHistoryEnabled(enabled: Boolean) { viewModelScope.launch { history.setEnabled(enabled) } }
}
