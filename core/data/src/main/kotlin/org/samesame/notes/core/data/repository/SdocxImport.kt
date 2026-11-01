package org.samesame.notes.core.data.repository

import org.samesame.notes.core.sdocx.SdocxFormatException
import org.samesame.notes.core.sdocx.SdocxLegacyFormatException
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.sdocx.SdocxArchive
import org.samesame.notes.core.sdocx.SdocxSideChannel
import org.samesame.notes.core.sdocx.map.SdocxMapper

/** Persist mapped media (page backgrounds, recordings) together with the exact source archive. */
internal suspend fun NoteDocumentStore.writeSdocxImport(
    bundle: SdocxMapper.ImportBundle,
    archive: SdocxArchive,
    originalBytes: ByteArray,
) {
    val id = bundle.document.id
    createFromDocument(bundle.document)
    val stored = LinkedHashMap<String, String>()
    for ((name, bytes) in bundle.media) stored[name] = writeMedia(id, bytes, extensionOf(name))
    writeDocument(SdocxMapper.withMediaPaths(bundle.document, stored))
    writeExtras(id, SdocxSideChannel.encode(archive, originalBytes))
    flush(id)
}

/** Why a `.sdocx` import could not be completed. The UI maps each to a user-visible message. */
enum class SdocxImportError {
    /** A legacy `.sdoc` / pre-2034 file: device-bound encryption, not readable (spec §9.2 / §11). */
    LEGACY_UNSUPPORTED,

    /** Not a `.sdocx`, truncated, corrupt, or a version newer than this app supports. */
    INVALID,

    /** Reading from the source (URI/stream) failed. */
    IO,
}

/** Thrown by [NoteRepository.importSdocx]; [error] is safe to show to the user. */
class SdocxImportException(val error: SdocxImportError, cause: Throwable? = null) :
    Exception(error.name, cause)

/** Classifies a reader failure into a user-facing [SdocxImportError]. */
internal fun sdocxImportErrorOf(cause: Throwable): SdocxImportError = when (cause) {
    is SdocxLegacyFormatException -> SdocxImportError.LEGACY_UNSUPPORTED
    is SdocxFormatException -> SdocxImportError.INVALID
    else -> SdocxImportError.INVALID
}

/** Extension of `media/<name>` (without the dot), defaulting to `bin`. */
internal fun extensionOf(mediaPath: String): String {
    val name = mediaPath.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot in 0 until name.length - 1) name.substring(dot + 1).lowercase() else "bin"
}
