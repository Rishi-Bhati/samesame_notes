package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.sdocx.SdocxFormatException
import org.samesame.notes.core.sdocx.SdocxLegacyFormatException

class SdocxImportErrorTest {

    @Test
    fun `legacy files are reported as unsupported, not invalid`() {
        val error = sdocxImportErrorOf(SdocxLegacyFormatException(2033, "old"))

        assertThat(error).isEqualTo(SdocxImportError.LEGACY_UNSUPPORTED)
    }

    @Test
    fun `malformed archives and unknown failures are invalid`() {
        assertThat(sdocxImportErrorOf(SdocxFormatException("bad"))).isEqualTo(SdocxImportError.INVALID)
        assertThat(sdocxImportErrorOf(IllegalStateException("boom"))).isEqualTo(SdocxImportError.INVALID)
    }

    @Test
    fun `media extension comes from the stored name`() {
        assertThat(extensionOf("media/image.PNG")).isEqualTo("png")
        assertThat(extensionOf("media/no-extension")).isEqualTo("bin")
        assertThat(extensionOf("media/trailing.")).isEqualTo("bin")
    }
}
