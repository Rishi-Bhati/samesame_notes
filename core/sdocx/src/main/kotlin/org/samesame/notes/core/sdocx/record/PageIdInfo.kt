package org.samesame.notes.core.sdocx.record

/** `pageIdInfo.dat` (core spec §4): the document hash plus page order and page hashes. */
class PageIdInfo(
    val docHash: ByteArray,
    val pages: List<Entry>,
) {
    class Entry(val pageId: String, val pageHash: ByteArray)
}
