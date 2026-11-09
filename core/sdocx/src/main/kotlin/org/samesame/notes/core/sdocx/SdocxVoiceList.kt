package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.VoiceTimelineEvent
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.MediaEntry
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.VoiceDataRecord

/**
 * Writes the note-level recording list (`NoteDocument.recordings`) as the note's voice-data list
 * (core spec §3.4 bit 13, objects spec §7, writer rules §12.3 "media added", §13.3 item 3).
 *
 * Note-level recordings never get an on-page voice object. For an imported note the existing
 * records are reused (matched by [NoteRecording.sourceFileId]), so unchanged ones are re-emitted
 * unchanged, renamed ones keep every field and unknown trailing bytes of the raw record, deleted
 * ones are dropped and new ones get a fresh media entry plus record.
 */
object SdocxVoiceList {

    /** Unit used for writing: µs (spec §0.1 "Writers: use µs"). */
    private const val US_PER_MS = 1000L

    /** Wall-clock ms values at or above this read back as µs after x1000 (reader rule: > 10^14 us). */
    private const val ABSOLUTE_MS_FLOOR = 100_000_000_000L

    /**
     * @param recordings the document's current note-level list, in list order.
     * @param audio bytes of a recording's audio (called only for recordings that are not already in the archive).
     * @return [archive] itself when nothing changed, else a copy with updated note property block, note stamps and media.
     */
    fun apply(
        archive: SdocxArchive,
        recordings: List<NoteRecording>,
        nowUs: Long,
        audio: (NoteRecording) -> ByteArray?,
    ): SdocxArchive {
        val existing = archive.note.properties.voiceData.orEmpty()
        val byFileId = existing.associateBy { it.fileId }
        // The importer only surfaces records whose attachment bytes exist (SdocxMapper.mapRecordings).
        val mappedIds = mappedFileIds(archive, existing)

        val media = ArrayList(archive.media.entries)
        val files = LinkedHashMap(archive.mediaFiles)
        var nextBind = (media.maxOfOrNull { it.bindNumber } ?: -1) + 1
        val usedNames = media.map { it.fileName }.toMutableSet()

        val out = ArrayList<VoiceDataRecord>()
        val kept = HashSet<Int>()
        var changed = false
        var nativeIndex = 0
        for (rec in recordings) {
            val old = rec.sourceFileId?.let { id -> if (id in mappedIds) byFileId[id] else null }
            if (old != null && kept.add(old.fileId)) {
                val updated = update(old, rec)
                if (updated !== old) changed = true
                out += updated
                continue
            }
            val bytes = audio(rec)
            if (bytes == null || bytes.isEmpty()) continue // nothing playable to ship
            changed = true
            val ext = rec.media.substringAfterLast('.', "m4a").ifEmpty { "m4a" }
            var fileName = "voice-${rec.id}.$ext"
            while (!usedNames.add(fileName)) fileName = "voice-${rec.id}-$nextBind.$ext"
            val bind = nextBind++
            media += MediaEntry(
                bindNumber = bind,
                fileName = fileName,
                fileHash = hexSha256(bytes),
                refCount = 1,
                modifiedTime = nowUs,
                isFileAttached = if (archive.media.extended) true else null,
            )
            files["media/$fileName"] = bytes
            out += newRecord(rec, bind, nativeIndex++)
        }
        // Mapped records the document no longer lists were deleted in SameSame; unmapped ones (no attachment bytes) never
        // surfaced, so they stay, after the others.
        val dropped = existing.count { it.fileId in mappedIds && it.fileId !in kept }
        if (dropped > 0) changed = true
        for (e in existing) if (e.fileId !in mappedIds) out += e
        if (!changed) return archive

        val props = archive.note.properties
        val block = LeWriter().apply {
            i32(out.size)
            for (r in out) voiceRecord(this, r)
        }.toByteArray()
        val hasBit = archive.note.propMask and VOICE_BIT != 0
        val newMask = if (out.isEmpty()) archive.note.propMask and VOICE_BIT.inv() else archive.note.propMask or VOICE_BIT
        val section = if (out.isEmpty()) ByteArray(0) else block
        val oldEnd = if (hasBit) props.voiceEnd else props.voiceStart
        val raw = props.raw.copyOfRange(0, props.voiceStart) + section + props.raw.copyOfRange(oldEnd, props.raw.size)

        val note = NoteRecord(
            propOffset = archive.note.propOffset,
            noteFlags = archive.note.noteFlags,
            propMask = newMask,
            formatVersion = archive.note.formatVersion,
            noteId = archive.note.noteId,
            fileRevision = archive.note.fileRevision + 1,
            createdTime = archive.note.createdTime,
            modifiedTime = nowUs,
            width = archive.note.width,
            height = archive.note.height,
            pageHorizontalPadding = archive.note.pageHorizontalPadding,
            pageVerticalPadding = archive.note.pageVerticalPadding,
            minFormatVersion = archive.note.minFormatVersion,
            title = archive.note.title,
            body = archive.note.body,
            properties = parseNotePropertyBlock(raw, newMask),
            docHash = archive.note.docHash,
        )
        return SdocxArchive(
            // The voice list lives in note.note; end_tag.bin mirrors the note stamps.
            endTag = SdocxMirror.endTag(note, archive.endTag, modifiedTimeUs = nowUs, displayModifiedTimeUs = nowUs),
            note = note,
            pageIdInfo = archive.pageIdInfo,
            pages = archive.pages,
            media = MediaRegistry(archive.media.extended, archive.media.formatVersion, media, archive.media.hasTerminator),
            mediaFiles = files,
            extraEntries = archive.extraEntries,
        )
    }

    private fun mappedFileIds(archive: SdocxArchive, existing: List<VoiceDataRecord>): Set<Int> {
        val byBind = archive.mediaByBindNumber
        return existing.filter {
            val name = byBind[it.fileId]?.fileName
            name != null && archive.mediaFiles["media/$name"]?.isNotEmpty() == true
        }.map { it.fileId }.toSet()
    }

    /** Applies edited fields to an existing record; returns [old] itself when nothing differs. */
    private fun update(old: VoiceDataRecord, rec: NoteRecording): VoiceDataRecord {
        val sameName = old.name == rec.name
        val sameCreated = msOf(old.createdTime) == rec.createdAtMs
        val sameElapsed = (old.recordingTime ?: 0L) == rec.elapsedMs
        val sameDisplay = old.playTime == rec.displayDuration
        val oldTimeline = old.events.map { VoiceTimelineEvent(it.action, msOf(it.timestamp)) }
        val sameTimeline = oldTimeline == rec.timeline
        if (sameName && sameCreated && sameElapsed && sameDisplay && sameTimeline) return old
        return VoiceDataRecord(
            fileId = old.fileId,
            name = rec.name,
            playTime = if (sameDisplay) old.playTime else rec.displayDuration,
            createdTime = if (sameCreated) old.createdTime else rec.createdAtMs * US_PER_MS,
            events = if (sameTimeline) old.events else rec.timeline.map { VoiceDataRecord.Event(it.action, toUs(it.timestampMs)) },
            recordingTime = if (sameElapsed) old.recordingTime else rec.elapsedMs,
            extra = old.extra,
        )
    }

    private fun newRecord(rec: NoteRecording, bind: Int, index: Int): VoiceDataRecord {
        val duration = rec.durationMs
        val timeline = rec.timeline.ifEmpty {
            listOf(
                VoiceTimelineEvent(ACTION_START, rec.createdAtMs),
                VoiceTimelineEvent(ACTION_STOP, rec.createdAtMs + duration),
            )
        }
        return VoiceDataRecord(
            fileId = bind,
            name = rec.name.ifEmpty { "Voice %03d".format(index + 1) },
            playTime = rec.displayDuration.ifEmpty { formatPlayTime(duration) },
            createdTime = rec.createdAtMs * US_PER_MS,
            events = timeline.map { VoiceDataRecord.Event(it.action, toUs(it.timestampMs)) },
            recordingTime = duration,
        )
    }

    private fun voiceRecord(w: LeWriter, r: VoiceDataRecord) {
        val body = LeWriter().apply {
            i32(r.fileId)
            str16(r.name)
            str16(r.playTime)
            i64(r.createdTime)
            i32(r.events.size)
            for (e in r.events) { i32(e.action); i64(e.timestamp) }
            r.recordingTime?.let { i64(it) }
            if (r.extra.isNotEmpty()) bytes(r.extra)
        }.toByteArray()
        w.i32(body.size)
        w.bytes(body)
    }

    /** Absolute wall-clock stamps go out as µs (they read back via the > 10^14 rule); small relative stamps stay as ms. */
    private fun toUs(ms: Long): Long = if (kotlin.math.abs(ms) > ABSOLUTE_MS_FLOOR) ms * US_PER_MS else ms

    private fun msOf(value: Long): Long =
        if (kotlin.math.abs(value) > 100_000_000_000_000L) value / 1000L else value

    internal fun formatPlayTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun hexSha256(bytes: ByteArray): String =
        sha256(bytes).joinToString("") { "%02x".format(it) }

    private const val VOICE_BIT = 0x2000
    private const val ACTION_START = 1
    private const val ACTION_STOP = 4
}
