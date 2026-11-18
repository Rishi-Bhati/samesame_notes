package org.samesame.notes.feature.files.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.textdoc.TextDocRoute

/**
 * Text viewer and editor: embeds [TextDocRoute] in the Files viewer chrome. Edits save directly to the
 * repository via [org.samesame.notes.core.data.repository.FilesRepository.updateContent].
 */
@Composable
internal fun TextViewer(
    file: LibraryFile,
    uri: Uri,
    bar: @Composable (BarSlots, ChromeColors) -> Unit,
    onOpenWith: () -> Unit,
    onClose: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        bar(BarSlots(), themeChrome())
        TextDocRoute(
            uri = uri,
            libraryFileId = file.id.value,
            displayName = file.displayName,
            mimeType = file.mimeType,
            writable = true,
            showTopBar = false,
            modifier = Modifier.fillMaxSize(),
            onBack = onClose,
        )
    }
}
