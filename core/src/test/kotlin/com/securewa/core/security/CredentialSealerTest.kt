package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class CredentialSealerTest {

    private val masterKey = ByteArray(32) { it.toByte() }
    private val otherMasterKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `a credential round trips for its own slot`() {
        val plaintext = "sk-test-value".toByteArray()
        val sealed = CredentialSealer.sealCredential(masterKey, "slot-a", plaintext)
        assertTrue(
            plaintext.contentEquals(CredentialSealer.openCredential(masterKey, "slot-a", sealed))
        )
    }

    @Test
    fun `a credential cannot be opened under a different slot`() {
        // This is what makes sharing a credential slot an explicit decision:
        // copying the ciphertext to another slot does not work.
        val sealed = CredentialSealer.sealCredential(masterKey, "slot-a", "value".toByteArray())
        assertThrows<AEADBadTagException> {
            CredentialSealer.openCredential(masterKey, "slot-b", sealed)
        }
    }

    @Test
    fun `a credential cannot be opened with a different master key`() {
        val sealed = CredentialSealer.sealCredential(masterKey, "slot-a", "value".toByteArray())
        assertThrows<AEADBadTagException> {
            CredentialSealer.openCredential(otherMasterKey, "slot-a", sealed)
        }
    }

    @Test
    fun `sealing the same value twice produces different ciphertext`() {
        val first = CredentialSealer.sealCredential(masterKey, "slot-a", "value".toByteArray())
        val second = CredentialSealer.sealCredential(masterKey, "slot-a", "value".toByteArray())
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `the master key is wrapped and unwrapped with the passphrase key`() {
        val keyEncryptionKey = ByteArray(32) { (it * 3).toByte() }
        val wrapped = CredentialSealer.wrapMasterKey(keyEncryptionKey, masterKey)
        assertTrue(masterKey.contentEquals(CredentialSealer.unwrapMasterKey(keyEncryptionKey, wrapped)))
    }

    @Test
    fun `a wrong passphrase key fails as authentication, not as corruption`() {
        val wrapped = CredentialSealer.wrapMasterKey(ByteArray(32) { 1 }, masterKey)
        assertThrows<AEADBadTagException> {
            CredentialSealer.unwrapMasterKey(ByteArray(32) { 2 }, wrapped)
        }
    }

    @Test
    fun `cipher parameters are the ones claimed`() {
        assertEquals("AES/GCM/NoPadding", AeadCipher.ALGORITHM)
        assertEquals(128, AeadCipher.TAG_BITS)
        assertEquals(12, AeadCipher.NONCE_BYTES)
        assertEquals(32, AeadCipher.KEY_BYTES)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (error: Throwable) {
            thrown = error is T
        }
        assertTrue("expected ${T::class.java.simpleName} but got ${thrown::class.java.simpleName}", thrown)
    }
}
