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
 * The text document editor. It is exported for ACTION_EDIT and ACTION_VIEW of text files, and started by the app for
 * a Files entry. A URI is editable only when the intent carries a write grant (or explicitly asks for it); otherwise
 * the editor opens read-only and offers Save as.
 */
@AndroidEntryPoint
class TextDocActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val libraryFileId = intent.getStringExtra(EXTRA_LIBRARY_FILE_ID)
        if (uri == null && libraryFileId.isNullOrBlank()) {
            finish()
            return
        }
        val writable = intent.getBooleanExtra(EXTRA_WRITABLE, false) ||
            intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
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
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_DISPLAY_NAME = "org.samesame.notes.extra.TEXTDOC_NAME"
        private const val EXTRA_LIBRARY_FILE_ID = "org.samesame.notes.extra.TEXTDOC_FILE_ID"
        private const val EXTRA_WRITABLE = "org.samesame.notes.extra.TEXTDOC_WRITABLE"

        /** Opens [uri] from inside the app. The flags are forwarded so the editor may write the document. */
        fun intent(context: Context, uri: Uri, displayName: String?, writable: Boolean): Intent =
            Intent(context, TextDocActivity::class.java)
                .setAction(if (writable) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
                .setData(uri)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
                .putExtra(EXTRA_WRITABLE, writable)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        /** Opens a Files entry by id; the editor loads and saves it through the repository. */
        fun intent(context: Context, libraryFileId: String): Intent =
            Intent(context, TextDocActivity::class.java)
                .putExtra(EXTRA_LIBRARY_FILE_ID, libraryFileId)
                .putExtra(EXTRA_WRITABLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
