package org.samesame.notes.navigation

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.samesame.notes.ComposerActivity
import org.samesame.notes.ProvideLibraryFiles
import org.samesame.notes.feature.files.FilesKey
import org.samesame.notes.feature.files.FilesRoute
import org.samesame.notes.feature.notes.NotesKey
import org.samesame.notes.feature.notes.NotesRoute
import org.samesame.notes.feature.settings.SettingsKey
import org.samesame.notes.feature.settings.SettingsRoute

@Composable
fun SameSameNavDisplay(
    openNoteId: String? = null,
    onOpenNoteConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(NotesKey)
    // Never pop the root: a screen that reports "back" twice (e.g. a dialog result plus the system Back) must
    // not empty the stack, which NavDisplay rejects (device crash, round 3).
    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    LaunchedEffect(openNoteId) {
        if (openNoteId != null) context.startActivity(ComposerActivity.intent(context, openNoteId))
        onOpenNoteConsumed()
    }

    val density = LocalDensity.current
    val open = remember(density) { ScreenTransitions.open<androidx.navigation3.runtime.NavKey>(density) }
    val close = remember(density) { ScreenTransitions.close<androidx.navigation3.runtime.NavKey>(density) }

    ProvideLibraryFiles {
    NavDisplay(
        backStack = backStack,
        onBack = pop,
        // The 96 dp the covered screen slides away must show the theme background, not the (light) window background.
        // The platform animation extends the window's edge pixels there, which on these screens is the background colour.
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        transitionSpec = open,
        popTransitionSpec = close,
        // The reference app has no predictive-back preview (back is handled on release; manifest opts out too).
        predictivePopTransitionSpec = { close() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<NotesKey> {
                NotesRoute(
                    onOpenNote = { note -> context.startActivity(ComposerActivity.intent(context, note.value)) },
                    onOpenSettings = { backStack.add(SettingsKey) },
                    onOpenFiles = { backStack.add(FilesKey) },
                )
            }
            entry<FilesKey> {
                FilesRoute(
                    onBack = pop,
                    onOpenNote = { id -> context.startActivity(ComposerActivity.intent(context, id.value)) },
                )
            }
            entry<SettingsKey> { SettingsRoute(onBack = pop) }
        },
    )
    }
}
