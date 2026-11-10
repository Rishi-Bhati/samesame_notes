package org.samesame.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.ThemeModeCache
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.feature.composer.ComposerRoute
import javax.inject.Inject

@AndroidEntryPoint
class ComposerActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        if (noteId.isNullOrBlank()) {
            finish()
            return
        }
        val launchTheme = ThemeModeCache.read(this)
        enableEdgeToEdge()
        applyLaunchAppearance(resolveDark(launchTheme, systemDark()))
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
            val dark = isDarkTheme(settings.themeMode)
            SystemBarAppearance(dark)
            SameSameTheme(darkTheme = dark) {
                // The editor lives in this activity, outside the navigation host, so it gets the Files entry points here.
                ProvideLibraryFiles { ComposerRoute(noteId = noteId, onBack = { finish() }) }
            }
        }
    }

    companion object {
        const val EXTRA_NOTE_ID = "org.samesame.notes.extra.NOTE_ID"

        fun intent(context: Context, noteId: String): Intent =
            Intent(context, ComposerActivity::class.java).putExtra(EXTRA_NOTE_ID, noteId)
    }
}
