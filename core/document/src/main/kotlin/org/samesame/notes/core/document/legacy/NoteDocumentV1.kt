package org.samesame.notes.core.document.legacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate

/** On-disk `document.json` of format version 1 (no title, plain-text body). Read-only; see migrations. */
@Serializable
data class NoteDocumentV1(
    val formatVersion: Int = 1,
    val id: String,
    val pageMode: PageMode,
    val pageRatio: PageRatio = PageRatio.A4Portrait,
    val body: String = "",
    val pages: List<PageSpecV1>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): NoteDocumentV1 = json.decodeFromString(serializer(), text)

        /** Test/fixture helper: how the v1 app wrote it. */
        fun toJson(doc: NoteDocumentV1): String = json.encodeToString(serializer(), doc)
    }
}

@Serializable
data class PageSpecV1(
    val id: String,
    val template: PageTemplate = PageTemplate.Blank,
    val backgroundColor: Long? = null,
    val isBookmarked: Boolean = false,
)
