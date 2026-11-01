package org.samesame.notes.feature.composer.voice

/** One note-level recording of the note: a row of the "Recorded audio" list. */
data class VoiceRecording(
    val id: String,
    val media: String,
    val durationMs: Long,
    /** Empty falls back to the generated "Voice NNN" row name. */
    val name: String = "",
    /** Wall-clock creation time (ms); 0 shows no date. */
    val createdAtMs: Long = 0L,
)

/**
 * State of the "Recorded audio" list: the note's recordings in recording
 * order, and the edit mode with its checked rows. Pure, so the rules are unit-tested; the composable only renders it.
 */
data class VoiceListState(
    val recordings: List<VoiceRecording> = emptyList(),
    val selectMode: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    val selectedRecordings: List<VoiceRecording> get() = recordings.filter { it.id in selected }
    val allSelected: Boolean get() = recordings.isNotEmpty() && selected.size == recordings.size

    /** 1-based number of a recording, for the derived name "Voice NNN" (when the recording has no stored name). */
    fun numberOf(id: String): Int = recordings.indexOfFirst { it.id == id } + 1
}

/** Rules of the list. Every function returns a new state; the caller plays, deletes and shows dialogs. */
object VoiceRecordingList {
    /**
     * The note's recordings changed (a new one, an undo, a delete): keeps the checks of recordings that still exist and
     * leaves edit mode when none remain.
     */
    fun update(state: VoiceListState, recordings: List<VoiceRecording>): VoiceListState {
        val ids = recordings.mapTo(HashSet()) { it.id }
        return state.copy(
            recordings = recordings,
            selected = state.selected.filterTo(HashSet()) { it in ids },
            selectMode = state.selectMode && recordings.isNotEmpty(),
        )
    }

    /** Tap on a row in edit mode checks or unchecks it. Outside edit mode the caller plays the row instead. */
    fun toggle(state: VoiceListState, id: String): VoiceListState {
        if (!state.selectMode || state.recordings.none { it.id == id }) return state
        return state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id)
    }

    /** Long press on a row: outside edit mode it starts edit mode with that row checked; inside, it checks the row. */
    fun longPress(state: VoiceListState, id: String): VoiceListState {
        if (state.recordings.none { it.id == id }) return state
        return if (state.selectMode) toggle(state, id) else state.copy(selectMode = true, selected = setOf(id))
    }

    /** The select-all checkbox: checks every row, or clears them when all are checked already. */
    fun selectAll(state: VoiceListState): VoiceListState {
        if (!state.selectMode) return state
        return state.copy(selected = if (state.allSelected) emptySet() else state.recordings.mapTo(HashSet()) { it.id })
    }

    /** The header's Edit button: starts select mode with nothing checked. */
    fun enterSelectMode(state: VoiceListState): VoiceListState =
        if (state.recordings.isEmpty()) state else state.copy(selectMode = true, selected = emptySet())

    /** Leaves edit mode and clears the checks. */
    fun exitSelectMode(state: VoiceListState): VoiceListState = state.copy(selectMode = false, selected = emptySet())
}
