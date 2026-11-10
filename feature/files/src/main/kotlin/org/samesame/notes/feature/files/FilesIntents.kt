package org.samesame.notes.feature.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.samesame.notes.core.model.files.LibraryFile

/** Intents for opening and sharing library files. Every URI is one of our own provider's (read grant per intent). */
object FilesIntents {
    fun view(file: LibraryFile, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType.ifBlank { "*/*" }).apply {
            clipData = ClipData.newRawUri(file.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun send(files: List<LibraryFile>, uris: List<Uri>): Intent {
        val type = commonMime(files)
        val clip = ClipData.newRawUri(files.first().displayName, uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        return intent.setType(type).apply {
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** The one MIME type of [files] when they share it, else the shared top-level type with a wildcard, else star-star. */
    fun commonMime(files: List<LibraryFile>): String {
        val types = files.map { it.mimeType.lowercase().ifBlank { "*/*" } }.distinct()
        if (types.size == 1) return types.single()
        val tops = types.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1) "${tops.single()}/*" else "*/*"
    }

    /** Starts [intent]; false when no app handles it. */
    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent); true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun launchView(context: Context, file: LibraryFile, uri: Uri): Boolean = launch(context, view(file, uri))

    fun copyText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
