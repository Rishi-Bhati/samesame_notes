package org.samesame.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IncomingNamesTest {

    @Test
    fun providerNameWins() {
        assertThat(IncomingNames.pick("Hi.md", "other.txt", "x/y.txt")).isEqualTo("Hi.md")
    }

    @Test
    fun fallsBackToDecodedLastPathSegment() {
        // Uri.lastPathSegment of .../document/primary%3ADownload%2Fx%2Ftest.md
        assertThat(IncomingNames.pick(null, null, "primary:Download/x/test.md")).isEqualTo("test.md")
        // still percent-encoded (a raw encoded segment)
        assertThat(IncomingNames.pick(null, null, "primary%3ADownload%2Fx%2Ftest.md")).isEqualTo("test.md")
        assertThat(IncomingNames.pick("  ", null, "msf:3a%20b.md")).isEqualTo("3a b.md")
    }

    @Test
    fun usesExtraTitleWhenTheProviderGivesNoName() {
        assertThat(IncomingNames.pick(null, "notes.md", "1234")).isEqualTo("notes.md")
        assertThat(IncomingNames.pick(null, "notes.md", "docs/readme.txt")).isEqualTo("notes.md")
    }

    @Test
    fun prefersACandidateWithAnExtension() {
        assertThat(IncomingNames.pick("download", null, "primary:a/test.md")).isEqualTo("test.md")
        assertThat(IncomingNames.pick("download", null, "1234")).isEqualTo("download")
    }

    @Test
    fun opaqueIdsAreNotUsedAsNames() {
        assertThat(IncomingNames.pick(null, null, "1234")).isNull()
        assertThat(IncomingNames.pick(null, null, "msf:1234")).isNull()
        assertThat(IncomingNames.pick(null, null, null)).isNull()
    }

    @Test
    fun namesAreSanitisedNeverPaths() {
        assertThat(IncomingNames.sanitize("../../etc/passwd.md")).isEqualTo("passwd.md")
        assertThat(IncomingNames.sanitize("a\\b\\c.md")).isEqualTo("c.md")
        assertThat(IncomingNames.sanitize("..")).isNull()
        assertThat(IncomingNames.sanitize("a/..")).isNull()
        assertThat(IncomingNames.sanitize("x\u0000y\n.md")).isEqualTo("xy.md")
        assertThat(IncomingNames.sanitize("%2E%2E%2Fsecret.md")).isEqualTo("secret.md")
        assertThat(IncomingNames.sanitize("a".repeat(400) + ".md")!!.length).isAtMost(255)
        assertThat(IncomingNames.sanitize("")).isNull()
    }
}
