package org.samesame.notes.feature.composer.files

import org.samesame.notes.core.model.files.LibraryFile

/**
 * Live filter of the inline `///` picker. [files] arrives newest first and stays in that order; a blank query shows the
 * whole library. Matching is a case-insensitive contains over the name, the tags and the description (0.3.0 fields).
 */
object SlashFileFilter {
    fun filter(files: List<LibraryFile>, query: String): List<LibraryFile> {
        val term = query.trim().lowercase()
        if (term.isEmpty()) return files
        return files.filter { file ->
            file.displayName.lowercase().contains(term) ||
                file.tags.any { it.lowercase().contains(term) } ||
                file.description.lowercase().contains(term)
        }
    }
}
