package org.samesame.notes.core.export

import java.io.InputStream

/** A library file a note links to, offered for "export with files" by the repository. [open] returns a fresh stream. */
data class ExportLinkedFile(
    val id: String,
    val name: String,
    val open: () -> InputStream,
)
