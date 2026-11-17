package org.samesame.notes.core.ink.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.files.FileIdRemap
import java.io.ByteArrayOutputStream

/** Rewriting the object JSON of a page file without decoding ink (library id remap on import). */
class PageLayerCodecRewriteTest {
    private val old = "0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60"
    private val new = "99999999-8888-4777-8666-555555555555"

    private fun floating(fileId: String?) = FloatingObject(ImageObject("i", "media/a.png", 10f, 10f, libraryFileId = fileId), RectPu(0f, 0f, 10f, 10f))

    private fun page(vararg json: String): ByteArray {
        val els = json.mapIndexed { i, j -> ObjectElement(ElementId(i + 1L), j, box(0f, 0f, 10f, 10f)) }
        return ByteArrayOutputStream().also { PageLayerCodec.encode(PageContent("p", els, 0), it) }.toByteArray()
    }

    @Test fun `the library id of a floating picture is remapped and other objects stay`() {
        val a = floating(old).toJson()
        val b = floating(null).toJson()
        val out = PageLayerCodec.rewriteObjects(page(a, b)) { FileIdRemap.remapFloatingJson(it, mapOf(old to new)) }
        val els = PageLayerCodec.decode(out.inputStream()).filterIsInstance<ObjectElement>()
        assertEquals(2, els.size)
        assertEquals(new, (FloatingObject.fromJson(els[0].json).obj as ImageObject).libraryFileId)
        assertEquals(b, els[1].json)
        assertEquals(1L, els[0].id.value)
    }

    @Test fun `an unchanged page is returned as the same bytes`() {
        val bytes = page(floating("11111111-2222-4333-8444-555555555555").toJson())
        assertSame(bytes, PageLayerCodec.rewriteObjects(bytes) { FileIdRemap.remapFloatingJson(it, mapOf(old to new)) })
    }

    @Test fun `garbage is returned unchanged`() {
        val junk = byteArrayOf(1, 2, 3)
        assertSame(junk, PageLayerCodec.rewriteObjects(junk) { it + "x" })
    }
}
