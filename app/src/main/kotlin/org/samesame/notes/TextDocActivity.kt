package org.samesame.notes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.ThemeModeCache
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.feature.textdoc.TextDocRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        if (uri == null || !isValidIntent(intent, packageName)) {
            finish()
            return
        }
        val writable = isWritable(intent)
        // Provider queries and the first bytes are read off the main thread. A binary file goes to the
        // "Save to SameSame" sheet instead of the editor.
        lifecycleScope.launch {
            val (incoming, binaryType) = withContext(Dispatchers.IO) {
                // Opened straight from another app (no Save to SameSame hop): the name and type come from the provider.
                val file = IncomingFileResolver(contentResolver).resolve(uri, intent.type, intent.getStringExtra(Intent.EXTRA_TITLE))
                file to sniffBinary(uri)
            }
            val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: incoming.displayName
            when {
                binaryType == null -> showEditor(uri, writable, displayName, incoming.mimeType)
                handOffBinary(uri, binaryType, displayName) -> finish()
                else -> {
                    Toast.makeText(this@TextDocActivity, R.string.text_doc_not_text, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    /** A MIME type for the file when its first bytes are not text, else null (also null when it cannot be read: the editor reports that). */
    private fun sniffBinary(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BinaryContentSniffer.SNIFF_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            BinaryContentSniffer.sniff(buffer, read)
        }
    }.getOrNull()

    /**
     * Hands the same URI, with only the read grant this activity holds, to the "Save to SameSame" sheet. SEND (not
     * VIEW) so the sheet never routes it back here. Returns false when the sheet cannot be started.
     */
    private fun handOffBinary(uri: Uri, mimeType: String, displayName: String?): Boolean = runCatching {
        val send = Intent(this, ShareReceiveActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!displayName.isNullOrBlank()) send.putExtra(Intent.EXTRA_TITLE, displayName)
        send.clipData = ClipData.newRawUri(null, uri)
        startActivity(send)
    }.isSuccess

    private fun showEditor(uri: Uri, writable: Boolean, displayName: String?, mimeType: String?) {
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
