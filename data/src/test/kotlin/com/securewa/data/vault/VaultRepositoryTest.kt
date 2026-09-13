package com.securewa.data.vault

import com.securewa.core.security.AeadCipher
import com.securewa.core.security.PassphraseKeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * Vault behaviour, tested without a device: the platform key is an injected
 * [PlatformSealer] and the storage is in memory, so everything except the
 * Android Keystore binding itself is exercised.
 */
class VaultRepositoryTest {

    private val passphrase = "a sufficiently long passphrase".toCharArray()
    private val otherPassphrase = "an entirely different passphrase".toCharArray()

    // 100_000 iterations is the minimum this project accepts. It keeps the
    // suite fast without weakening what is under test, which is the vault
    // lifecycle rather than the cost of the derivation.
    private val testIterations = 100_000

    private class InMemoryVaultStorage : VaultStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes
        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }
        override fun clear() {
            bytes = null
        }
    }

    /** Stands in for the Android Keystore: same seal/open shape, no device. */
    private class FakePlatformSealer(
        private val key: ByteArray = ByteArray(32) { 7 },
        private val failOpen: Boolean = false
    ) : PlatformSealer {
        override fun seal(plaintext: ByteArray): ByteArray {
            val sealed = AeadCipher.seal(key, plaintext)
            return sealed.nonce + sealed.ciphertext
        }

        override fun open(sealed: ByteArray): ByteArray {
            if (failOpen) throw GeneralSecurityException("device key unavailable")
            return AeadCipher.open(
                key,
                AeadCipher.Sealed(
                    nonce = sealed.copyOfRange(0, 12),
                    ciphertext = sealed.copyOfRange(12, sealed.size)
                )
            )
        }
    }

    private fun repository(
        storage: VaultStorage = InMemoryVaultStorage(),
        sealer: PlatformSealer = FakePlatformSealer()
    ) = VaultRepository(storage, sealer, testIterations)

    @Test
    fun `a created vault keeps the master key in memory`() {
        val vault = repository()
        assertTrue(vault.create(passphrase))
        assertTrue(vault.isUnlocked)
        assertTrue(vault.hasVault())
    }

    @Test
    fun `a vault reopens with the same passphrase and rejects a different one`() {
        val storage = InMemoryVaultStorage()
        repository(storage).create(passphrase)

        val reopened = repository(storage)
        assertFalse("a fresh repository starts locked", reopened.isUnlocked)
        assertEquals(UnlockResult.Success, reopened.unlock(passphrase))
        assertTrue(reopened.isUnlocked)

        reopened.lock()
        assertEquals(UnlockResult.WrongPassphrase, reopened.unlock(otherPassphrase))
        assertFalse(reopened.isUnlocked)
    }

    @Test
    fun `unlocking without a vault reports that no vault exists`() {
        assertEquals(UnlockResult.NoVault, repository().unlock(passphrase))
    }

    @Test
    fun `an existing vault is never overwritten silently`() {
        val storage = InMemoryVaultStorage()
        assertTrue(repository(storage).create(passphrase))
        assertFalse("creating twice would destroy every stored credential", repository(storage).create(passphrase))
    }

    @Test
    fun `a device that cannot open the envelope reports a platform failure`() {
        val storage = InMemoryVaultStorage()
        repository(storage).create(passphrase)
        val result = repository(storage, FakePlatformSealer(failOpen = true)).unlock(passphrase)
        assertTrue(
            "a missing device key must not be reported as a wrong passphrase: $result",
            result is UnlockResult.PlatformFailure
        )
    }

    @Test
    fun `a damaged envelope is reported as damage, not as a missing vault`() {
        val storage = InMemoryVaultStorage()
        repository(storage).create(passphrase)
        storage.write(FakePlatformSealer().seal("this is not an envelope".toByteArray()))

        val result = repository(storage).unlock(passphrase)
        assertTrue(
            "corruption and absence are different problems: $result",
            result is UnlockResult.CorruptEnvelope
        )
    }

    @Test
    fun `sealing credential material requires an unlocked vault`() {
        val vault = repository()
        var refused = false
        try {
            vault.sealCredential("slot-a", "value".toByteArray())
        } catch (locked: IllegalStateException) {
            refused = true
        }
        assertTrue("a locked vault has no key to encrypt with", refused)
    }

    @Test
    fun `a credential is bound to the slot it was sealed for`() {
        val vault = repository()
        vault.create(passphrase)
        val sealed = vault.sealCredential("slot-a", "provider key".toByteArray())

        assertEquals(
            "provider key",
            String(vault.openCredential("slot-a", sealed), Charsets.UTF_8)
        )
        var crossedSlots = false
        try {
            vault.openCredential("slot-b", sealed)
        } catch (rejected: AEADBadTagException) {
            crossedSlots = true
        }
        assertTrue("moving a credential between slots must fail", crossedSlots)
    }

    @Test
    fun `credentials stay readable across a restart of the repository`() {
        val storage = InMemoryVaultStorage()
        val first = repository(storage)
        first.create(passphrase)
        val sealed = first.sealCredential("slot-a", "provider key".toByteArray())
        first.lock()

        val second = repository(storage)
        assertEquals(UnlockResult.Success, second.unlock(passphrase))
        assertEquals(
            "provider key",
            String(second.openCredential("slot-a", sealed), Charsets.UTF_8)
        )
    }

    @Test
    fun `changing the passphrase keeps credentials readable`() {
        val storage = InMemoryVaultStorage()
        val vault = repository(storage)
        vault.create(passphrase)
        val sealed = vault.sealCredential("slot-a", "provider key".toByteArray())

        assertEquals(UnlockResult.Success, vault.changePassphrase(passphrase, otherPassphrase))
        assertEquals(
            "rotating the passphrase must not invalidate stored credentials",
            "provider key",
            String(vault.openCredential("slot-a", sealed), Charsets.UTF_8)
        )

        val reopened = repository(storage)
        assertEquals(UnlockResult.WrongPassphrase, reopened.unlock(passphrase))
        assertEquals(UnlockResult.Success, reopened.unlock(otherPassphrase))
    }

    @Test
    fun `changing the passphrase with the wrong current passphrase is refused`() {
        val storage = InMemoryVaultStorage()
        repository(storage).create(passphrase)
        assertEquals(
            UnlockResult.WrongPassphrase,
            repository(storage).changePassphrase(otherPassphrase, passphrase)
        )
    }

    @Test
    fun `locking discards the master key`() {
        val vault = repository()
        vault.create(passphrase)
        vault.lock()
        assertNull(vault.peekMasterKey())
        assertFalse(vault.isUnlocked)
    }

    @Test
    fun `the stored envelope does not contain the master key`() {
        val storage = InMemoryVaultStorage()
        val vault = repository(storage)
        vault.create(passphrase)
        val masterKey = vault.peekMasterKey()!!

        val stored = String(FakePlatformSealer().open(storage.bytes!!), Charsets.UTF_8)
        assertFalse(
            "the master key must never be persisted",
            stored.contains(masterKey.joinToString(",") { it.toString() })
        )
        assertFalse(stored.contains(String(masterKey, Charsets.ISO_8859_1)))
    }

    @Test
    fun `the envelope records the derivation parameters it will need later`() {
        val storage = InMemoryVaultStorage()
        repository(storage).create(passphrase)
        val stored = String(FakePlatformSealer().open(storage.bytes!!), Charsets.UTF_8)
        assertTrue(stored.startsWith("v1\n"))
        assertTrue(stored.contains(testIterations.toString()))
        assertTrue(
            PassphraseKeyDerivation.DEFAULT_ITERATIONS >= 210_000
        )
    }
}
