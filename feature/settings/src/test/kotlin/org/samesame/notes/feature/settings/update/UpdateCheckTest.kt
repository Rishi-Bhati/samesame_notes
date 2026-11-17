package org.samesame.notes.feature.settings.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateCheckTest {
    private fun v(s: String) = ReleaseVersion.parse(s)!!

    @Test fun leadingVIsIgnored() { assertThat(v("v0.3.0-alpha")).isEqualTo(v("0.3.0-alpha")) }
    @Test fun patchIsNewer() { assertThat(v("0.3.1-alpha")).isGreaterThan(v("0.3.0-alpha")) }
    @Test fun numericNotLexical() { assertThat(v("0.10.0")).isGreaterThan(v("0.9.9")) }
    @Test fun labelOrder() {
        assertThat(v("1.0.0")).isGreaterThan(v("1.0.0-rc1"))
        assertThat(v("1.0.0-rc1")).isGreaterThan(v("1.0.0-beta2"))
        assertThat(v("1.0.0-beta2")).isGreaterThan(v("1.0.0-alpha"))
    }
    @Test fun labelNumbers() {
        assertThat(v("1.0.0-rc2")).isGreaterThan(v("1.0.0-rc1"))
        assertThat(v("1.0.0-alpha")).isEqualTo(v("1.0.0-alpha0"))
    }
    @Test fun buildMetadataIgnored() { assertThat(v("1.0.0+5")).isEqualTo(v("1.0.0")) }
    @Test fun garbageIsNull() {
        assertThat(ReleaseVersion.parse("garbage")).isNull()
        assertThat(ReleaseVersion.parse("1.0")).isNull()
        assertThat(ReleaseVersion.parse("")).isNull()
    }

    private val payload = """
        {"url":"https://api.github.com/x","html_url":"https://github.com/Rishi-Bhati/samesame_notes/releases/tag/v0.3.0-alpha",
         "tag_name":"v0.3.0-alpha","name":"0.3.0","draft":false,"prerelease":true,"assets":[{"name":"a.apk"}]}
    """.trimIndent()

    @Test fun parsesRealisticPayload() {
        val r = parseLatestRelease(payload)!!
        assertThat(r.displayVersion).isEqualTo("0.3.0-alpha")
        assertThat(r.version).isEqualTo(v("0.3.0-alpha"))
        assertThat(r.url).isEqualTo("https://github.com/Rishi-Bhati/samesame_notes/releases/tag/v0.3.0-alpha")
    }
    @Test fun missingTagIsNull() { assertThat(parseLatestRelease("""{"html_url":"https://github.com/Rishi-Bhati/samesame_notes/x"}""")).isNull() }
    @Test fun notJsonIsNull() { assertThat(parseLatestRelease("<html>")).isNull() }
    @Test fun foreignUrlFallsBack() {
        val r = parseLatestRelease("""{"tag_name":"v1.0.0","html_url":"https://evil.example/x"}""")!!
        assertThat(r.url).isEqualTo(RELEASES_PAGE)
    }
}
