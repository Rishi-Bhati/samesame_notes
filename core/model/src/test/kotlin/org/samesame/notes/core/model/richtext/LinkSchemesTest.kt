package org.samesame.notes.core.model.richtext

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class LinkSchemesTest {

    @Test fun `web and mail links open`() {
        assertThat(LinkSchemes.isOpenable("https://example.com/a")).isTrue()
        assertThat(LinkSchemes.isOpenable("HTTP://example.com")).isTrue()
        assertThat(LinkSchemes.isOpenable("mailto:a@b.c")).isTrue()
    }

    @Test fun `dangerous or private schemes never open`() {
        for (url in listOf(
            "javascript:alert(1)", " JavaScript:alert(1)", "java\tscript:alert(1)", "\u0001javascript:x",
            "file:///data/data/org.samesame.notes/files/notes/x.ssnote", "content://org.samesame.notes.files/f/x/y",
            "intent://x#Intent;component=org.samesame.notes/.ComposerActivity;end", "data:text/html,<b>x</b>",
            "market://details?id=x", "samesame://file/00000000-0000-0000-0000-000000000000", "", "relative/path",
        )) {
            assertWithMessage(url).that(LinkSchemes.isOpenable(url)).isFalse()
        }
    }

    @Test fun `exports keep safe absolute links and relative references`() {
        assertThat(LinkSchemes.isExportable("https://example.com")).isTrue()
        assertThat(LinkSchemes.isExportable("tel:+123")).isTrue()
        assertThat(LinkSchemes.isExportable("samesame://file/00000000-0000-0000-0000-000000000000?n=a")).isTrue()
        assertThat(LinkSchemes.isExportable("files/a b.pdf")).isTrue()
        assertThat(LinkSchemes.isExportable("#top")).isTrue()
        assertThat(LinkSchemes.isExportable("files/a.pdf", allowRelative = false)).isFalse()
    }

    @Test fun `exports drop script, file and unknown schemes`() {
        for (url in listOf("javascript:alert(1)", "vbscript:x", "file://server/share", "data:text/html,x", "intent://x", " ")) {
            assertWithMessage(url).that(LinkSchemes.isExportable(url)).isFalse()
        }
    }

    @Test fun `a colon after a path character is not a scheme`() {
        assertThat(LinkSchemes.schemeOf("a/b:c")).isEmpty()
        assertThat(LinkSchemes.schemeOf("?q=a:b")).isEmpty()
        assertThat(LinkSchemes.schemeOf("1abc:x")).isEmpty()
    }
}
