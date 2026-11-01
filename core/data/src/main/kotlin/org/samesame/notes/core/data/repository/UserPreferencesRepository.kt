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
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.UserPreferences
import org.samesame.notes.core.model.ViewMode
import javax.inject.Inject
import javax.inject.Singleton

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setViewMode(mode: ViewMode)
    suspend fun setSortField(field: SortField)
    suspend fun setSortDirection(direction: SortDirection)
    suspend fun setPinFavoritesToTop(pin: Boolean)
    suspend fun setShowFolderCards(show: Boolean)
}

private val Context.userPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

private object Keys {
    val viewMode = stringPreferencesKey("view_mode")
    val sortField = stringPreferencesKey("sort_field")
    val sortDirection = stringPreferencesKey("sort_direction")
    val pinFavorites = booleanPreferencesKey("pin_favorites_to_top")
    val showFolderCards = booleanPreferencesKey("show_folder_cards")
}

/** Maps stored values to [UserPreferences]; unknown or missing values fall back to the defaults. */
internal fun Preferences.toUserPreferences(): UserPreferences {
    val defaults = UserPreferences()
    return UserPreferences(
        viewMode = enumOrDefault(this[Keys.viewMode], defaults.viewMode),
        sortField = enumOrDefault(this[Keys.sortField], defaults.sortField),
        sortDirection = enumOrDefault(this[Keys.sortDirection], defaults.sortDirection),
        pinFavoritesToTop = this[Keys.pinFavorites] ?: defaults.pinFavoritesToTop,
        showFolderCards = this[Keys.showFolderCards] ?: defaults.showFolderCards,
    )
}

internal inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

@Singleton
internal class DataStoreUserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : UserPreferencesRepository {
    private val store get() = context.userPreferencesStore

    override val preferences: Flow<UserPreferences> = context.userPreferencesStore.data.map { it.toUserPreferences() }

    override suspend fun setViewMode(mode: ViewMode) { store.edit { it[Keys.viewMode] = mode.name } }
    override suspend fun setSortField(field: SortField) { store.edit { it[Keys.sortField] = field.name } }
    override suspend fun setSortDirection(direction: SortDirection) { store.edit { it[Keys.sortDirection] = direction.name } }
    override suspend fun setPinFavoritesToTop(pin: Boolean) { store.edit { it[Keys.pinFavorites] = pin } }
    override suspend fun setShowFolderCards(show: Boolean) { store.edit { it[Keys.showFolderCards] = show } }
}
