package org.samesame.notes.core.sdocx

/**
 * Lock state of a `.sdocx` note (spec §9.1). `.sdocx` locking is a flag, not encryption: the file
 * is plaintext and imports without any password. [ownerId] is the cloud-account GUID the file
 * expects, kept for round-trip.
 */
data class SdocxLock(
    val locked: Boolean,
    val ownerId: String,
) {
    companion object {
        val Unlocked = SdocxLock(false, "")
    }
}
