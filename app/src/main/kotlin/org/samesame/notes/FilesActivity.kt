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
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.feature.files.FilesRoute
import org.samesame.notes.feature.files.FilesTarget
import javax.inject.Inject

/**
 * The Files screen opened from a note ("Open in Files", "Search Files for ..."): it sits on top of the editor, which
 * runs in its own activity, so Back returns to the note. Not exported.
 */
@AndroidEntryPoint
class FilesActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = targetOf(intent)
        val launchTheme = ThemeModeCache.read(this)
        enableEdgeToEdge()
        applyLaunchAppearance(resolveDark(launchTheme, systemDark()))
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
            val dark = isDarkTheme(settings.themeMode)
            SystemBarAppearance(dark)
            SameSameTheme(darkTheme = dark) {
                FilesRoute(
                    onBack = { finish() },
                    onOpenNote = { id -> startActivity(ComposerActivity.intent(this, id.value)) },
                    initialTarget = target,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_FILE_ID = "org.samesame.notes.extra.FILES_FILE_ID"
        private const val EXTRA_QUERY = "org.samesame.notes.extra.FILES_QUERY"
        private const val MAX_QUERY = 255

        fun showFile(context: Context, id: FileId): Intent = Intent(context, FilesActivity::class.java).putExtra(EXTRA_FILE_ID, id.value)
        fun search(context: Context, query: String): Intent = Intent(context, FilesActivity::class.java).putExtra(EXTRA_QUERY, query.take(MAX_QUERY))

        internal fun targetOf(intent: Intent): FilesTarget? {
            intent.getStringExtra(EXTRA_FILE_ID)?.let(::FileId)?.takeIf { it.isWellFormed }?.let { return FilesTarget.File(it) }
            return intent.getStringExtra(EXTRA_QUERY)?.take(MAX_QUERY)?.let { FilesTarget.Search(it) }
        }
    }
}
