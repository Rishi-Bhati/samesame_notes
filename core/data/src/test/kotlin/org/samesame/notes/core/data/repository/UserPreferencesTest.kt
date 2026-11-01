package org.samesame.notes.core.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.UserPreferences

class UserPreferencesTest {
    @Test
    fun folderCardsAreHiddenByDefault() {
        assertThat(UserPreferences().showFolderCards).isFalse()
        assertThat(mutablePreferencesOf().toUserPreferences().showFolderCards).isFalse()
    }

    @Test
    fun storedFolderCardChoiceIsRead() {
        val prefs = mutablePreferencesOf(booleanPreferencesKey("show_folder_cards") to true)
        assertThat(prefs.toUserPreferences().showFolderCards).isTrue()
    }
}
