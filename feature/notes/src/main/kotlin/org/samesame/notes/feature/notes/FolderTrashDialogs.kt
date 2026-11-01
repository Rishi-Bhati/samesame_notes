package org.samesame.notes.feature.notes

import androidx.annotation.StringRes
import org.samesame.notes.core.model.Folder

/** A dialog message and its format arguments, so the wording can be tested without a Composable. */
internal data class TrashDialogText(@StringRes val message: Int, val args: List<Int> = emptyList())

/** "Move N folders and M notes to the Trash?" shapes, including the "all the items in it/them" family. */
internal fun trashDialogText(folderCount: Int, noteCount: Int, hasChild: Boolean): TrashDialogText = when {
    folderCount > 0 && noteCount > 0 -> when {
        hasChild && folderCount == 1 && noteCount == 1 ->
            TrashDialogText(R.string.feature_notes_trash_msg_one_folder_has_child_one_note)
        hasChild && folderCount == 1 ->
            TrashDialogText(R.string.feature_notes_trash_msg_one_folder_has_child_n_note, listOf(noteCount))
        hasChild && noteCount == 1 ->
            TrashDialogText(R.string.feature_notes_trash_msg_n_folder_has_child_one_note, listOf(folderCount))
        hasChild ->
            TrashDialogText(R.string.feature_notes_trash_msg_n_folder_has_child_n_note, listOf(folderCount, noteCount))
        folderCount == 1 && noteCount == 1 -> TrashDialogText(R.string.feature_notes_trash_msg_one_folder_one_note)
        folderCount == 1 -> TrashDialogText(R.string.feature_notes_trash_msg_one_folder_n_note, listOf(noteCount))
        noteCount == 1 -> TrashDialogText(R.string.feature_notes_trash_msg_n_folder_one_note, listOf(folderCount))
        else -> TrashDialogText(R.string.feature_notes_trash_msg_n_folder_n_note, listOf(folderCount, noteCount))
    }
    folderCount > 0 -> when {
        hasChild && folderCount == 1 -> TrashDialogText(R.string.feature_notes_trash_msg_one_folder_has_child)
        hasChild -> TrashDialogText(R.string.feature_notes_trash_msg_n_folder_has_child, listOf(folderCount))
        folderCount == 1 -> TrashDialogText(R.string.feature_notes_trash_msg_one_folder)
        else -> TrashDialogText(R.string.feature_notes_trash_msg_n_folder, listOf(folderCount))
    }
    noteCount == 1 -> TrashDialogText(R.string.feature_notes_trash_msg_one_note)
    else -> TrashDialogText(R.string.feature_notes_trash_msg_n_note, listOf(noteCount))
}

/** The permanent-delete family; same shapes as [trashDialogText]. */
internal fun permanentDeleteDialogText(folderCount: Int, noteCount: Int, hasChild: Boolean): TrashDialogText = when {
    folderCount > 0 && noteCount > 0 -> when {
        hasChild && folderCount == 1 && noteCount == 1 ->
            TrashDialogText(R.string.feature_notes_delete_msg_one_folder_has_child_one_note)
        hasChild && folderCount == 1 ->
            TrashDialogText(R.string.feature_notes_delete_msg_one_folder_has_child_n_note, listOf(noteCount))
        hasChild && noteCount == 1 ->
            TrashDialogText(R.string.feature_notes_delete_msg_n_folder_has_child_one_note, listOf(folderCount))
        hasChild ->
            TrashDialogText(R.string.feature_notes_delete_msg_n_folder_has_child_n_note, listOf(folderCount, noteCount))
        folderCount == 1 && noteCount == 1 -> TrashDialogText(R.string.feature_notes_delete_msg_one_folder_one_note)
        folderCount == 1 -> TrashDialogText(R.string.feature_notes_delete_msg_one_folder_n_note, listOf(noteCount))
        noteCount == 1 -> TrashDialogText(R.string.feature_notes_delete_msg_n_folder_one_note, listOf(folderCount))
        else -> TrashDialogText(R.string.feature_notes_delete_msg_n_folder_n_note, listOf(folderCount, noteCount))
    }
    folderCount > 0 -> when {
        hasChild && folderCount == 1 -> TrashDialogText(R.string.feature_notes_delete_msg_one_folder_has_child)
        hasChild -> TrashDialogText(R.string.feature_notes_delete_msg_n_folder_has_child, listOf(folderCount))
        folderCount == 1 -> TrashDialogText(R.string.feature_notes_delete_msg_one_folder)
        else -> TrashDialogText(R.string.feature_notes_delete_msg_n_folder, listOf(folderCount))
    }
    noteCount == 1 -> TrashDialogText(R.string.feature_notes_delete_msg_one_note)
    else -> TrashDialogText(R.string.feature_notes_delete_msg_n_note, listOf(noteCount))
}

internal enum class RestoreToast { Folder, Folders, Items, Note, Notes }

/** "Folder restored." / "Folders restored." / "Items restored." / "Note(s) restored." for the selection. */
internal fun restoreToast(folderCount: Int, noteCount: Int): RestoreToast = when {
    folderCount > 0 && noteCount > 0 -> RestoreToast.Items
    folderCount == 1 -> RestoreToast.Folder
    folderCount > 1 -> RestoreToast.Folders
    noteCount == 1 -> RestoreToast.Note
    else -> RestoreToast.Notes
}

/** True when the folder carries anything (notes or subfolders): the dialog then says "all the items in it/them". */
internal fun hasContents(folder: Folder, allFolders: List<Folder>): Boolean =
    folder.noteCount > 0 || allFolders.any { it.parentId == folder.id }
