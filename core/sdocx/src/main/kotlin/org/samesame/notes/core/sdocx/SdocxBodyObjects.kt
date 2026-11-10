package org.samesame.notes.core.sdocx

import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteObject
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.sdocx.record.RectF64
import org.samesame.notes.core.sdocx.record.TextCommon

/**
 * Objects anchored in the body text (objects spec §6.4): each occupies one U+FFFC character and has
 * an object-span entry at that position.
 *
 * Import surfaces pictures as [ImageObject]s whose id is derived from the object uuid. Export walks
 * the body's placeholder characters: one that still names a source object keeps that object's
 * record (verbatim, at its new position); one that names a picture added in SameSame gets a new
 * image record; a placeholder with neither is removed from the text, because a reader fails on a
 * placeholder that has no object.
 */
object SdocxBodyObjects {

    private const val ID_PREFIX = "sdocx-"

    fun objectId(uuid: String): String = ID_PREFIX + uuid

    /** A surfaced picture: the editor object and the character it sits on. */
    class Surfaced(val obj: NoteObject, val charPos: Int)

    /** The pictures of [tc] the editor can show; [scale] converts page units to SameSame page units. */
    fun surface(archive: SdocxArchive, tc: TextCommon?, scale: Float): List<Surfaced> {
        if (tc == null) return emptyList()
        val media = archive.mediaByBindNumber
        val seen = HashSet<String>()
        return tc.objectSpans.mapNotNull { span ->
            if (span.objectType != SdocxFormat.ObjectType.IMAGE) return@mapNotNull null
            if (span.charPos !in tc.text.indices || tc.text[span.charPos] != OBJECT_CHAR) return@mapNotNull null
            val info = SdocxImages.info(span.record) ?: return@mapNotNull null
            val name = media[info.imageId]?.fileName ?: return@mapNotNull null
            if (archive.mediaFiles["media/$name"]?.isNotEmpty() != true) return@mapNotNull null
            if (!seen.add(info.uuid)) return@mapNotNull null
            val width = (info.rect.width * scale).toFloat()
            val height = (info.rect.height * scale).toFloat()
            if (!(width > 0f) || !(height > 0f)) return@mapNotNull null
            Surfaced(ImageObject(id = objectId(info.uuid), media = "media/$name", widthPu = width, heightPu = height), span.charPos)
        }
    }

    internal class Prepared(val text: RichText, val spans: List<TextCommon.ObjectSpan>)

    /**
     * Resolves every placeholder of [body]. [previous] is the source body of an imported note;
     * [newRecord] builds the record for an object id that is not a source object (null = none).
     */
    internal fun prepare(body: RichText, previous: TextCommon?, newRecord: (objectId: String) -> ByteArray?): Prepared {
        val text = body.text
        if (OBJECT_CHAR !in text && previous?.objectSpans.isNullOrEmpty()) return Prepared(body, emptyList())
        val styles = ArrayList<CharStyle>(text.length)
        for (run in body.runs) repeat(run.length) { if (styles.size < text.length) styles += run.style }
        while (styles.size < text.length) styles += CharStyle()

        val byId = LinkedHashMap<String, TextCommon.ObjectSpan>()
        val anonymous = ArrayDeque<TextCommon.ObjectSpan>()
        for (span in previous?.objectSpans.orEmpty()) {
            val uuid = parseObjectBase(span.record)?.uuid
            val surfaced = span.objectType == SdocxFormat.ObjectType.IMAGE && SdocxImages.info(span.record) != null
            if (uuid != null && surfaced && objectId(uuid) !in byId) byId[objectId(uuid)] = span else anonymous += span
        }

        val outText = StringBuilder(text.length)
        val outStyles = ArrayList<CharStyle>(text.length)
        val spans = ArrayList<TextCommon.ObjectSpan>()
        for (i in text.indices) {
            val c = text[i]
            if (c != OBJECT_CHAR) {
                outText.append(c); outStyles += styles[i]
                continue
            }
            val id = styles[i].objectId
            // A placeholder without an id stands for a source object the editor could not show, or for
            // one whose id an edit lost: it takes the next source object in order.
            val source = if (id != null) {
                byId.remove(id)
            } else {
                anonymous.removeFirstOrNull() ?: byId.keys.firstOrNull()?.let(byId::remove)
            }
            val span = source?.let { TextCommon.ObjectSpan(it.objectType, it.record, outText.length) }
                ?: id?.let(newRecord)?.let { TextCommon.ObjectSpan(SdocxFormat.ObjectType.IMAGE, it, outText.length) }
            if (span == null) continue // a placeholder without an object is not written
            spans += span
            outText.append(c); outStyles += styles[i]
        }
        val runs = ArrayList<TextRun>()
        for (style in outStyles) {
            val last = runs.lastOrNull()
            if (last != null && last.style == style && style.objectId == null) runs[runs.lastIndex] = TextRun(last.length + 1, style) else runs += TextRun(1, style)
        }
        return Prepared(RichText(outText.toString(), runs, body.paragraphs), spans)
    }

    /** Where a new picture goes: below [top], at the left text margin, never wider than the text column. */
    internal fun placement(source: SdocxImageSource, top: Double, noteWidth: Int): RectF64 {
        val column = noteWidth - 2 * LEFT
        val fit = if (source.width > column) column / source.width else 1.0
        return RectF64(LEFT, top, LEFT + source.width * fit, top + source.height * fit)
    }

    /** Left edge and first top of a picture in the text, as the reference app placed one (1080 wide note). */
    internal const val LEFT = 48.0
    internal const val FIRST_TOP = 30.0
    internal const val GAP = 20.0
}
