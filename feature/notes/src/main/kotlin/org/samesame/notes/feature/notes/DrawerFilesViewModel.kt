package org.samesame.notes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.samesame.notes.core.data.repository.FilesRepository
import javax.inject.Inject

/** The Files row's badge in the drawer: how many files the library holds. */
@HiltViewModel
class DrawerFilesViewModel @Inject constructor(files: FilesRepository) : ViewModel() {
    val fileCount: StateFlow<Int> = files.observeSummary().map { it.fileCount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
