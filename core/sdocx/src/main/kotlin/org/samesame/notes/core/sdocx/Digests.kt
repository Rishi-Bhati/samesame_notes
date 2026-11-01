package org.samesame.notes.core.sdocx

import java.security.MessageDigest

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

internal fun concat(parts: List<ByteArray>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (p in parts) out.write(p)
    return out.toByteArray()
}

/** Object change token: `SHA-256(UTF8(uuid + decimal(modifiedTime)))` (core spec §7). */
internal fun objectHash(uuid: String, modifiedTime: Long): ByteArray =
    sha256("$uuid$modifiedTime")
