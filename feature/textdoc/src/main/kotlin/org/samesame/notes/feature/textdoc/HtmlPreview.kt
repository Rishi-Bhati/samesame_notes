package org.samesame.notes.feature.textdoc

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Sandboxed preview of an HTML document: no JavaScript, no network, no access to app files or content providers, and
 * no navigation (a link or a `<meta http-equiv="refresh">` goes nowhere; without a client, WebView would hand the URL,
 * `intent:` URIs included, to another app). The markup comes from the file itself, so nothing else may run.
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
        setGeolocationEnabled(false)
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
    }
    webView.webViewClient = NoNavigationClient
}

/** Keeps the preview on the document it was given: every navigation the page starts is dropped. */
internal object NoNavigationClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
}
