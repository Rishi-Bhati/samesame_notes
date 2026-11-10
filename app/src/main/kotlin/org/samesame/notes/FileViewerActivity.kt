package org.samesame.notes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.ThemeModeCache
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.feature.files.viewer.FileViewerRoute
import org.samesame.notes.feature.files.viewer.LibraryFileViewer
import javax.inject.Inject

/**
 * Hosts the viewers of library files (started only through `openLibraryFile`). The window is transparent: the image, PDF
 * and text viewers fill it, the audio player is a sheet over the screen below, and a file with no viewer goes straight to
 * the system without a blank page in between. Not exported; it only opens an existing file by id.
 */
@AndroidEntryPoint
class FileViewerActivity : FragmentActivity() {

    @Inject lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileId = LibraryFileViewer.fileIdOf(intent)
        if (fileId == null) {
            finish()
            return
        }
        val launchTheme = ThemeModeCache.read(this)
        enableEdgeToEdge()
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(themeMode = launchTheme))
            val dark = isDarkTheme(settings.themeMode)
            var darkChrome by remember { mutableStateOf(false) }
            SystemBarAppearance(dark || darkChrome)
            SameSameTheme(darkTheme = dark) {
                FileViewerRoute(
                    fileId = fileId,
                    onClose = { finish() },
                    onOpenNote = { id -> startActivity(ComposerActivity.intent(this, id.value)) },
                    onDarkChrome = { darkChrome = it },
                )
            }
        }
    }
}
