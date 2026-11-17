package org.samesame.notes

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingFileResolverTest {
    private val ownPackage = "org.samesame.notes"

    @Test
    fun foreignContentAllowed() {
        val uri = Uri.parse("content://com.google.android.apps.docs.storage/document/123")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isTrue()
    }

    @Test
    fun fileSchemeRejected() {
        val uri = Uri.parse("file:///data/user/0/org.samesame.notes/files/note.txt")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isFalse()
    }

    @Test
    fun ownPackageAuthorityRejected() {
        val uri = Uri.parse("content://org.samesame.notes/file")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isFalse()
    }

    @Test
    fun ownSubAuthorityRejected() {
        val uri = Uri.parse("content://org.samesame.notes.files/f/123/test.pdf")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isFalse()
    }

    @Test
    fun caseInsensitiveAuthorityBypassRejected() {
        val uri = Uri.parse("content://ORG.SAMESAME.NOTES.FILES/f/123/test.pdf")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isFalse()
    }

    @Test
    fun userInfoAuthorityBypassRejected() {
        val uri = Uri.parse("content://attacker@org.samesame.notes.files/f/123/test.pdf")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isFalse()
    }

    @Test
    fun portAuthorityBypassRejected() {
        val uri = Uri.parse("content://attacker:secret@ORG.SAMESAME.NOTES.FILES:8080/f/123/test.pdf")
        assertThat(IncomingFileResolver.isForeignContent(uri, ownPackage)).isFalse()
    }
}
