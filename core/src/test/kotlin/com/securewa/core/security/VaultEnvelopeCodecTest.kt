package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultEnvelopeCodecTest {

    private val envelope = Vault.create("passphrase for codec".toCharArray()).envelope

    @Test
    fun `an envelope survives a codec round trip`() {
        val decoded = VaultEnvelopeCodec.decode(VaultEnvelopeCodec.encode(envelope))
        assertEquals(envelope, decoded)
        assertTrue(decoded.salt.contentEquals(envelope.salt))
        assertTrue(decoded.wrappedMasterKey.ciphertext.contentEquals(envelope.wrappedMasterKey.ciphertext))
    }

    @Test
    fun `a decoded envelope still opens the vault`() {
        val passphrase = "passphrase for codec".toCharArray()
        val decoded = VaultEnvelopeCodec.decode(VaultEnvelopeCodec.encode(envelope))
        assertTrue(Vault.open(passphrase, envelope).contentEquals(Vault.open(passphrase, decoded)))
    }

    @Test
    fun `the stored form is line oriented and readable`() {
        val lines = VaultEnvelopeCodec.encode(envelope).trim().split('\n')
        assertEquals(6, lines.size)
        assertEquals("v1", lines[0])
        assertEquals(envelope.iterations.toString(), lines[1])
    }

    @Test
    fun `a truncated record is rejected, not treated as no vault`() {
        assertRejected(VaultEnvelopeCodec.encode(envelope).lines().take(3).joinToString("\n"))
    }

    @Test
    fun `an unknown version is rejected`() {
        assertRejected("v99\n210000\nchecksum\naabb\n00112233445566778899aabb\naabbcc")
    }

    @Test
    fun `non hex content is rejected`() {
        assertRejected("v1\n210000\nchecksum\nnot-hex\n0011\nccdd")
    }

    @Test
    fun `an unparsable iteration count is rejected`() {
        assertRejected("v1\nmany\nchecksum\naabb\n0011\nccdd")
    }

    @Test
    fun `hex round trips binary content`() {
        val bytes = byteArrayOf(0, 1, 15, 16, 127, -1, -128)
        assertTrue(bytes.contentEquals(Hex.decode(Hex.encode(bytes))))
        assertEquals("00010f107f80ff", Hex.encode(byteArrayOf(0, 1, 15, 16, 127, -128, -1)))
    }

    private fun assertRejected(text: String) {
        try {
            VaultEnvelopeCodec.decode(text)
            fail("expected the malformed envelope to be rejected: $text")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message?.isNotBlank() == true)
        }
    }
}
