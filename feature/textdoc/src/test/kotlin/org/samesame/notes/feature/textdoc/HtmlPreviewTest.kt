package org.samesame.notes.feature.textdoc

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HtmlPreviewTest {

    @Test
    fun configureSandboxedWebViewEnforcesSecuritySettings() {
        val context = RuntimeEnvironment.getApplication()
        val webView = WebView(context)

        configureSandboxedWebView(webView)

        assertThat(webView.settings.javaScriptEnabled).isFalse()
        assertThat(webView.settings.allowFileAccess).isFalse()
        assertThat(webView.settings.allowContentAccess).isFalse()
        assertThat(webView.settings.blockNetworkLoads).isTrue()
        assertThat(webView.settings.domStorageEnabled).isFalse()
        assertThat(webView.settings.loadsImagesAutomatically).isFalse()
    }

    @Test
    fun linksAndRedirectsInThePreviewGoNowhere() {
        val webView = WebView(RuntimeEnvironment.getApplication())

        configureSandboxedWebView(webView)

        val client = shadowOf(webView).webViewClient
        assertThat(client).isSameInstanceAs(NoNavigationClient)
        for (url in listOf("https://example.com", "intent://x#Intent;scheme=market;end", "file:///sdcard/a", "content://x/y")) {
            val request = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            assertThat(client.shouldOverrideUrlLoading(webView, request)).isTrue()
        }
    }
}
