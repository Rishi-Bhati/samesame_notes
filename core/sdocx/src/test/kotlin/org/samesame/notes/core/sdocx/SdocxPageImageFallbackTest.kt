package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SdocxPageImageFallbackTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10)
    private val imageUuid = "77777777-7777-7777-7777-777777777777"

    @Test
    fun `adds a full page image object and registers its media`() {
        val archive = SdocxFixtures.archive(includeUnknownObject = false)
        val pageId = archive.pages.single().pageId

        val out = SdocxPageImageFallback.apply(
            archive,
            mapOf(pageId to png),
            newUuid = { imageUuid },
            nowUs = { SdocxFixtures.MODIFIED_US + 1 },
        )

        val objects = out.pages.single().layers.single().objects
        assertThat(objects).hasSize(2)
        assertThat(objects.last().objectType).isEqualTo(SdocxFormat.ObjectType.IMAGE)
        assertThat(objects.last().uuid).isEqualTo(imageUuid)

        val entry = out.media.entries.last()
        assertThat(entry.bindNumber).isEqualTo(1)
        assertThat(entry.fileName).isEqualTo("page-$pageId.png")
        assertThat(entry.refCount).isEqualTo(1)
        assertThat(entry.fileHash).isEqualTo("4c4b6a3be1314ab86138bef4314dde022e600960d8689a2c8f8631802d20dab6")
        assertThat(out.mediaFiles.getValue("media/page-$pageId.png")).isEqualTo(png)
    }

    @Test
    fun `fallback archive rebuilds and re-reads with the image intact`() {
        val out = SdocxPageImageFallback.apply(
            SdocxFixtures.archive(),
            mapOf(SdocxFixtures.UUID_1 to png),
            newUuid = { imageUuid },
            nowUs = { SdocxFixtures.MODIFIED_US + 3 },
        )
        val bytes = SdocxWriter().writeToBytes(out)
        val reread = SdocxReader().read(bytes)

        val image = reread.pages.single().layers.single().objects.last()
        assertThat(image.objectType).isEqualTo(SdocxFormat.ObjectType.IMAGE)
        assertThat(image.uuid).isEqualTo(imageUuid)
        assertThat(image.modifiedTime).isEqualTo(SdocxFixtures.MODIFIED_US + 3)
        assertThat(reread.mediaFiles.getValue("media/page-${SdocxFixtures.UUID_1}.png")).isEqualTo(png)
        assertThat(reread.media.entries.last().fileName).isEqualTo("page-${SdocxFixtures.UUID_1}.png")
        // The lossless path is stable on the fallback output too.
        assertThat(SdocxWriter().writeToBytes(reread)).isEqualTo(bytes)
    }
}
