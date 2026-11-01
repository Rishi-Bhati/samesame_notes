package org.samesame.notes.feature.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.samesame.notes.core.data.transfer.TransferItem
import org.samesame.notes.core.data.transfer.TransferNaming
import java.io.File

/** Hands files in `cache/share` to the system share sheet through the module's FileProvider. No network is involved. */
object ShareFiles {
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** A fresh file in the share cache, for callers that render a single note themselves (the editor). */
    fun newCacheFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "share/editor")
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, name)
    }

    /** `ACTION_SEND` for one file, `ACTION_SEND_MULTIPLE` for several; wrapped in a chooser. */
    fun chooser(context: Context, files: List<Pair<File, String>>, title: CharSequence?): Intent {
        require(files.isNotEmpty())
        val uris = ArrayList(files.map { uriFor(context, it.first) })
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files[0].second)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .setType(TransferNaming.commonMimeType(files.map { it.second }))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri(null, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        return Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareItems(context: Context, items: List<TransferItem>, title: CharSequence?) =
        context.startActivity(chooser(context, items.map { it.file to it.mimeType }, title))

    fun shareFile(context: Context, file: File, mimeType: String, title: CharSequence?) =
        context.startActivity(chooser(context, listOf(file to mimeType), title))
}
