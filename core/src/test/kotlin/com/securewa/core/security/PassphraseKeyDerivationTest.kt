package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class PassphraseKeyDerivationTest {

    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `derivation is deterministic for the same inputs`() {
        val salt = ByteArray(16) { it.toByte() }
        val first = PassphraseKeyDerivation.derive(passphrase, salt, iterations = 100_000)
        val second = PassphraseKeyDerivation.derive(passphrase, salt, iterations = 100_000)
        assertEquals(32, first.size)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `a different salt produces a different key`() {
        val a = PassphraseKeyDerivation.derive(passphrase, ByteArray(16) { 1 }, iterations = 100_000)
        val b = PassphraseKeyDerivation.derive(passphrase, ByteArray(16) { 2 }, iterations = 100_000)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a different passphrase produces a different key`() {
        val salt = ByteArray(16) { 7 }
        val a = PassphraseKeyDerivation.derive("one passphrase".toCharArray(), salt, iterations = 100_000)
        val b = PassphraseKeyDerivation.derive("another passphrase".toCharArray(), salt, iterations = 100_000)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `more iterations produce a different key`() {
        val salt = ByteArray(16) { 3 }
        val a = PassphraseKeyDerivation.derive(passphrase, salt, iterations = 100_000)
        val b = PassphraseKeyDerivation.derive(passphrase, salt, iterations = 100_001)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `generated salts are random and the right size`() {
        val salts = (1..8).map { PassphraseKeyDerivation.generateSalt() }
        assertEquals(8, salts.map { it.joinToString() }.distinct().size)
        salts.forEach { assertEquals(16, it.size) }
    }

    @Test
    fun `weak parameters are rejected`() {
        val salt = ByteArray(16) { 1 }
        assertThrows<IllegalArgumentException> {
            PassphraseKeyDerivation.derive(passphrase, salt, iterations = 1_000)
        }
        assertThrows<IllegalArgumentException> {
            PassphraseKeyDerivation.derive(passphrase, ByteArray(0))
        }
        assertThrows<IllegalArgumentException> {
            PassphraseKeyDerivation.derive(passphrase, salt, iterations = 100_000, keyBits = 64)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (error: Throwable) {
            thrown = error is T
        }
        assertTrue("expected ${T::class.java.simpleName}", thrown)
    }

    @Test
    fun `the default iteration count is at least the current OWASP minimum`() {
        assertTrue(
            PassphraseKeyDerivation.DEFAULT_ITERATIONS >= 210_000
        )
    }

    @Test
    fun `a derived key cannot be mistaken for the passphrase`() {
        val key = PassphraseKeyDerivation.derive(passphrase, ByteArray(16) { 5 }, iterations = 100_000)
        val asText = String(key, Charsets.UTF_8)
        assertFalse(asText.contains("correct horse"))
    }

    @Test
    fun `aes-gcm round trips and rejects tampering`() {
        val key = PassphraseKeyDerivation.derive(passphrase, ByteArray(16) { 9 }, iterations = 100_000)
        val sealed = AeadCipher.seal(key, "secret".toByteArray(), "aad".toByteArray())
        assertEquals(
            "secret",
            String(AeadCipher.open(key, sealed, "aad".toByteArray()), Charsets.UTF_8)
        )
        val tampered = sealed.copy(ciphertext = sealed.ciphertext.clone().also { it[0] = (it[0] + 1).toByte() })
        assertThrows<AEADBadTagException> { AeadCipher.open(key, tampered, "aad".toByteArray()) }
    }
}
