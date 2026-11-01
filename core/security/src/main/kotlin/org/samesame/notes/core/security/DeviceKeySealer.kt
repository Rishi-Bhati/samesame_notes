package org.samesame.notes.core.security

/**
 * Seals data with a non-exportable key of this device (Android Keystore, StrongBox when present), so a copy of
 * the lock config taken off the device cannot be brute-forced offline: every guess needs this device's key.
 * The key needs no user authentication. Isolated so the vault can be tested on the JVM.
 */
interface DeviceKeySealer {
    /** Seals [data]; throws when the device key cannot be created or used. */
    fun seal(data: ByteArray): ByteArray

    /** Opens [sealed]; throws [TamperedDataException] (or another GeneralSecurityException) when it can't. */
    fun open(sealed: ByteArray): ByteArray
}
