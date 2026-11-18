package org.samesame.notes.feature.textdoc

import android.content.Context
import android.webkit.WebView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
}
