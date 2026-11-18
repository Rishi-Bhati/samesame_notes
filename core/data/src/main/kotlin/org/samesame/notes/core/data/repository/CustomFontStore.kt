package org.samesame.notes.core.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.samesame.notes.core.model.CustomFontFile
import org.samesame.notes.core.model.FontChoice
import org.samesame.notes.core.model.FontKind
import org.samesame.notes.core.model.FontSettings
import org.samesame.notes.core.model.Limits
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

sealed interface FontImportResult {
    data object Imported : FontImportResult
    data class Rejected(val reason: CustomFontFile.Verdict) : FontImportResult
    data object Unreadable : FontImportResult
}

/** Serializes file and preference changes. Call on IO, with a scope that owns the whole transaction. */
internal class CustomFontStore(
    private val directory: File,
    private val read: suspend () -> FontSettings,
    private val update: suspend ((FontSettings) -> FontSettings) -> Unit,
    private val validate: (File) -> Boolean,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    private val mutex = Mutex()

    suspend fun import(input: InputStream, name: String): FontImportResult = mutex.withLock {
        var temp: File? = null
        val target = File(directory, "font-${UUID.randomUUID()}.ttf")
        var committed = false
        try {
            val before = read()
            directory.mkdirs()
            cleanup(before)
            val incoming = File.createTempFile("incoming-", ".tmp", directory)
            temp = incoming
            var size = 0L
            val header = ByteArray(4)
            var headerLength = 0
            incoming.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val take = minOf(4 - headerLength, count)
                    buffer.copyInto(header, headerLength, 0, take)
                    headerLength += take
                    size += count
                    if (size > Limits.MAX_CUSTOM_FONT_BYTES) break
                    output.write(buffer, 0, count)
                }
            }
            val verdict = CustomFontFile.check(size, header.copyOf(headerLength))
            if (verdict != CustomFontFile.Verdict.Ok) return@withLock FontImportResult.Rejected(verdict)
            if (!validate(incoming)) return@withLock FontImportResult.Rejected(CustomFontFile.Verdict.NotAFont)
            // The new name is published only after the complete file is in place; the old file stays valid on failure.
            if (!rename(incoming, target)) return@withLock FontImportResult.Unreadable
            update { it.copy(choice = FontChoice(FontKind.Custom), customFontName = name, customFontFileName = target.name) }
            committed = true
            cleanup(before.copy(customFontFileName = target.name))
            FontImportResult.Imported
        } catch (e: IOException) {
            FontImportResult.Unreadable
        } finally {
            temp?.delete()
            if (!committed) target.delete()
        }
    }

    suspend fun remove() = mutex.withLock {
        update {
            it.copy(
                choice = if (it.choice.kind == FontKind.Custom) FontChoice() else it.choice,
                customFontName = null,
                customFontFileName = null,
            )
        }
        cleanup(null)
    }

    /** Also recovers unreferenced files left by an interrupted process, keeping the selected legacy file. */
    private fun cleanup(settings: FontSettings?) {
        val keep = settings?.let { CustomFontFile.storageName(it.customFontFileName) }
        directory.listFiles()?.filter {
            it.name != keep && (it.name == CustomFontFile.LEGACY_NAME || it.name.startsWith("font-") || it.name.startsWith("incoming"))
        }?.forEach { it.delete() }
    }
}
