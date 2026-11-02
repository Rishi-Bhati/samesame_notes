package org.samesame.notes.core.document

import kotlinx.serialization.Serializable
import org.samesame.notes.core.document.legacy.NoteDocumentV1
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteJson
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.ViewState
import org.samesame.notes.core.model.richtext.RichText
import java.io.File

object NoteDocumentMigrations {
    /** A v6 (or older) document's remaining field: v7 moved the reading position to the per-note store. */
    @Serializable
    private data class LegacyDocumentViewState(val viewState: ViewState? = null)

    /**
     * Reads the `viewState` field that v6 documents still carried (the old reader). v7 has no such field; the value is
     * copied into the per-note view-state store on open and never written back into the document.
     */
    fun legacyViewState(text: String): ViewState? =
        runCatching { NoteJson.decodeFromString(LegacyDocumentViewState.serializer(), text).viewState }.getOrNull()

    /** v6 -> v7 removed the document's `viewState` field; everything else is the same document. */
    fun v6ToV7(v6: NoteDocument): NoteDocument = v6.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION)

    /**
     * v5 -> v6 only adds the defaulted [org.samesame.notes.core.model.document.PageSpec.size]. A v5 page has none, which
     * means "the note's global page ratio", so every old page (imported PDF pages included) keeps exactly its old shape.
     */
    fun v5ToV6(v5: NoteDocument): NoteDocument = v5.copy(
        formatVersion = NOTE_DOCUMENT_FORMAT_VERSION,
        pages = v5.pages.map { if (it.size == null) it else it.copy(size = null) },
    )

    /** v4 -> v5 only adds the [org.samesame.notes.core.model.document.PaintingObject] type, so nothing in a v4 document changes. */
    fun v4ToV5(v4: NoteDocument): NoteDocument = v4.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION)

    /**
     * v3 -> v4 only adds the defaulted [NoteDocument.recordings] list, so the document is the same
     * value with the current version. Applied in memory; the working copy is rewritten on the next commit.
     */
    fun v3ToV4(v3: NoteDocument): NoteDocument = v3.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION)

    /**
     * v2 -> v3 only adds defaulted fields and object types, so the document is the same value with the
     * current version. Applied in memory; the working copy is rewritten on the next commit.
     */
    fun v2ToV3(v2: NoteDocument): NoteDocument = v2.copy(formatVersion = NOTE_DOCUMENT_FORMAT_VERSION)

    /** Pure structural migration. v1 kept titles only in the catalog, so [titleFromDb] is passed in. */
    fun v1ToV2(v1: NoteDocumentV1, titleFromDb: String): NoteDocument = NoteDocument(
        id = v1.id,
        title = titleFromDb,
        layout = PageLayout(mode = v1.pageMode, ratio = v1.pageRatio),
        body = RichText.of(v1.body), // normalise() builds one paragraph entry per line
        pages = v1.pages.map {
            PageSpec(
                id = it.id,
                template = TemplateRef.Builtin(it.template),
                backgroundColor = it.backgroundColor,
                isBookmarked = it.isBookmarked,
            )
        },
    )

    /**
     * Migrates a whole v1 bundle in place: every page file is re-encoded as v2 (v1 files get random
     * element ids on decode, so this is what makes ids stable), then `document.json` is replaced last
     * as the commit point. Safe to re-run after a crash: pages already in v2 decode to the same ids.
     */
    fun migrateBundleV1(bundleDir: File, v1: NoteDocumentV1, titleFromDb: String): NoteDocument {
        val doc = v1ToV2(v1, titleFromDb)
        for (page in doc.pages) {
            val file = File(bundleDir, "ink/${page.id}.ink")
            if (!file.exists()) continue
            val elements = file.inputStream().buffered().use { PageLayerCodec.decode(it) }
            writeFileAtomically(file) { PageLayerCodec.encode(PageContent(page.id, elements, 0), it) }
        }
        writeFileAtomically(File(bundleDir, DOCUMENT_FILE)) { it.write(doc.toJson().encodeToByteArray()) }
        return doc
    }
}
