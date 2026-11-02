package org.samesame.notes.feature.composer.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.feature.composer.history.BodyTextTarget

/**
 * Structure of the open note: everything in `NoteDocument` except the live body text, which is read from the
 * bound [BodyTextTarget] only when it changed (the Editable to RichText conversion is not free).
 * Main-thread only.
 */
class DocumentHolder(initial: NoteDocument) {
    private var doc: NoteDocument = initial
    private var bodySource: BodyTextTarget? = null
    private var bodyDirty = false

    /** Bumped by every change to the document file's content. */
    var revision: Long = 0
        private set
    private var savedRevision: Long = 0

    /** Called synchronously after every structural change. */
    var onChanged: () -> Unit = {}

    private val _structure = MutableStateFlow(doc)

    /** Structure for layout and chrome. `body` is stale here; use [snapshot] for a full document. */
    val structure: StateFlow<NoteDocument> = _structure.asStateFlow()

    val current: NoteDocument get() = doc
    val isDirty: Boolean get() = revision != savedRevision

    fun bindBody(target: BodyTextTarget?) {
        bodySource = target
    }

    fun onBodyChanged() {
        bodyDirty = true
        revision++
    }

    fun setTitle(title: String) = update { it.copy(title = title) }
    fun setLayout(layout: PageLayout) = update { it.copy(layout = layout) }
    fun setStyle(style: NoteStyle) = update { it.copy(style = style) }
    fun setPages(pages: List<PageSpec>) = update { it.copy(pages = pages) }
    fun setRecordings(recordings: List<NoteRecording>) = update { it.copy(recordings = recordings) }

    fun updatePage(spec: PageSpec) = update { d -> d.copy(pages = d.pages.map { if (it.id == spec.id) spec else it }) }

    fun setInlineObject(id: String, obj: NoteObject?) = update { d ->
        val rest = d.inlineObjects.filterNot { it.id == id }
        d.copy(inlineObjects = if (obj == null) rest else rest + obj)
    }

    fun inlineObject(id: String): NoteObject? = doc.inlineObjects.firstOrNull { it.id == id }

    /** Full document for a save, plus the revision to pass to [markSaved] once it is on disk. */
    fun snapshot(): Pair<NoteDocument, Long> {
        val src = bodySource
        if (bodyDirty && src != null) {
            doc = doc.copy(body = src.toRichText())
            bodyDirty = false
        }
        return doc to revision
    }

    fun markSaved(revision: Long) {
        if (revision > savedRevision) savedRevision = revision
    }

    /** Body as last snapshotted (use the bound target for the live one). */
    val bodyOrEmpty: RichText get() = doc.body

    private inline fun update(change: (NoteDocument) -> NoteDocument) {
        doc = change(doc)
        revision++
        _structure.value = doc
        onChanged()
    }
}
