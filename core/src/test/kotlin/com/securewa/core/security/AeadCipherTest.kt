package com.securewa.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The stored form of a sealed value: a credential slot column holds one byte
 * array, and what it holds must survive the round trip and nothing else.
 */
class AeadCipherTest {

    private val key = ByteArray(AeadCipher.KEY_BYTES) { it.toByte() }

    @Test
    fun `a sealed value survives being stored and read back`() {
        val sealed = AeadCipher.seal(key, "sk-a-key".toByteArray())
        val stored = AeadCipher.encode(sealed)

        assertEquals(sealed.nonce.size + sealed.ciphertext.size, stored.size)
        val reopened = AeadCipher.open(key, AeadCipher.decode(stored))
        assertArrayEquals("sk-a-key".toByteArray(), reopened)
    }

    @Test
    fun `the stored form starts with the nonce`() {
        val sealed = AeadCipher.seal(key, "value".toByteArray())

        assertArrayEquals(sealed.nonce, AeadCipher.encode(sealed).copyOfRange(0, AeadCipher.NONCE_BYTES))
    }

    @Test
    fun `a value sealed for one slot still cannot be opened as another`() {
        val sealed = CredentialSealer.sealCredential(key, "slot-a", "secret".toByteArray())
        val stored = AeadCipher.encode(sealed)

        val decoded = AeadCipher.decode(stored)
        val opened = CredentialSealer.openCredential(key, "slot-a", decoded)
        assertEquals("secret", String(opened))

        val wrongSlot = runCatching { CredentialSealer.openCredential(key, "slot-b", decoded) }
        assertFalse("a credential must stay bound to its slot", wrongSlot.isSuccess)
    }

    @Test
    fun `a truncated stored value is rejected as malformed rather than as a wrong key`() {
        val stored = ByteArray(AeadCipher.NONCE_BYTES)

        val result = runCatching { AeadCipher.decode(stored) }
        assertFalse(result.isSuccess)
        assertEquals(IllegalArgumentException::class.java, result.exceptionOrNull()!!.javaClass)
    }
}
