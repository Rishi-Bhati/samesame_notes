package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BundleZipTest {
    private val id1 = "11111111-1111-4111-8111-111111111111"
    private val id2 = "22222222-2222-4222-8222-222222222222"
    private val link1 = "samesame://file/$id1?n=pic.png"
    private val link2 = "samesame://file/$id2?n=../../evil.png"

    @Test fun `linkedIds finds file links in rendered text`() {
        assertThat(BundleZip.linkedIds("see $link1 and $link2 and https://example.com"))
            .containsExactly(id1, id2)
    }

    @Test fun `rewriteLinks replaces known ids and keeps unknown links`() {
        val paths = mapOf(id1 to "files/pic.png")
        val text = "a [$link1](x) b $link2"
        val out = BundleZip.rewriteLinks(text, paths)
        assertThat(out).contains("files/pic.png")
        assertThat(out).contains(link2)
    }

    @Test fun `rewriteLinks percent-encodes spaces and parentheses in a file name`() {
        assertThat(BundleZip.rewriteLinks(link1, mapOf(id1 to "files/Plan v2 (2).pdf")))
            .isEqualTo("files/Plan%20v2%20%282%29.pdf")
    }

    @Test fun `write is streaming and produces safe unique entry names`() {
        val files = listOf(
            ExportLinkedFile(id1, "pic.png") { ByteArrayInputStream("one".toByteArray()) },
            ExportLinkedFile(id2, "../../evil.png") { ByteArrayInputStream("two".toByteArray()) },
        )
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking {
            BundleZip.write(out, "note.md", "body", files = files)
        }
        val entries = unzip(out.toByteArray())
        assertThat(entries.keys).containsExactly("note.md", "files/pic.png", "files/.._.._evil.png").inOrder()
        assertThat(entries["note.md"]!!.toString(Charsets.UTF_8)).isEqualTo("body")
        assertThat(entries["files/pic.png"]!!.toString(Charsets.UTF_8)).isEqualTo("one")
        assertThat(entries.values.all { it.isNotEmpty() }).isTrue()
        assertThat(entries.keys.all { name -> !name.startsWith("/") && ".." !in name.split('/') }).isTrue()
    }

    @Test fun `write resolves name clashes before the extension`() {
        val files = listOf(
            ExportLinkedFile(id1, "a.txt") { ByteArrayInputStream("x".toByteArray()) },
            ExportLinkedFile(id2, "a.txt") { ByteArrayInputStream("y".toByteArray()) },
        )
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking { BundleZip.write(out, "note.md", "", files = files) }
        assertThat(unzip(out.toByteArray()).keys).containsExactly("note.md", "files/a.txt", "files/a (2).txt").inOrder()
    }
}
