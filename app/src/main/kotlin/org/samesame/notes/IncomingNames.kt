package org.samesame.notes

/**
 * Works out a file's display name from everything an incoming intent offers. Files managers often return no
 * `OpenableColumns.DISPLAY_NAME`, so the URI's last path segment (`.../document/primary%3ADownload%2Fx%2Ftest.md`)
 * and `EXTRA_TITLE` stand in. The result is only a label and a type hint: it is stripped of any directory part and
 * control characters and is never used as a path.
 */
object IncomingNames {

    private const val MAX_NAME_LENGTH = 255

    /**
     * The first candidate that carries a file extension, else the first usable one. [lastPathSegment] is only
     * trusted when it looks like a file name (an extension), because many providers use opaque ids there.
     */
    fun pick(displayName: String?, extraTitle: String?, lastPathSegment: String?): String? {
        val provider = sanitize(displayName)
        val title = sanitize(extraTitle)
        val segment = sanitize(lastPathSegment)?.takeIf { hasExtension(it) }
        val candidates = listOfNotNull(provider, title, segment)
        return candidates.firstOrNull(::hasExtension) ?: candidates.firstOrNull()
    }

    /** A bare file name from [raw]: percent-decoded when still encoded, directory and `volume:` parts dropped. */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var name: String = raw
        if (!name.contains('/') && PERCENT_ESCAPE.containsMatchIn(name)) name = percentDecode(name)
        name = name.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
        name = name.filterNot { it.isISOControl() }.trim().take(MAX_NAME_LENGTH).trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        return name
    }

    private fun hasExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "")
        return name.length > ext.length + 1 && ext.length in 1..10 && ext.all { it.isLetterOrDigit() }
    }

    private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

    private fun percentDecode(value: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
