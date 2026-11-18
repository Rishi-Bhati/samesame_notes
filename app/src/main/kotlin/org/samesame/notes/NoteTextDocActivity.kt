package org.samesame.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.samesame.notes.feature.textdoc.TextDocRoute
import javax.inject.Inject

/**
 * The internal text document editor: started only from within the app for markdown notes
 * and library files. Not exported.
 */
@AndroidEntryPoint
class NoteTextDocActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isValidIntent(intent)) {
            finish()
            return
        }
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val libraryFileId = intent.getStringExtra(EXTRA_LIBRARY_FILE_ID)
        val uri = intent.data
        val writable = intent.getBooleanExtra(EXTRA_WRITABLE, true)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)

        val launchTheme = ThemeModeCache.read(this)
        enableEdgeToEdge()
        applyLaunchAppearance(resolveDark(launchTheme, systemDark()))
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
            val dark = isDarkTheme(settings.themeMode)
            SystemBarAppearance(dark)
            SameSameTheme(darkTheme = dark, font = settings.font) {
                TextDocRoute(
                    uri = uri,
                    libraryFileId = libraryFileId,
                    displayName = displayName,
                    writable = writable,
                    noteId = noteId,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "org.samesame.notes.extra.TEXTDOC_NAME"
        const val EXTRA_LIBRARY_FILE_ID = "org.samesame.notes.extra.TEXTDOC_FILE_ID"
        const val EXTRA_NOTE_ID = "org.samesame.notes.extra.TEXTDOC_NOTE_ID"
        const val EXTRA_WRITABLE = "org.samesame.notes.extra.TEXTDOC_WRITABLE"

        /** Returns true only if the intent carries an internal note id, library file id, or uri. */
        fun isValidIntent(intent: Intent): Boolean {
            val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
            val libraryFileId = intent.getStringExtra(EXTRA_LIBRARY_FILE_ID)
            return !noteId.isNullOrBlank() || !libraryFileId.isNullOrBlank() || intent.data != null
        }

        /** Opens a Markdown note by id; the editor loads and saves it through the note store. */
        fun noteIntent(context: Context, noteId: String): Intent =
            Intent(context, NoteTextDocActivity::class.java)
                .putExtra(EXTRA_NOTE_ID, noteId)
                .putExtra(EXTRA_WRITABLE, true)

        /** Opens a Files entry by id; the editor loads and saves it through the repository. */
        fun intent(context: Context, libraryFileId: String): Intent =
            Intent(context, NoteTextDocActivity::class.java)
                .putExtra(EXTRA_LIBRARY_FILE_ID, libraryFileId)
                .putExtra(EXTRA_WRITABLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
