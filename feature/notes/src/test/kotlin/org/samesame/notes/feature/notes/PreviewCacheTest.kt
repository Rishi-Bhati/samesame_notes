package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.NoteId

class PreviewCacheTest {
    @Test
    fun evictsTheOldestInsertionBeyondTheLimit() {
        val cache = PreviewCache<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        cache.put("d", 4)

        assertThat(cache["a"]).isNull()
        assertThat(cache["b"]).isEqualTo(2)
        assertThat(cache["c"]).isEqualTo(3)
        assertThat(cache["d"]).isEqualTo(4)
        assertThat(cache.size).isEqualTo(3)
    }

    @Test
    fun replacingAKeyMovesItToTheNewestPosition() {
        val cache = PreviewCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("a", 3)
        cache.put("c", 4)

        assertThat(cache["a"]).isEqualTo(3)
        assertThat(cache["b"]).isNull()
        assertThat(cache.contains("a")).isTrue()
    }

    @Test
    fun lightAndDarkPreviewsDoNotShareAnEntry() {
        val cache = PreviewCache<PreviewKey, String>(4)
        val light = PreviewKey(NoteId("note"), 1L, darkTheme = false, locked = false)
        val dark = PreviewKey(NoteId("note"), 1L, darkTheme = true, locked = false)

        assertThat(light).isNotEqualTo(dark)
        cache.put(light, "light page")
        cache.put(dark, "dark page")

        assertThat(cache[light]).isEqualTo("light page")
        assertThat(cache[dark]).isEqualTo("dark page")
    }
}
