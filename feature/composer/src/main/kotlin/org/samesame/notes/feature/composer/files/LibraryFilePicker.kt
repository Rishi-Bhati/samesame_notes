package org.samesame.notes.feature.composer.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.samesame.notes.core.model.files.FileId

/**
 * Entry points the composer needs from the Files feature, which it can't depend on (features don't depend on each other).
 * The app provides them around its navigation host; while they are null the composer hides what needs them.
 *
 * Wiring in `app` (it depends on both features):
 * ```
 * CompositionLocalProvider(
 *     LocalLibraryFilePicker provides { onPicked, onDismiss -> FilePickerSheet(onPicked = onPicked, onDismiss = onDismiss) },
 *     LocalLibraryFilesNavigation provides object : LibraryFilesNavigation { ... },
 * ) { NavHost(...) }
 * ```
 */

/** Sheet that lets the user pick library files; calls `onPicked(ids)` once (non-empty) or `onDismiss`. */
typealias LibraryFilePickerSheet = @Composable (onPicked: (List<FileId>) -> Unit, onDismiss: () -> Unit) -> Unit

/** Insert > From Files is shown only when this is not null. */
val LocalLibraryFilePicker = staticCompositionLocalOf<LibraryFilePickerSheet?> { null }

/** Opens the Files screen; "Open in Files" and "Search Files for ..." are shown only when this is provided. */
interface LibraryFilesNavigation {
    /** Shows the file (its folder in the Files screen, or its info). */
    fun openInFiles(id: FileId)

    /** Opens the Files screen searching for [query] (a missing file's name). */
    fun searchFiles(query: String)
}

val LocalLibraryFilesNavigation = staticCompositionLocalOf<LibraryFilesNavigation?> { null }
