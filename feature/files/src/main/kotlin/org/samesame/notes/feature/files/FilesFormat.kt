package org.samesame.notes.feature.files

import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.LibraryFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure text helpers of the Files screen (JVM-tested). */
internal object FilesFormat {
    private const val KB = 1024.0

    /** "980 B", "12 KB", "2.4 MB", "1.3 GB": one decimal below 10, none from 100. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / KB
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) { value /= KB; unit++ }
        val text = if (value >= 100 || (unit == 0 && value >= 10)) String.format(locale, "%.0f", value) else String.format(locale, "%.1f", value)
        return "$text ${units[unit]}"
    }

    /** "8 Nov" in the current year, "8 Nov 2025" otherwise. */
    fun date(millis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val pattern = if (day.year == today.year) "d MMM" else "d MMM yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(day)
    }

    /** "2.4 MB · 8 Nov" (the grid and list second line). */
    fun sizeAndDate(file: LibraryFile, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        "${size(file.sizeBytes, locale)} · ${date(file.createdAt, today, zone, locale)}"

    /** Extension badge text: upper-case, at most four characters; "" when there is no extension. */
    fun badge(file: LibraryFile): String = file.ext.uppercase().take(4)

    /** "Folder / Sub folder" for a folder id; "" for the root. */
    fun path(folderId: FileFolderId?, folders: List<FileFolder>, separator: String = " / "): String {
        if (folderId == null) return ""
        val byId = folders.associateBy { it.id }
        val names = ArrayList<String>()
        var cursor = byId[folderId]
        var guard = 0
        while (cursor != null && guard++ < 100) {
            names += cursor.name
            cursor = cursor.parentId?.let(byId::get)
        }
        return names.asReversed().joinToString(separator)
    }

    /** Sort key used where the repository's order isn't available (search results). */
    fun isImageLike(category: FileCategory) = category == FileCategory.Image
}
