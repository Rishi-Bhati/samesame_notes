package org.samesame.notes.core.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PenType

class EditorPreferencesTest {
    @Test
    fun emptyPreferencesGiveDefaults() {
        assertThat(mutablePreferencesOf().toEditorPreferences()).isEqualTo(EditorPreferences())
    }

    @Test
    fun recentColorsMoveToFrontDedupAndCap() {
        var list = emptyList<Long>()
        for (i in 1L..12L) list = list.withRecent(i)
        assertThat(list).hasSize(EditorPreferences.MAX_RECENT_COLORS)
        assertThat(list.first()).isEqualTo(12L)
        assertThat(list.withRecent(5L).take(2)).containsExactly(5L, 12L).inOrder()
        assertThat(list.withRecent(5L).count { it == 5L }).isEqualTo(1)
    }

    @Test
    fun unknownPenTypeFallsBack() {
        assertThat(EditorPreferences().penType).isEqualTo(PenType.Fountain)
    }

    @Test
    fun recentCapacityMatchesReference() {
        assertThat(EditorPreferences.MAX_RECENT_COLORS).isEqualTo(8)
    }

    @Test
    fun paletteSetsDefaultNormaliseAndParse() {
        assertThat(EditorPreferences().paletteSets).containsExactly(1, 4, 21, 5).inOrder()
        assertThat(parsePaletteSets(null)).containsExactly(1, 4, 21, 5).inOrder()
        assertThat(parsePaletteSets("3,3,9,0,22,7,2,6")).containsExactly(3, 9, 7, 2).inOrder()
        assertThat(parsePaletteSets("")).containsExactly(1, 4, 21, 5).inOrder()
        assertThat(parsePaletteSets("x,y")).containsExactly(1, 4, 21, 5).inOrder()
    }

    private fun pen(level: Int, color: Long = 0xFF335FFF, hl: Boolean = false) =
        FavoritePen(highlighter = hl, penType = PenType.Pencil, tip = if (hl) 2 else 0, level = level, color = color, opacity = if (hl) 45 else 100)

    @Test
    fun favouritePensAddRejectsDuplicateAndCapsAtFortyFive() {
        var list = emptyList<FavoritePen>()
        val (l1, r1) = FavoritePens.add(list, pen(10)); list = l1
        assertThat(r1).isEqualTo(FavoriteAddResult.Added)
        assertThat(FavoritePens.add(list, pen(10)).second).isEqualTo(FavoriteAddResult.AlreadySaved)
        assertThat(FavoritePens.add(list, pen(10, color = 0xFFFF0000)).second).isEqualTo(FavoriteAddResult.Added)
        for (i in 11..60) list = FavoritePens.add(list, pen(i)).first
        assertThat(list).hasSize(FavoritePens.MAX)
        assertThat(FavoritePens.MAX).isEqualTo(45)
        assertThat(FavoritePens.add(list, pen(100, 1)).second).isEqualTo(FavoriteAddResult.ListFull)
    }

    @Test
    fun favouritePensEncodeDecodeRoundTrip() {
        val list = listOf(pen(10), pen(55, 0xFFFFEB3B, hl = true), FavoritePen(false, PenType.Fountain, 0, 30, 0xFF335FFF))
        assertThat(FavoritePens.decode(FavoritePens.encode(list))).isEqualTo(list)
        assertThat(FavoritePens.decode(null)).isEmpty()
        assertThat(FavoritePens.decode("garbage;P:Nope:0:5:1:1")).hasSize(1)
    }

    @Test
    fun fingerDrawingIsOnUntilAStylusIsSeenThenOffOnce() {
        val fresh = EditorPreferences()
        assertThat(fresh.fingerDrawing).isTrue()
        assertThat(fresh.stylusSeen).isFalse()
        val seen = fresh.withStylusSeen()
        assertThat(seen.fingerDrawing).isFalse()
        assertThat(seen.stylusSeen).isTrue()
        // The user turns finger drawing back on: a later stylus event must not flip it again.
        val reEnabled = seen.copy(fingerDrawing = true)
        assertThat(reEnabled.withStylusSeen()).isEqualTo(reEnabled)
    }

    @Test
    fun stylusSeenAndEasyPadGuideAreReadFromStorage() {
        val p = mutablePreferencesOf(
            androidx.datastore.preferences.core.booleanPreferencesKey("stylus_seen") to true,
            androidx.datastore.preferences.core.booleanPreferencesKey("finger_drawing") to false,
            androidx.datastore.preferences.core.booleanPreferencesKey("easy_pad_guide_seen") to true,
        ).toEditorPreferences()
        assertThat(p.stylusSeen).isTrue(); assertThat(p.fingerDrawing).isFalse(); assertThat(p.easyPadGuideSeen).isTrue()
    }
}
