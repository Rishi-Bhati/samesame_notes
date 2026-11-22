package org.samesame.notes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.ThemeModeCache
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.feature.files.FilesIntents
import org.samesame.notes.feature.files.ReceiveEvent
import org.samesame.notes.feature.files.ReceiveKind
import org.samesame.notes.feature.files.ReceiveRouting
import org.samesame.notes.feature.files.ReceivedItem
import org.samesame.notes.feature.files.ShareReceiveSheet
import org.samesame.notes.feature.files.SharedText
import org.samesame.notes.feature.files.ShareReceiveViewModel
import javax.inject.Inject

/**
 * The "Save to SameSame" receiver: SEND, SEND_MULTIPLE and VIEW for every file type. It copies the files into Files (or,
 * for note files and PDFs, makes a note) while its sheet is visible, because the sender's URI grants end with this activity.
 */
@AndroidEntryPoint
class ShareReceiveActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    private val viewModel: ShareReceiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-delivered from Recents: it was handled when it first arrived.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) { finish(); return }
        if (savedInstanceState == null) {
            val items = receivedItems(intent)
            val first = items.firstOrNull()
            if (intent.action == Intent.ACTION_VIEW && first != null && first.kind == ReceiveKind.Text) {
                val hasWrite = (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                startActivity(
                    TextDocActivity.intent(this, first.uri, first.name, writable = hasWrite)
                )
                finish()
                return
            }
            val shared = if (items.isEmpty()) sharedText(intent) else null
            if (shared != null) viewModel.startText(shared)
            else if (items.isEmpty()) {
                Toast.makeText(this, org.samesame.notes.feature.files.R.string.feature_files_receive_nothing, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            else viewModel.start(items, referrer?.takeIf { it.scheme == "android-app" }?.host)
        }
        val launchTheme = ThemeModeCache.read(this)
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
            val dark = isDarkTheme(settings.themeMode)
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        is ReceiveEvent.OpenNote -> { startActivity(ComposerActivity.intent(this@ShareReceiveActivity, event.noteId)); finish() }
                        ReceiveEvent.TextTooLarge -> Toast.makeText(
                            this@ShareReceiveActivity, org.samesame.notes.feature.files.R.string.feature_files_receive_text_too_large, Toast.LENGTH_LONG,
                        ).show()
                        ReceiveEvent.NoteFailed -> Toast.makeText(
                            this@ShareReceiveActivity, org.samesame.notes.feature.files.R.string.feature_files_receive_note_failed, Toast.LENGTH_LONG,
                        ).show()
                        ReceiveEvent.SavedElsewhere -> {
                            Toast.makeText(
                                this@ShareReceiveActivity, org.samesame.notes.feature.files.R.string.feature_files_receive_saved_elsewhere, Toast.LENGTH_SHORT,
                            ).show()
                            finish()
                        }
                        is ReceiveEvent.SaveElsewhereFailed -> Toast.makeText(
                            this@ShareReceiveActivity,
                            if (event.tooLarge) org.samesame.notes.feature.files.R.string.feature_files_receive_save_too_large
                            else org.samesame.notes.feature.files.R.string.feature_files_receive_save_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            SameSameTheme(darkTheme = dark, font = settings.font) {
                if (state.items.isNotEmpty()) {
                    ShareReceiveSheet(
                        viewModel = viewModel,
                        state = state,
                        onFinish = { finish() },
                        onOpen = { file ->
                            val uri = viewModel.contentUri(file)
                            if (!FilesIntents.launchView(this@ShareReceiveActivity, file, uri)) {
                                Toast.makeText(this@ShareReceiveActivity, org.samesame.notes.feature.files.R.string.feature_files_no_app, Toast.LENGTH_LONG).show()
                            } else finish()
                        },
                    )
                }
            }
        }
    }

    /** EXTRA_TEXT of a SEND / SEND_MULTIPLE without a file (a selection, a web page link). EXTRA_HTML_TEXT is ignored. */
    private fun sharedText(intent: Intent): SharedText? {
        val texts: List<CharSequence?> = when (intent.action) {
            Intent.ACTION_SEND -> listOf(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_SEND_MULTIPLE -> intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> return null
        }
        return SharedText.of(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT), texts)
    }

    private fun receivedItems(intent: Intent): List<ReceivedItem> {
        val resolver = IncomingFileResolver(contentResolver)
        return streamUris(intent)
            .filter { IncomingFileResolver.isForeignContent(it, packageName) }
            .distinct()
            .map { uri ->
                val file = resolver.resolve(uri, intent.type)
                val name = file.displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
                ReceivedItem(uri, name.ifBlank { "file" }, file.mimeType, ReceiveRouting.kindOf(name, file.mimeType))
            }
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipUri())
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
        else -> emptyList()
    }

    private fun Intent.clipUri(): Uri? = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
}
