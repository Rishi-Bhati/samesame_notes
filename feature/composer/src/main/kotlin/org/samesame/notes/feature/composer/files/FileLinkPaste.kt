package org.samesame.notes.feature.composer.files

import org.samesame.notes.core.model.FilePasteMode
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.FileNames
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun

/**
 * Pasting and inserting library files into the body (docs/architecture/files-feature.md 2.3 and 2.4). Pure, unit-tested;
 * `FileLinkController` does the I/O and the UI.
 */
object FileLinkPaste {
    /** A pasted or picked file as the library knows it now. */
    sealed interface Item {
        val id: FileId
        val label: String
        val uri: String

        /** A file in the library (not in its Recycle bin). */
        data class Live(val file: LibraryFile) : Item {
            override val id get() = file.id
            override val label get() = file.displayName
            override val uri get() = FileLinks.format(file)
        }

        /** A file in the Files Recycle bin: pasted as a link (tapping it offers Restore). */
        data class Trashed(val file: LibraryFile) : Item {
            override val id get() = file.id
            override val label get() = file.displayName
            override val uri get() = FileLinks.format(file)
        }

        /** An id the library doesn't know (deleted, or a link from another device): a link with the name hint. */
        data class Missing(val target: FileLinks.Target) : Item {
            override val id get() = target.id
            override val label get() = target.nameHint?.takeIf { it.isNotBlank() } ?: target.id.value
            override val uri get() = FileLinks.format(target.id, target.nameHint.orEmpty())
        }
    }

    /** The items for [targets] (in their order, the same file once), given what the library returned. */
    fun items(targets: List<FileLinks.Target>, known: Map<FileId, LibraryFile>): List<Item> =
        targets.distinctBy { it.id }.take(Limits.MAX_FILES_PER_PASTE).map { t ->
            val f = known[t.id]
            when {
                f == null -> Item.Missing(t)
                f.isInTrash -> Item.Trashed(f)
                else -> Item.Live(f)
            }
        }

    /** A picture "Show in note" can copy into the note. */
    fun canShow(item: Item): Boolean =
        item is Item.Live && item.file.isRenderableImage && item.file.sizeBytes in 1..Limits.MAX_SHOW_IN_NOTE_BYTES

    /** What a paste does. */
    enum class Choice { Links, Ask, Show }

    /** Links when nothing is a picture; otherwise the setting decides ([FilePasteMode.Ask] asks at the caret). */
    fun choice(mode: FilePasteMode, items: List<Item>): Choice = when {
        items.none(::canShow) -> Choice.Links
        mode == FilePasteMode.AlwaysLink -> Choice.Links
        mode == FilePasteMode.AlwaysShowInNote -> Choice.Show
        else -> Choice.Ask
    }

    /** The chooser's second row: "Show in note" for one picture, "Show images" for several. */
    fun showsSeveral(items: List<Item>): Boolean = items.count(::canShow) > 1

    /** One piece of the pasted content, in order. */
    sealed interface Part {
        data class Link(val label: String, val uri: String) : Part
        data class Picture(val file: LibraryFile) : Part
    }

    /** The parts to insert: pictures shown in the note when [show] (and they can be), every other file as a link. */
    fun parts(items: List<Item>, show: Boolean): List<Part> =
        items.map { if (show && canShow(it)) Part.Picture((it as Item.Live).file) else Part.Link(it.label, it.uri) }

    /** Between two pasted files. */
    const val SEPARATOR = " "

    /**
     * A link run labelled [label] with the caret's [style] (its own link dropped) and the link [uri]. Typing after it
     * doesn't extend the link (the sticky rule), so the separator after a link run is plain.
     */
    fun linkRun(label: String, uri: String, style: CharStyle): RichText {
        val text = label.ifEmpty { uri }
        return RichText(text, listOf(TextRun(text.length, style.copy(link = uri, objectId = null))))
    }

    /** Plain [text] in [style] (no link, no object). */
    fun plain(text: String, style: CharStyle): RichText =
        RichText(text, listOf(TextRun(text.length, style.copy(link = null, objectId = null))))

    /** Media extension of a picture copied into the bundle. */
    fun mediaExtension(file: LibraryFile): String {
        val ext = file.ext.ifEmpty { FileNames.extensionOf(file.displayName) }.lowercase()
        return when {
            ext == "jpeg" -> "jpg"
            ext.isNotEmpty() -> ext
            else -> file.mimeType.substringAfter('/').let { if (it == "jpeg") "jpg" else it }
        }
    }

    /** The picture's size in the text: its own aspect ratio, [columnWidthPu] wide at most (the text scales it anyway). */
    fun inlineSize(pixelWidth: Int, pixelHeight: Int, columnWidthPu: Float): Pair<Float, Float> {
        if (pixelWidth <= 0 || pixelHeight <= 0) return columnWidthPu to columnWidthPu
        val w = minOf(pixelWidth.toFloat(), columnWidthPu)
        return w to w * pixelHeight / pixelWidth
    }

    /** Names that look like a picture, for the link menu's "Show in note" (the row resolves the file before acting). */
    fun looksLikePicture(name: String?): Boolean =
        name != null && FileNames.extensionOf(name).lowercase() in PICTURE_EXTENSIONS

    /** "Update label" is offered when the label is still the name the link was made with. */
    fun labelFollowsName(label: String, target: FileLinks.Target): Boolean = label == target.nameHint

    private val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heif", "heic")
}
