package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.notes.SelectionMenuItem.AddFavorite
import org.samesame.notes.feature.notes.SelectionMenuItem.Duplicate
import org.samesame.notes.feature.notes.SelectionMenuItem.Lock
import org.samesame.notes.feature.notes.SelectionMenuItem.RemoveFavorite
import org.samesame.notes.feature.notes.SelectionMenuItem.SaveAsFile
import org.samesame.notes.feature.notes.SelectionMenuItem.Unlock

class SelectionMenuTest {
    @Test fun `plain notes get every note action`() {
        assertThat(SelectionCounts(notes = 2).moreMenuItems()).containsExactly(Lock, SaveAsFile, Duplicate, AddFavorite).inOrder()
    }

    @Test fun `one locked note hides Duplicate`() {
        val counts = SelectionCounts(notes = 1, locked = 1)
        assertThat(counts.canShow(Duplicate)).isFalse()
        assertThat(counts.moreMenuItems()).containsExactly(Unlock, SaveAsFile, AddFavorite).inOrder()
    }

    @Test fun `a mix of locked and unlocked notes offers Lock but no Duplicate`() {
        val counts = SelectionCounts(notes = 3, locked = 1)
        assertThat(counts.moreMenuItems()).containsExactly(Lock, SaveAsFile, AddFavorite).inOrder()
    }

    @Test fun `favourites toggle on all-or-not`() {
        assertThat(SelectionCounts(notes = 2, favorites = 2).canShow(RemoveFavorite)).isTrue()
        assertThat(SelectionCounts(notes = 2, favorites = 2).canShow(AddFavorite)).isFalse()
        assertThat(SelectionCounts(notes = 2, favorites = 1).canShow(AddFavorite)).isTrue()
        assertThat(SelectionCounts(notes = 2, favorites = 1).canShow(RemoveFavorite)).isFalse()
    }

    @Test fun `folders in the selection or no notes leave the menu empty`() {
        assertThat(SelectionCounts(notes = 2, folders = 1).moreMenuItems()).isEmpty()
        assertThat(SelectionCounts(notes = 0).moreMenuItems()).isEmpty()
    }
}
