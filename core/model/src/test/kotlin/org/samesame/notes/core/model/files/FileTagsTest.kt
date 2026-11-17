package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Limits

class FileTagsTest {
    @Test fun `tags are trimmed, lower-cased, whitespace-collapsed and lose a leading hash`() {
        assertThat(FileTags.normalize("  #Tax  2025 ")).isEqualTo("tax 2025")
        assertThat(FileTags.normalize("RECEIPTS")).isEqualTo("receipts")
        assertThat(FileTags.normalize("#home")).isEqualTo("home")
    }

    @Test fun `a blank tag is rejected`() {
        assertThat(FileTags.normalize("")).isNull()
        assertThat(FileTags.normalize("   ")).isNull()
        assertThat(FileTags.normalize("#")).isNull()
    }

    @Test fun `tag length is capped in code points`() {
        val long = "a".repeat(Limits.MAX_FILE_TAG_LENGTH + 10)
        assertThat(FileTags.normalize(long)).hasLength(Limits.MAX_FILE_TAG_LENGTH)
        assertThat(FileTags.normalize("😀".repeat(Limits.MAX_FILE_TAG_LENGTH + 2))).hasLength(Limits.MAX_FILE_TAG_LENGTH * 2)
    }

    @Test fun `a list is normalised, de-duplicated in order and capped`() {
        assertThat(FileTags.normalizeAll(listOf("Home", "home", "#Work", " ", "Tax")))
            .containsExactly("home", "work", "tax").inOrder()
        val many = (1..Limits.MAX_FILE_TAGS + 5).map { "tag$it" }
        assertThat(FileTags.normalizeAll(many)).hasSize(Limits.MAX_FILE_TAGS)
    }

    @Test fun `the description is trimmed and capped in code points`() {
        assertThat(FileTags.normalizeDescription("  A receipt  ")).isEqualTo("A receipt")
        val long = "x".repeat(Limits.MAX_FILE_DESCRIPTION_LENGTH + 5)
        assertThat(FileTags.normalizeDescription(long)).hasLength(Limits.MAX_FILE_DESCRIPTION_LENGTH)
        assertThat(FileTags.normalizeDescription("😀".repeat(Limits.MAX_FILE_DESCRIPTION_LENGTH + 1)))
            .hasLength(Limits.MAX_FILE_DESCRIPTION_LENGTH * 2)
    }
}
