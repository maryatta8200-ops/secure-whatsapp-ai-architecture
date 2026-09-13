package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class VaultTest {

    private val passphrase = "a long enough application passphrase".toCharArray()
    private val otherPassphrase = "a completely different passphrase".toCharArray()

    @Test
    fun `a created vault opens with the same passphrase`() {
        val vault = Vault.create(passphrase)
        val masterKey = Vault.open(passphrase, vault.envelope)
        assertTrue(vault.masterKey.contentEquals(masterKey))
    }

    @Test
    fun `a vault does not open with the wrong passphrase`() {
        val vault = Vault.create(passphrase)
        var failed = false
        try {
            Vault.open(otherPassphrase, vault.envelope)
        } catch (error: AEADBadTagException) {
            failed = true
        } catch (error: Throwable) {
            failed = error is javax.crypto.BadPaddingException
        }
        assertTrue("a wrong passphrase must be rejected", failed)
    }

    @Test
    fun `the checksum distinguishes a wrong passphrase from a corrupt envelope`() {
        val vault = Vault.create(passphrase)
        assertTrue(Vault.matchesChecksum(passphrase, vault.envelope))
        assertFalse(Vault.matchesChecksum(otherPassphrase, vault.envelope))
    }

    @Test
    fun `two vaults use different salts and different master keys`() {
        val first = Vault.create(passphrase)
        val second = Vault.create(passphrase)
        assertFalse(first.envelope.salt.contentEquals(second.envelope.salt))
        assertFalse(first.masterKey.contentEquals(second.masterKey))
    }

    @Test
    fun `changing the passphrase keeps every credential readable`() {
        val vault = Vault.create(passphrase)
        val sealed = CredentialSealer.sealCredential(vault.masterKey, "slot-a", "provider key".toByteArray())

        val rotated = Vault.changePassphrase(passphrase, otherPassphrase, vault.envelope)
        val reopened = Vault.open(otherPassphrase, rotated)

        assertTrue(
            "rotating the passphrase must not invalidate sealed credentials",
            vault.masterKey.contentEquals(reopened)
        )
        assertEquals(
            "provider key",
            String(CredentialSealer.openCredential(reopened, "slot-a", sealed), Charsets.UTF_8)
        )
        assertFalse(Vault.matchesChecksum(passphrase, rotated))
        assertTrue(Vault.matchesChecksum(otherPassphrase, rotated))
    }

    @Test
    fun `an envelope from an unknown version is rejected rather than guessed at`() {
        val vault = Vault.create(passphrase)
        val future = vault.envelope.copy(version = 99)
        var rejected = false
        try {
            Vault.open(passphrase, future)
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `the envelope records everything needed to re-derive the key`() {
        val vault = Vault.create(passphrase, iterations = 210_000)
        assertEquals(VaultEnvelope.CURRENT_VERSION, vault.envelope.version)
        assertEquals(210_000, vault.envelope.iterations)
        assertEquals(16, vault.envelope.salt.size)
        assertNotNull(vault.envelope.keyChecksum)
        assertEquals(12, vault.envelope.wrappedMasterKey.nonce.size)
    }

    @Test
    fun `the master key is never part of the envelope plaintext`() {
        val vault = Vault.create(passphrase)
        val envelopeText = vault.envelope.wrappedMasterKey.ciphertext.joinToString(",") { it.toString() }
        val keyText = vault.masterKey.joinToString(",") { it.toString() }
        assertFalse(
            "the wrapped master key must not contain the master key",
            envelopeText.contains(keyText)
        )
    }
}
