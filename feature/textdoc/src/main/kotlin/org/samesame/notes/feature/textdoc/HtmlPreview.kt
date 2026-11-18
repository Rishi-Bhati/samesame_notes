package org.samesame.notes.feature.textdoc

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Sandboxed preview of an HTML document: no JavaScript, no network and no access to app files or content providers.
 * The markup comes from the file itself, so nothing else may run.
 */
@Composable
fun HtmlPreview(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                configureSandboxedWebView(this)
            }
        },
        update = { view ->
            // loadDataWithBaseURL with a null base URL keeps relative links from resolving to stored files.
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
        onRelease = { view ->
            runCatching {
                view.stopLoading()
                view.destroy()
            }
        },
    )
}

internal fun configureSandboxedWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        blockNetworkLoads = true
        loadsImagesAutomatically = false
    }
}
