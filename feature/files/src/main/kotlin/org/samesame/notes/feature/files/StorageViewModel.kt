package org.samesame.notes.feature.files

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.samesame.notes.core.data.repository.FilesRepository
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.FileSortField
import org.samesame.notes.core.model.files.FilesSummary
import org.samesame.notes.core.model.files.LibraryFile
import javax.inject.Inject

/** Free space of the volume the library lives on; null when it can't be read. */
fun interface FreeSpace {
    fun freeBytes(): Long?
}

class DeviceFreeSpace @Inject constructor(@ApplicationContext private val context: Context) : FreeSpace {
    override fun freeBytes(): Long? = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
}

data class CategoryUsage(val category: FileCategory, val bytes: Long, val count: Int)

data class StorageUiState(
    val loaded: Boolean = false,
    val fileCount: Int = 0,
    /** Sum of the file sizes. Files with the same content are stored once, so the space really used can be lower. */
    val totalBytes: Long = 0,
    /** Categories that have files, largest first. */
    val categories: List<CategoryUsage> = emptyList(),
    val largest: List<LibraryFile> = emptyList(),
    val trashedCount: Int = 0,
    val trashedBytes: Long = 0,
    val freeBytes: Long? = null,
) {
    /** Below the reserve an import is refused, so the page warns. */
    val lowSpace: Boolean get() = freeBytes != null && freeBytes < Limits.LIBRARY_FREE_SPACE_RESERVE_BYTES

    companion object {
        const val LARGEST_COUNT = 10

        fun from(files: List<LibraryFile>, summary: FilesSummary, freeBytes: Long?): StorageUiState {
            val live = files.filter { !it.isInTrash }
            return StorageUiState(
                loaded = true,
                fileCount = live.size,
                totalBytes = live.sumOf { it.sizeBytes },
                categories = live.groupBy { it.category }
                    .map { (category, list) -> CategoryUsage(category, list.sumOf { it.sizeBytes }, list.size) }
                    .sortedWith(compareByDescending<CategoryUsage> { it.bytes }.thenBy { it.category.ordinal }),
                largest = live.sortedWith(compareByDescending<LibraryFile> { it.sizeBytes }.thenBy { it.displayName }).take(LARGEST_COUNT),
                trashedCount = summary.trashedCount,
                trashedBytes = summary.trashedBytes,
                freeBytes = freeBytes,
            )
        }
    }
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val repo: FilesRepository,
    private val freeSpace: FreeSpace,
) : ViewModel() {
    private val free = MutableStateFlow(freeSpace.freeBytes())

    val uiState: StateFlow<StorageUiState> = combine(
        repo.observeAllFiles(FileSort(FileSortField.Size, ascending = false)),
        repo.observeSummary(),
        free,
    ) { files, summary, freeBytes -> StorageUiState.from(files, summary, freeBytes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    fun emptyTrash() {
        viewModelScope.launch {
            repo.emptyTrash()
            free.value = freeSpace.freeBytes()
        }
    }

    /** The page came back to the front: the free space may have changed. */
    fun refresh() { free.value = freeSpace.freeBytes() }
}
