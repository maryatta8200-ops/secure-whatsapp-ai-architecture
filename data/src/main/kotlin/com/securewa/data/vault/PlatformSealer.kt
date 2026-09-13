package com.securewa.data.vault

/**
 * Seals bytes with a platform key that never leaves the device.
 *
 * This is the second layer of the vault. The first is the passphrase: the master
 * key is wrapped with a key derived from it. This layer wraps that envelope
 * again with a key held in the Android Keystore, so copying the vault file off
 * the device yields nothing - the key material is not extractable.
 *
 * The interface exists so the repository can be tested without a device: only
 * [AndroidKeystorePlatformSealer] touches the Keystore.
 */
interface PlatformSealer {
    fun seal(plaintext: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException when this device cannot open the blob. */
    fun open(sealed: ByteArray): ByteArray
}
