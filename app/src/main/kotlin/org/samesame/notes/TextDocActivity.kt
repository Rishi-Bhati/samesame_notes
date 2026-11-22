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
 * The text document editor for external files. It is exported for ACTION_EDIT and ACTION_VIEW of text files
 * from other apps. It strictly accepts foreign content:// URIs; internal note and library IDs, file:// URIs,
 * and app-private URIs are rejected.
 */
@AndroidEntryPoint
class TextDocActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (!isValidIntent(intent, packageName)) {
            finish()
            return
        }
        val writable = isWritable(intent)
        // Opened straight from another app (no Save to SameSame hop): the name and type come from the provider.
        val incoming = uri?.let { IncomingFileResolver(contentResolver).resolve(it, intent.type) }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: incoming?.displayName
        val mimeType = incoming?.mimeType

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
                    libraryFileId = null,
                    displayName = displayName,
                    writable = writable,
                    noteId = null,
                    mimeType = mimeType,
                    onBack = { finish() },
                    onOpenNote = { id, markdown ->
                        startActivity(
                            if (markdown) NoteTextDocActivity.noteIntent(this, id) else ComposerActivity.intent(this, id),
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "org.samesame.notes.extra.TEXTDOC_NAME"
        const val EXTRA_WRITABLE = "org.samesame.notes.extra.TEXTDOC_WRITABLE"
        const val EXTRA_LIBRARY_FILE_ID = "org.samesame.notes.extra.TEXTDOC_FILE_ID"
        const val EXTRA_NOTE_ID = "org.samesame.notes.extra.TEXTDOC_NOTE_ID"

        /** Returns true only if the intent points to a valid external content URI and carries no internal IDs. */
        fun isValidIntent(intent: Intent, ownPackage: String): Boolean {
            if (intent.hasExtra(EXTRA_NOTE_ID) || intent.hasExtra(EXTRA_LIBRARY_FILE_ID)) return false
            val uri = intent.data ?: return false
            return IncomingFileResolver.isForeignContent(uri, ownPackage)
        }

        /**
         * Editable only when the sender granted write access. [EXTRA_WRITABLE] is not trusted: any app can set it, and
         * the editor would then write with this app's own access (a persisted backup folder grant, for one).
         */
        fun isWritable(intent: Intent): Boolean = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0

        /** Opens [uri] from inside/outside the app. The flags are forwarded so the editor may write the document. */
        fun intent(context: Context, uri: Uri, displayName: String?, writable: Boolean): Intent {
            val flags = if (writable) {
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            } else {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            return Intent(context, TextDocActivity::class.java)
                .setAction(if (writable) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
                .setData(uri)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
                .putExtra(EXTRA_WRITABLE, writable)
                .addFlags(flags)
        }
    }
}
