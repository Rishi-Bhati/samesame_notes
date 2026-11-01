package org.samesame.notes.feature.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract

/**
 * "Create document" picker that tells the provider the real MIME type of the file.
 *
 * The stock `ActivityResultContracts.CreateDocument` call used with a wildcard type creates the file with that type; the provider stores
 * it as an anonymous blob, and PDF viewers and file managers that look at the stored type do not offer it as a PDF.
 * Input is the suggested file name and the MIME type of the content.
 */
class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(documentMime(input.first, input.second))
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data

    companion object {
        /**
         * The type to create a document with. Providers append the MIME type's extension when the file name's own
         * extension maps to another type (`Plan.ssnote` as application/zip becomes `Plan.ssnote.zip`), so a type is only
         * passed when the extension agrees with it; otherwise the file is created as `application/octet-stream`.
         */
        fun documentMime(fileName: String, mimeType: String): String =
            pickMime(mimeType, MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()))

        fun pickMime(mimeType: String, extensionMime: String?): String =
            if (extensionMime != null && extensionMime.equals(mimeType, ignoreCase = true)) mimeType else "application/octet-stream"
    }
}
