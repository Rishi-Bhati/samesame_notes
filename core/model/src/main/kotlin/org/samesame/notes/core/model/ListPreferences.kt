package org.samesame.notes.core.model

enum class SortField { Title, DateCreated, DateModified, LastOpened }

enum class SortDirection { Ascending, Descending }

data class SortOrder(
    val field: SortField = SortField.DateModified,
    val direction: SortDirection = SortDirection.Descending,
    val favoritesFirst: Boolean = false,
)

enum class ViewMode { GridSmall, GridMedium, GridLarge, List, SimpleList }

/**
 * Persisted list preferences. Grid (large) is the default view. Folder cards are hidden by default and shown
 * from the drawer's "Show folders" switch; this only affects the note lists (All notes, inside a folder).
 */
data class UserPreferences(
    val viewMode: ViewMode = ViewMode.GridLarge,
    val sortField: SortField = SortField.DateModified,
    val sortDirection: SortDirection = SortDirection.Descending,
    val pinFavoritesToTop: Boolean = false,
    val showFolderCards: Boolean = false,
) {
    val sortOrder: SortOrder get() = SortOrder(sortField, sortDirection, pinFavoritesToTop)
}
