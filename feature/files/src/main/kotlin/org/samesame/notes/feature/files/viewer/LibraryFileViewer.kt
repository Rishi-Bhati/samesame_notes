package org.samesame.notes.feature.files.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.samesame.notes.core.model.files.FileId

/**
 * Opens a library file from anywhere in the app: the one entry point for the Files screen, note links and deep links.
 *
 * It starts the viewer host activity, which looks the id up and then shows the image, PDF or text viewer, the audio
 * player sheet, the "in Recycle bin" / "not found" message, or hands any other type to the system "open with" intent.
 * It only ever opens: no other action can be triggered through it. Returns false when [fileId] is not a well-formed id
 * (nothing is started then).
 */
fun openLibraryFile(context: Context, fileId: FileId): Boolean {
    if (!fileId.isWellFormed) return false
    val intent = LibraryFileViewer.intent(context, fileId)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    return true
}

object LibraryFileViewer {
    /** The host activity (declared in the app module, which owns the themes). An app unit test keeps this name in step. */
    const val HOST_ACTIVITY = "org.samesame.notes.FileViewerActivity"
    const val EXTRA_FILE_ID = "org.samesame.notes.extra.LIBRARY_FILE_ID"

    fun intent(context: Context, fileId: FileId): Intent =
        Intent().setClassName(context.packageName, HOST_ACTIVITY).putExtra(EXTRA_FILE_ID, fileId.value)

    /** The id an [intent] carries, or null when it is missing or not a well-formed id. */
    fun fileIdOf(intent: Intent?): FileId? =
        intent?.getStringExtra(EXTRA_FILE_ID)?.let(::FileId)?.takeIf { it.isWellFormed }
}
