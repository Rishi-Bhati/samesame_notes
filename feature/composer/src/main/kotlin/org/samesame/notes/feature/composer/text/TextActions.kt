package org.samesame.notes.feature.composer.text

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Share / Web search of selected text (the read-only selection menu). Both hand the text to other apps by intent. */
internal object TextActions {
    fun share(context: Context, text: String) {
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The system web search (the user's search app); nothing when there is none. */
    fun webSearch(context: Context, text: String) {
        val query = text.trim().take(MAX_QUERY)
        if (query.isEmpty()) return
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(search)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private const val MAX_QUERY = 1000
}
