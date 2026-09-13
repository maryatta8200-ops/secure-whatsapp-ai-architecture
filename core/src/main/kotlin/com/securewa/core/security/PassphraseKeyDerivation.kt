package com.securewa.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Derives a key-encryption key from the application passphrase.
 *
 * PBKDF2-HMAC-SHA256 with a per-installation random salt. The salt and the
 * iteration count are stored next to the wrapped key, because they are not
 * secrets: they only have to be unique and recorded.
 *
 * The passphrase itself is never stored, never logged and never written to
 * disk. This class only ever sees it as a `CharArray` so the caller can clear
 * it as soon as the key is derived.
 *
 * The iteration count is a parameter rather than a constant so it can be raised
 * without a migration of the derivation itself: old blobs keep their recorded
 * count, new ones use the current default.
 */
object PassphraseKeyDerivation {

    /** OWASP-recommended minimum for PBKDF2-HMAC-SHA256 at the time of writing. */
    const val DEFAULT_ITERATIONS = 210_000

    /** 16 bytes, the size recommended for a PBKDF2 salt. */
    const val SALT_BYTES = 16

    /** AES-256 key material for the wrapping cipher. */
    const val KEY_BITS = 256

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * Derives key material of [keyBits] bits.
     *
     * @param passphrase cleared by the caller afterwards; not modified here.
     */
    fun derive(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        keyBits: Int = KEY_BITS
    ): ByteArray {
        require(salt.isNotEmpty()) { "salt must not be empty" }
        require(iterations >= 100_000) { "iterations must be at least 100000" }
        require(keyBits == 128 || keyBits == 256) { "keyBits must be 128 or 256" }

        val spec = PBEKeySpec(passphrase, salt, iterations, keyBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
