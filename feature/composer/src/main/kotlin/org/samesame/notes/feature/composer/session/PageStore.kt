package org.samesame.notes.feature.composer.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.feature.composer.perf.EditorTrace

sealed interface PageSlot {
    data object Loading : PageSlot
    data class Loaded(val content: PageContent) : PageSlot
    data class Failed(val error: Throwable) : PageSlot
}

/**
 * Page layers by id, loaded on demand and kept in an LRU of [maxLoaded] resident pages. Dirty pages
 * (content version differs from the last saved one) are never evicted. Main-thread only; [loader] may
 * switch dispatchers itself.
 */
class PageStore(
    private val scope: CoroutineScope,
    private val loader: suspend (pageId: String) -> PageContent,
    private val maxLoaded: Int = DEFAULT_MAX_LOADED,
    /** Called after every edit that dirtied a page (the autosaver's trigger). */
    var onDirty: () -> Unit = {},
    /** Called synchronously whenever any slot changes (the canvas bridge bumps Compose state here). */
    var onChanged: () -> Unit = {},
) {
    private class Entry(var slot: PageSlot, var savedVersion: Long, var job: Job? = null)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true) // access order: eldest = least recently used

    private val _slots = MutableStateFlow<Map<String, PageSlot>>(emptyMap())

    /** Observable view for the canvas; a new [PageSlot.Loaded] instance per edit. */
    val slots: StateFlow<Map<String, PageSlot>> = _slots.asStateFlow()

    /** Starts loading [pageId] unless it is resident, loading, or was never persisted. Retries a failed one. */
    fun request(pageId: String) {
        val e = entries[pageId]
        if (e != null && e.slot !is PageSlot.Failed) return
        val entry = e ?: Entry(PageSlot.Loading, savedVersion = NEVER_SAVED).also { entries[pageId] = it }
        entry.slot = PageSlot.Loading
        entry.job = scope.launch {
            val result = runCatching { EditorTrace.section("open:page") { loader(pageId) } }
            if (entries[pageId] !== entry) return@launch // removed meanwhile
            result.onSuccess {
                entry.slot = PageSlot.Loaded(it)
                entry.savedVersion = it.version
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                entry.slot = PageSlot.Failed(it)
            }
            evict(keep = pageId)
            publish()
        }
        publish()
    }

    /** Requests every page in [pageIds] and marks them recently used (the canvas calls this with its visible range). */
    fun want(pageIds: Collection<String>) {
        pageIds.forEach { request(it) }
    }

    fun slot(pageId: String): PageSlot? = entries[pageId]?.slot

    /** Loaded content, touching the LRU; null while loading / failed / unknown. */
    fun content(pageId: String): PageContent? = (entries[pageId]?.slot as? PageSlot.Loaded)?.content

    /** Loaded content, loading first if needed; null when loading failed. */
    suspend fun await(pageId: String): PageContent? {
        content(pageId)?.let { return it }
        request(pageId)
        val slot = slots.first { it[pageId] !is PageSlot.Loading }[pageId]
        return (slot as? PageSlot.Loaded)?.content
    }

    /**
     * Replaces the element list of a loaded page, bumping its version and marking it dirty. Returns false when the
     * page is not resident (callers `await` first).
     */
    fun edit(pageId: String, transform: (List<PageElement>) -> List<PageElement>): Boolean {
        val entry = entries[pageId] ?: return false
        val loaded = entry.slot as? PageSlot.Loaded ?: return false
        val c = loaded.content
        entry.slot = PageSlot.Loaded(PageContent(pageId, transform(c.elements), c.version + 1))
        publish()
        onDirty()
        return true
    }

    /** A new page (empty layer, dirty until its first save). */
    fun create(pageId: String) {
        if (entries.containsKey(pageId)) return
        entries[pageId] = Entry(PageSlot.Loaded(PageContent(pageId, emptyList(), 0)), NEVER_SAVED)
        publish()
        onDirty()
    }

    /** Restores a detached layer (undo of a page delete); dirty so the file is rewritten. */
    fun attach(content: PageContent) {
        entries[content.pageId] = Entry(PageSlot.Loaded(content), NEVER_SAVED)
        publish()
        onDirty()
    }

    fun detach(pageId: String): PageContent? {
        val e = entries.remove(pageId) ?: return null
        e.job?.cancel()
        publish()
        return (e.slot as? PageSlot.Loaded)?.content
    }

    fun hasDirty(): Boolean = entries.values.any { it.isDirty() }

    /** Snapshot of the dirty layers (immutable, safe to hand to the IO thread). */
    fun dirtyPages(): List<PageContent> =
        entries.values.mapNotNull { e -> (e.slot as? PageSlot.Loaded)?.content?.takeIf { e.isDirty() } }

    /** Marks the given versions as persisted; a page edited since the snapshot stays dirty. */
    fun markSaved(saved: List<PageContent>) {
        for (c in saved) entries[c.pageId]?.let { e ->
            val cur = (e.slot as? PageSlot.Loaded)?.content
            if (cur != null && cur.version >= c.version) e.savedVersion = c.version
        }
        evict(keep = null)
        publish()
    }

    private fun Entry.isDirty(): Boolean {
        val c = (slot as? PageSlot.Loaded)?.content ?: return false
        return c.version != savedVersion
    }

    private fun evict(keep: String?) {
        var loaded = entries.values.count { it.slot is PageSlot.Loaded }
        if (loaded <= maxLoaded) return
        val it = entries.entries.iterator()
        while (it.hasNext() && loaded > maxLoaded) {
            val (id, e) = it.next()
            if (id == keep || e.slot !is PageSlot.Loaded || e.isDirty()) continue
            it.remove()
            loaded--
        }
    }

    private fun publish() {
        _slots.value = entries.mapValues { it.value.slot }
        onChanged()
    }

    companion object {
        const val DEFAULT_MAX_LOADED = 8
        private const val NEVER_SAVED = -1L
    }
}
