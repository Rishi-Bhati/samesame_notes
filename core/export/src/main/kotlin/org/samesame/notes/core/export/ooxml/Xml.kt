package org.samesame.notes.core.export.ooxml

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

/** Escapes for element text and attribute values, dropping characters illegal in XML 1.0. */
internal fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
            c < ' ' -> Unit
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { sb.append(c).append(s[i + 1]); i++ }
            c.isSurrogate() || c == '￾' || c == '￿' -> Unit
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/** Writes OPC parts; `[Content_Types].xml` must be the first entry. */
internal class OpcPackage(out: OutputStream) {
    private val zip = ZipOutputStream(out)
    fun part(name: String, content: String) = part(name, content.toByteArray(Charsets.UTF_8))
    fun part(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
    fun finish() { zip.finish(); zip.flush() }
}

internal fun imageExtension(mime: String) = when (mime) {
    "image/png" -> "png"
    "image/jpeg" -> "jpeg"
    "image/gif" -> "gif"
    else -> error("unsupported image type $mime")
}

internal const val EMU_PER_TWIP = 635L
