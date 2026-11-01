package org.samesame.notes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeModeTest {
    @Test
    fun lightAndDarkIgnoreTheSystem() {
        assertThat(ThemeMode.Light.isDark(systemDark = true)).isFalse()
        assertThat(ThemeMode.Light.isDark(systemDark = false)).isFalse()
        assertThat(ThemeMode.Dark.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.Dark.isDark(systemDark = false)).isTrue()
    }

    @Test
    fun systemFollowsTheSystem() {
        assertThat(ThemeMode.System.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.System.isDark(systemDark = false)).isFalse()
    }

    @Test
    fun systemIsTheDefault() {
        assertThat(AppSettings().themeMode).isEqualTo(ThemeMode.System)
    }
}
