package org.samesame.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.ThemeModeCache
import org.samesame.notes.core.designsystem.theme.FontSectionScope
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.NoteKind
import org.samesame.notes.feature.composer.ComposerRoute
import javax.inject.Inject

@AndroidEntryPoint
class ComposerActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository
    @Inject lateinit var noteRepository: NoteRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        if (noteId.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val note = withContext(Dispatchers.IO) {
                noteRepository.getNote(NoteId(noteId))
            }
            if (note?.kind == NoteKind.Markdown) {
                startActivity(NoteTextDocActivity.noteIntent(this@ComposerActivity, noteId))
                finish()
                return@launch
            }
            // A new note started from a home-screen widget button: keypad, pen, camera, image or voice (null = just open).
            val startAction = intent.getStringExtra(EXTRA_START_ACTION)
            val launchTheme = ThemeModeCache.read(this@ComposerActivity)
            enableEdgeToEdge()
            applyLaunchAppearance(resolveDark(launchTheme, systemDark()))
            setContent {
                val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
                val dark = isDarkTheme(settings.themeMode)
                SystemBarAppearance(dark)
                SameSameTheme(darkTheme = dark, font = settings.font) {
                  FontSectionScope(FontSection.Chrome) {
                    // The editor lives in this activity, outside the navigation host, so it gets the Files entry points here.
                    ProvideLibraryFiles { ComposerRoute(noteId = noteId, onBack = { finish() }, startAction = startAction) }
                  }
                }
            }
        }
    }

    companion object {
        const val EXTRA_NOTE_ID = "org.samesame.notes.extra.NOTE_ID"
        const val EXTRA_START_ACTION = "org.samesame.notes.extra.START_ACTION"

        fun intent(context: Context, noteId: String): Intent =
            Intent(context, ComposerActivity::class.java).putExtra(EXTRA_NOTE_ID, noteId)
    }
}
