package org.samesame.notes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.ThemeModeCache
import org.samesame.notes.core.data.transfer.ImportFailure
import org.samesame.notes.core.data.transfer.NoteFileDetector
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.navigation.SameSameNavDisplay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    private val importViewModel: FileImportViewModel by viewModels()
    private val fileResolver by lazy { IncomingFileResolver(contentResolver) }
    private var pendingImport by mutableStateOf<List<IncomingFile>>(emptyList())
    private var pendingLegacy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchTheme = ThemeModeCache.read(this)
        enableEdgeToEdge()
        applyLaunchAppearance(resolveDark(launchTheme, systemDark()))
        if (savedInstanceState == null) {
            applyLaunch(intent)
        } else {
            // Not imported yet when the activity was recreated: resolve again (a started import lives in the ViewModel).
            pendingImport = savedInstanceState.getStringArrayList(KEY_PENDING).orEmpty()
                .map { fileResolver.resolve(Uri.parse(it), null) }
                .filter { it.verdict is NoteFileDetector.Verdict.Supported }
            pendingLegacy = savedInstanceState.getBoolean(KEY_LEGACY, false)
        }

        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
            val dark = isDarkTheme(settings.themeMode)
            SystemBarAppearance(dark)
            val importState by importViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(pendingImport) {
                if (pendingImport.isNotEmpty()) {
                    val files = pendingImport
                    pendingImport = emptyList()
                    importViewModel.import(files)
                }
            }
            LaunchedEffect(importState.error) {
                importState.error?.let {
                    Toast.makeText(this@MainActivity, it.messageRes(), Toast.LENGTH_LONG).show()
                    importViewModel.consume()
                }
            }
            LaunchedEffect(importState.summary) {
                importState.summary?.let { (imported, failed) ->
                    val text = if (failed == 0) {
                        resources.getQuantityString(R.plurals.file_import_done, imported, imported)
                    } else {
                        resources.getQuantityString(R.plurals.file_import_done_failed, imported, imported, failed)
                    }
                    Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                    if (importState.noteId == null) importViewModel.consume()
                }
            }
            LaunchedEffect(pendingLegacy) {
                if (pendingLegacy) {
                    pendingLegacy = false
                    Toast.makeText(this@MainActivity, R.string.sdocx_import_legacy, Toast.LENGTH_LONG).show()
                }
            }

            SameSameTheme(darkTheme = dark) {
                SameSameNavDisplay(
                    openNoteId = importState.noteId,
                    onOpenNoteConsumed = importViewModel::consume,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunch(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (pendingImport.isNotEmpty()) outState.putStringArrayList(KEY_PENDING, ArrayList(pendingImport.map { it.uri.toString() }))
        if (pendingLegacy) outState.putBoolean(KEY_LEGACY, true)
        super.onSaveInstanceState(outState)
    }

    /**
     * The file URIs of a VIEW, SEND or SEND_MULTIPLE intent: `content://` from other apps only. A `file://` URI (or
     * one of this app's own providers) would be read with this app's permissions, i.e. could point at its private files.
     */
    private fun Intent.fileUris(): List<Uri> = when (action) {
        Intent.ACTION_VIEW -> listOfNotNull(data)
        @Suppress("DEPRECATION")
        Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        @Suppress("DEPRECATION")
        Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
        else -> emptyList()
    }.filter { IncomingFileResolver.isForeignContent(it, packageName) }

    /**
     * Queues the files of [intent] for import. An intent re-delivered from Recents (the task was relaunched from
     * history) was already handled when it first arrived, so it must not import another copy.
     */
    private fun applyLaunch(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val files = intent.fileUris().map { fileResolver.resolve(it, intent.type) }
        pendingImport = files.filter { it.verdict is NoteFileDetector.Verdict.Supported }
        pendingLegacy = pendingImport.isEmpty() && files.any { it.verdict == NoteFileDetector.Verdict.Legacy }
    }

    private fun ImportFailure.messageRes(): Int = when (this) {
        ImportFailure.LEGACY -> R.string.sdocx_import_legacy
        ImportFailure.INVALID, ImportFailure.UNSUPPORTED -> R.string.sdocx_import_invalid
        ImportFailure.PROTECTED -> R.string.file_import_protected
        ImportFailure.IO -> R.string.sdocx_import_io
    }

    private companion object {
        const val KEY_PENDING = "pending_file_import"
        const val KEY_LEGACY = "pending_sdocx_legacy"
    }
}
