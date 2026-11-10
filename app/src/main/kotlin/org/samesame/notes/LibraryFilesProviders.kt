package org.samesame.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.feature.composer.files.LibraryFilePickerSheet
import org.samesame.notes.feature.composer.files.LibraryFilesNavigation
import org.samesame.notes.feature.composer.files.LocalLibraryFileOpener
import org.samesame.notes.feature.composer.files.LocalLibraryFilePicker
import org.samesame.notes.feature.composer.files.LocalLibraryFilesNavigation
import org.samesame.notes.feature.files.picker.FilePickerSheet
import org.samesame.notes.feature.files.viewer.openLibraryFile

private val FilesPicker: LibraryFilePickerSheet = { onPicked, onDismiss -> FilePickerSheet(onPicked = onPicked, onDismiss = onDismiss) }

/**
 * Gives the editor what it needs from the Files feature (the two can't depend on each other): the picker sheet behind
 * Insert > From Files, and the navigation behind "Open in Files" and "Search Files". Both open the Files screen in its
 * own activity on top of the caller.
 */
@Composable
fun ProvideLibraryFiles(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val navigation = remember(context) {
        object : LibraryFilesNavigation {
            override fun openInFiles(id: FileId) { context.startActivity(FilesActivity.showFile(context, id)) }
            override fun searchFiles(query: String) { context.startActivity(FilesActivity.search(context, query)) }
        }
    }
    val opener = remember(context) { { id: FileId -> openLibraryFile(context, id); Unit } }
    CompositionLocalProvider(
        LocalLibraryFilePicker provides FilesPicker,
        LocalLibraryFilesNavigation provides navigation,
        LocalLibraryFileOpener provides opener,
        content = content,
    )
}
