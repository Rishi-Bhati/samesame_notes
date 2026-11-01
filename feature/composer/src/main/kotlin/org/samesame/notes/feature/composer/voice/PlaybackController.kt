package org.samesame.notes.feature.composer.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.VoiceSpeed
import java.io.File

/** The part of `MediaPlayer` the controller needs. */
interface PlayerEngine {
    var onCompleted: (() -> Unit)?
    var onError: (() -> Unit)?

    /** Loads [file]; false when it cannot be played. */
    fun prepare(file: File): Boolean
    val durationMs: Int
    val positionMs: Int
    fun start()
    fun pause()
    fun seekTo(ms: Int)
    fun setSpeed(speed: Float)
    fun release()
}

/** One playable recording: [durationMs] <= 0 means unknown (learned from the player once it is loaded). */
data class Track(val id: String, val file: File?, val durationMs: Long)

/**
 * [objectId] is the recording being played (null = nothing open; the play panel shows while it is non-null).
 * [positionMs] / [durationMs] are CUMULATIVE over the whole playlist (a single page voice object is a playlist of one).
 */
data class PlaybackState(
    val objectId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = VoiceSpeed.NORMAL,
)

/**
 * Plays the note's recordings as ONE playlist, like the reference's menu: the seek bar and `pos | total` span the sum of all
 * recordings, play goes on with the next recording when one ends, and ends (stopped, back at 0, panel closed) after the
 * last. [tap] plays a lone page
 * voice object instead (a playlist of one). Main-thread only; the UI calls [refresh] a few times a second.
 * The speed is kept across recordings and objects. [log] traces the transitions (debug builds: [VoiceLog]).
 */
class PlaybackController(
    private val log: (String) -> Unit = {},
    private val engineFactory: () -> PlayerEngine,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var engine: PlayerEngine? = null
    private var current = -1
    private var tracks: List<Track> = emptyList()
    private var single: Track? = null
    private val learned = HashMap<String, Long>()
    private var plan = VoicePlaylist(emptyList())

    private val active: List<Track> get() = single?.let { listOf(it) } ?: tracks

    private fun durationOf(t: Track): Long = if (t.durationMs > 0L) t.durationMs else learned[t.id] ?: 0L
    private fun rebuild() { plan = VoicePlaylist(active.map(::durationOf)) }
    private fun Long.ms(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** The recordings of the open note in list order. Deleting the playing one stops playback; other changes keep it going. */
    fun setTracks(list: List<Track>) {
        tracks = list
        val keep = learned.keys.filter { id -> list.none { it.id == id } }
        keep.forEach(learned::remove)
        if (single != null) return
        rebuild()
        val e = engine
        if (e != null) {
            val idx = list.indexOfFirst { it.id == _state.value.objectId }
            if (idx < 0) { close(); return }
            current = idx
            _state.value = _state.value.copy(positionMs = plan.positionOf(idx, e.positionMs.toLong()).ms(), durationMs = plan.totalMs.ms())
        } else {
            _state.value = _state.value.copy(positionMs = plan.clamp(_state.value.positionMs.toLong()).ms(), durationMs = plan.totalMs.ms())
        }
    }

    /** Tap on a page voice object: plays it from the start, or toggles play/pause when it is already the open one. Returns false when the file cannot be played. */
    fun tap(objectId: String, file: File): Boolean {
        log("object tap $objectId")
        if (_state.value.objectId == objectId && engine != null) {
            togglePlay()
            return true
        }
        close()
        single = Track(objectId, file, 0L)
        rebuild()
        return startAt(0L, play = true)
    }

    /** List row tap: seeks to the beginning of that recording and plays, whatever the state was. */
    fun playTrack(id: String): Boolean {
        if (single != null) close()
        val i = tracks.indexOfFirst { it.id == id }
        log("row tap $id -> index $i of ${tracks.size}")
        if (i < 0) return false
        rebuild()
        return loadTrack(i, 0L, play = true)
    }

    /**
     * Play/pause. Idle: plays the playlist from the position the seek bar was left at (from 0 after the end).
     * Returns false when nothing could be played.
     */
    fun togglePlay(): Boolean {
        val e = engine
        log("toggle play: open=${e != null} playing=${_state.value.playing} pos=${_state.value.positionMs} tracks=${tracks.size} total=${plan.totalMs}")
        if (e == null) {
            if (single != null || tracks.isEmpty()) return false
            val from = _state.value.positionMs.toLong().let { if (it >= plan.totalMs) 0L else it }
            return startAt(from, play = true)
        }
        val s = _state.value
        if (s.playing) {
            e.pause()
            _state.value = s.copy(playing = false, positionMs = enginePosition(e).ms())
            return true
        }
        // Paused at the very end: the next play starts over.
        if (plan.totalMs > 0 && s.positionMs.toLong() >= plan.totalMs) return startAt(0L, play = true)
        e.setSpeed(s.speed)
        e.start()
        _state.value = s.copy(playing = true)
        return true
    }

    fun pause() {
        val e = engine ?: return
        if (_state.value.playing) { e.pause(); _state.value = _state.value.copy(playing = false, positionMs = enginePosition(e).ms()) }
    }

    /**
     * Seek bar / skip buttons: [ms] on the cumulative timeline, clamped to 0..total; crossing a recording boundary
     * switches the player. Reaching the end while playing ends playback. With nothing
     * open only the position is remembered, and the next play starts there.
     */
    fun seekTo(ms: Int) {
        val p = plan.clamp(ms.toLong())
        val e = engine
        log("seek to $ms -> $p (open=${e != null})")
        if (e == null) {
            if (single == null && tracks.isNotEmpty()) _state.value = _state.value.copy(positionMs = p.ms(), durationMs = plan.totalMs.ms())
            return
        }
        val spot = plan.locate(p) ?: return
        val playing = _state.value.playing
        if (playing && plan.totalMs > 0 && p >= plan.totalMs) { close(); return }
        if (spot.index == current) {
            e.seekTo(spot.offsetMs.ms())
            _state.value = _state.value.copy(positionMs = p.ms())
        } else if (loadTrack(spot.index, spot.offsetMs, playing)) {
            _state.value = _state.value.copy(positionMs = p.ms())
        }
    }

    /** Rewind (negative) / forward buttons: [Limits.VOICE_SEEK_STEP_MS]. */
    fun seekBy(deltaMs: Int) = seekTo(_state.value.positionMs + deltaMs)

    fun rewind() = seekBy(-Limits.VOICE_SEEK_STEP_MS)

    /** Forward past the end plays out the playlist's end like the reference: seek to the end, then back to 0 (stopped). */
    fun forward() {
        val target = _state.value.positionMs.toLong() + Limits.VOICE_SEEK_STEP_MS
        if (engine != null && plan.totalMs > 0 && target >= plan.totalMs) {
            seekTo(plan.totalMs.ms())
            if (engine != null) seekTo(0) // paused: it did not end by itself
            return
        }
        seekBy(Limits.VOICE_SEEK_STEP_MS)
    }

    /** Speed button: 1.0x -> 1.25x -> 1.5x -> 2.0x -> 0.5x -> 0.75x. Applies to every following recording too. */
    fun cycleSpeed() {
        val next = VoiceSpeed.next(_state.value.speed)
        _state.value = _state.value.copy(speed = next)
        if (_state.value.playing) engine?.setSpeed(next)
    }

    /** Follows the player position while it plays. */
    fun refresh() {
        val e = engine ?: return
        if (_state.value.playing) _state.value = _state.value.copy(positionMs = enginePosition(e).ms())
    }

    /** Stops playback and closes the panel (end of the playlist, the editor closing, a recording starting); the position goes back to 0. */
    fun close() {
        if (engine != null || _state.value.objectId != null) log("close")
        releaseEngine()
        single = null
        current = -1
        rebuild()
        _state.value = _state.value.copy(objectId = null, playing = false, positionMs = 0, durationMs = plan.totalMs.ms())
    }

    private fun enginePosition(e: PlayerEngine): Long = plan.positionOf(current, e.positionMs.toLong())

    private fun releaseEngine() {
        engine?.release()
        engine = null
    }

    private fun startAt(positionMs: Long, play: Boolean): Boolean {
        val spot = plan.locate(positionMs) ?: return false
        return loadTrack(spot.index, spot.offsetMs, play)
    }

    /** Loads recording [index] at [offsetMs] (a fresh player). On failure everything is closed and false returned. */
    private fun loadTrack(index: Int, offsetMs: Long, play: Boolean): Boolean {
        val list = active
        val t = list.getOrNull(index)
        val file = t?.file
        releaseEngine()
        log("load track $index/${list.size} id=${t?.id} file=${file?.name} offset=$offsetMs play=$play")
        if (t == null || file == null) { log("no file for track $index"); close(); return false }
        val e = engineFactory()
        e.onCompleted = { if (engine === e) onTrackCompleted() }
        e.onError = { if (engine === e) { log("player error on track $index"); close() } }
        if (!e.prepare(file)) { log("prepare failed for track $index"); e.release(); close(); return false }
        engine = e
        current = index
        if (t.durationMs <= 0L && e.durationMs > 0 && learned[t.id] != e.durationMs.toLong()) {
            learned[t.id] = e.durationMs.toLong()
            rebuild()
        }
        e.setSpeed(_state.value.speed)
        if (offsetMs > 0) e.seekTo(offsetMs.ms())
        if (play) e.start()
        _state.value = _state.value.copy(
            objectId = t.id, playing = play, positionMs = plan.positionOf(index, offsetMs).ms(), durationMs = plan.totalMs.ms(),
        )
        return true
    }

    /** A recording ended: the next one starts; after the last, the playback ends. A file that cannot be loaded ends it too. */
    private fun onTrackCompleted() {
        val next = current + 1
        log("track $current ended; next=$next of ${active.size}")
        if (next < active.size) loadTrack(next, 0L, play = true) else close()
    }
}
