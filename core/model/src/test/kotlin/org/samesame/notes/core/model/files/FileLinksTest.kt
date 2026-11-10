package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileLinksTest {
    private val id = FileId("0f8fad5b-d9cb-469f-a165-70867728950e")

    @Test fun `format and parse round trip, the name is percent-encoded`() {
        val link = FileLinks.format(id, "My report (v2) é & co.pdf")
        assertThat(link).startsWith("samesame://file/0f8fad5b-d9cb-469f-a165-70867728950e?n=")
        assertThat(link).doesNotContain(" ")
        val target = FileLinks.parse(link)!!
        assertThat(target.id).isEqualTo(id)
        assertThat(target.nameHint).isEqualTo("My report (v2) é & co.pdf")
    }

    @Test fun `parse accepts links without a name and rejects non-UUID ids`() {
        assertThat(FileLinks.parse("samesame://file/${id.value}")).isEqualTo(FileLinks.Target(id, null))
        assertThat(FileLinks.parse("SameSame://file/${id.value.uppercase()}?n=a")!!.id).isEqualTo(id)
        assertThat(FileLinks.parse("samesame://file/../../etc")).isNull()
        assertThat(FileLinks.parse("samesame://file/1234")).isNull()
        assertThat(FileLinks.parse("https://example.com")).isNull()
        assertThat(FileLinks.parse("samesame://file/${id.value}?n=%ZZ")!!.nameHint).isNull()
    }

    @Test fun `links-only text is recognised for paste interception`() {
        val other = FileId("7c9e6679-7425-40de-944b-e07fc1f90ae7")
        val text = " ${FileLinks.format(id, "a.png")}\n${FileLinks.format(other, "b.pdf")}  "
        assertThat(FileLinks.parseLinksOnly(text)!!.map { it.id }).containsExactly(id, other).inOrder()
        assertThat(FileLinks.parseLinksOnly("see ${FileLinks.format(id, "a.png")}")).isNull()
        assertThat(FileLinks.parseLinksOnly("   ")).isNull()
    }

    @Test fun `ids are well formed only as UUIDs`() {
        assertThat(id.isWellFormed).isTrue()
        assertThat(FileId("x").isWellFormed).isFalse()
        assertThat(FileId("0f8fad5b-d9cb-469f-a165-70867728950e/..").isWellFormed).isFalse()
    }
}
