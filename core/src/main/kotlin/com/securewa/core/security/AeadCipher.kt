package com.securewa.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption.
 *
 * GCM is used rather than CBC because it is authenticated: a modified
 * ciphertext, a truncated ciphertext or a ciphertext opened with the wrong
 * associated data all fail with [javax.crypto.AEADBadTagException] instead of
 * producing plausible garbage. That property is what makes tamper detection a
 * fact rather than a convention.
 *
 * The associated data binds a ciphertext to the record it belongs to, so a
 * credential sealed for one slot cannot be moved to another even by someone who
 * has the master key.
 */
object AeadCipher {

    const val ALGORITHM = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32

    private val secureRandom = SecureRandom()

    /** One sealed value. Both parts are needed to decrypt; neither is a secret on its own. */
    data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

    fun seal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): Sealed {
        require(key.size == KEY_BYTES) { "key must be 32 bytes" }
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(associatedData)
        }
        return Sealed(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    /**
     * @throws javax.crypto.AEADBadTagException when the key, the associated data
     *         or the ciphertext does not match what was sealed.
     */
    fun open(key: ByteArray, sealed: Sealed, associatedData: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_BYTES) { "key must be 32 bytes" }
        require(sealed.nonce.size == NONCE_BYTES) { "nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.nonce))
            updateAAD(associatedData)
        }
        return cipher.doFinal(sealed.ciphertext)
    }
}
