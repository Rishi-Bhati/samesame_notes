package org.samesame.notes.core.model.files

import java.io.InputStream

/** One file of a folder subtree for a streaming zip export. [open] returns a fresh stream of the stored content. */
data class FolderExportFile(
    /** Folder names below the exported folder, top level first; empty when the file is directly in it. */
    val folderPath: List<String>,
    val name: String,
    val size: Long,
    val open: () -> InputStream,
)
