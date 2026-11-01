package org.samesame.notes.feature.composer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.samesame.notes.core.data.repository.EditorPreferences
import org.samesame.notes.core.data.repository.EditorPreferencesRepository
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec

class FakePrefs : EditorPreferencesRepository {
    private val state = MutableStateFlow(EditorPreferences())
    override val preferences: Flow<EditorPreferences> = state
    override suspend fun update(transform: (EditorPreferences) -> EditorPreferences) { state.value = transform(state.value) }
    override suspend fun addRecentColor(argb: Long) = update { it.copy(recentColors = listOf(argb) + it.recentColors) }
}

/** A page element that needs no native Ink objects. */
fun fakeElement(id: Long): PageElement = ObjectElement(ElementId(id), "{}", box(0f, 0f, 10f, 10f))

fun doc(vararg pageIds: String) = NoteDocument(id = "n1", pages = pageIds.map { PageSpec(it) })

class FakeStorage {
    val saved = ArrayList<NoteSnapshot>()
    val packs = ArrayList<Boolean>()
    var failures = 0
    val pageFiles = HashMap<String, PageContent>()

    suspend fun load(pageId: String): PageContent = pageFiles[pageId] ?: PageContent(pageId, emptyList(), 0)
    suspend fun save(s: NoteSnapshot) {
        if (failures > 0) { failures--; error("disk full") }
        saved += s
    }
    suspend fun pack(close: Boolean) { packs += close }
}
