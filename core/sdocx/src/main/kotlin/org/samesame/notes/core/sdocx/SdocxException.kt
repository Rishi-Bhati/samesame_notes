package org.samesame.notes.core.sdocx

/** Base type for all `.sdocx` read/write failures. */
open class SdocxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A legacy note we cannot read: an old `.sdoc` (device-bound key, §9.2), or a WDoc-looking file
 * whose `formatVersion` is below 2034 (§1.1). Detected and reported, never attempted.
 */
class SdocxLegacyFormatException(val detectedFormatVersion: Int?, message: String) :
    SdocxException(message)

/** A structurally invalid or unsupported `.sdocx` (bad magic, version above 4000, truncated). */
class SdocxFormatException(message: String, cause: Throwable? = null) : SdocxException(message, cause)
