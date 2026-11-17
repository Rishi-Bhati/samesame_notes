package org.samesame.notes.core.model.files

/**
 * Entry names for zip exports. Every name is a single path segment: no separators, no `..`, no control or bidi
 * characters, NFC, at most [org.samesame.notes.core.model.Limits.MAX_FILE_NAME_BYTES] bytes. [unique] adds
 * ` (2)`, ` (3)`, ... on clashes, before the extension.
 */
object ZipEntryNames {
    /** A zip-slip-safe single segment. Never empty, never "." or "..". */
    fun safe(raw: String?, fallback: String = "file"): String {
        val name = FileNames.sanitize(raw, fallback)
        return if (name == "." || name == "..") fallback else name
    }

    /** [name], or `base (2).ext`, ... : the first that is not in [taken] (compared ignoring case). */
    fun unique(name: String, taken: Collection<String>): String = FileNames.unique(name, taken)

    /**
     * A relative zip path from sanitised folder segments and a file name. No leading slash, no parent segments, so it
     * can never escape the extraction directory.
     */
    fun path(folders: List<String>, name: String): String =
        (folders.map { safe(it, "folder") } + safe(name)).joinToString("/")

    /**
     * Allocates a distinct `files/<name>` path per id from the files' display names, in first-seen order. Repeated ids
     * keep their first path; clashing names get ` (2)`, ` (3)`, ...
     */
    fun allocate(names: Iterable<Pair<String, String>>): Map<String, String> {
        val taken = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        for ((id, raw) in names) {
            if (id in out) continue
            val base = safe(raw)
            val name = unique(base, taken)
            taken += name
            out[id] = "files/$name"
        }
        return out
    }
}
