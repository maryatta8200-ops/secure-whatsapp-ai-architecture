package com.securewa.data.vault

import com.securewa.core.security.AeadCipher
import com.securewa.core.security.CredentialSealer
import com.securewa.core.security.PassphraseKeyDerivation
import com.securewa.core.security.Vault
import com.securewa.core.security.VaultEnvelope
import com.securewa.core.security.VaultEnvelopeCodec
import java.security.GeneralSecurityException

/**
 * Outcome of an unlock attempt.
 *
 * The states are distinct on purpose. "Wrong passphrase", "no vault exists",
 * "this device cannot open the envelope" and "the envelope is damaged" are
 * different problems with different remedies, and collapsing them into a single
 * "failed" would leave the user unable to act on any of them.
 */
sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassphrase : UnlockResult
    data object NoVault : UnlockResult
    data class PlatformFailure(val reason: String) : UnlockResult
    data class CorruptEnvelope(val reason: String) : UnlockResult
}

/**
 * Owns the master key.
 *
 * The master key exists in memory only while the vault is unlocked; it is never
 * written to disk, never logged and never returned to a caller. Everything that
 * needs to encrypt or decrypt credential material goes through
 * [sealCredential] and [openCredential], which take and produce ciphertext.
 *
 * The passphrase is accepted as a `CharArray` so a caller can clear it; this
 * class never copies it into a String.
 */
class VaultRepository(
    private val storage: VaultStorage,
    private val platformSealer: PlatformSealer,
    private val iterations: Int = PassphraseKeyDerivation.DEFAULT_ITERATIONS
) {

    @Volatile
    private var masterKey: ByteArray? = null

    val isUnlocked: Boolean
        get() = masterKey != null

    fun hasVault(): Boolean = storage.read() != null

    /** Creates the vault. Returns `false` when one already exists, so an existing configuration is never overwritten silently. */
    @Synchronized
    fun create(passphrase: CharArray): Boolean {
        if (hasVault()) return false
        val created = Vault.create(passphrase, iterations = iterations)
        persist(created.envelope)
        masterKey = created.masterKey
        return true
    }

    @Synchronized
    fun unlock(passphrase: CharArray): UnlockResult {
        val sealedBytes = storage.read() ?: return UnlockResult.NoVault
        val envelope = try {
            readEnvelope(sealedBytes)
        } catch (failure: GeneralSecurityException) {
            return UnlockResult.PlatformFailure(failure.message ?: "the platform key could not open the vault")
        } catch (failure: IllegalArgumentException) {
            return UnlockResult.CorruptEnvelope(failure.message ?: "the vault envelope is damaged")
        }

        return try {
            masterKey = Vault.open(passphrase, envelope)
            UnlockResult.Success
        } catch (failure: GeneralSecurityException) {
            // A wrong passphrase is an authentication failure, not corruption.
            lock()
            UnlockResult.WrongPassphrase
        }
    }

    /** Discards the master key. Credentials stay sealed on disk. */
    @Synchronized
    fun lock() {
        masterKey?.fill(0)
        masterKey = null
    }

    @Synchronized
    fun changePassphrase(current: CharArray, new: CharArray): UnlockResult {
        val sealedBytes = storage.read() ?: return UnlockResult.NoVault
        val envelope = try {
            readEnvelope(sealedBytes)
        } catch (failure: GeneralSecurityException) {
            return UnlockResult.PlatformFailure(failure.message ?: "the platform key could not open the vault")
        } catch (failure: IllegalArgumentException) {
            return UnlockResult.CorruptEnvelope(failure.message ?: "the vault envelope is damaged")
        }

        val rotated = try {
            Vault.changePassphrase(current, new, envelope, iterations)
        } catch (failure: GeneralSecurityException) {
            return UnlockResult.WrongPassphrase
        }
        persist(rotated)
        masterKey = Vault.open(new, rotated)
        return UnlockResult.Success
    }

    /**
     * Seals credential material for one slot.
     *
     * @throws IllegalStateException when the vault is locked. Refusing is the
     *         only safe behaviour: there is no key to encrypt with.
     */
    @Synchronized
    fun sealCredential(slotId: String, value: ByteArray): AeadCipher.Sealed {
        val key = requireKey()
        return CredentialSealer.sealCredential(key, slotId, value)
    }

    /**
     * Opens credential material sealed for [slotId].
     *
     * @throws javax.crypto.AEADBadTagException when the slot id does not match
     *         the one it was sealed for.
     */
    @Synchronized
    fun openCredential(slotId: String, sealed: AeadCipher.Sealed): ByteArray {
        val key = requireKey()
        return CredentialSealer.openCredential(key, slotId, sealed)
    }

    private fun requireKey(): ByteArray = masterKey
        ?: throw IllegalStateException("the vault is locked; unlock it before touching credentials")

    private fun readEnvelope(sealedBytes: ByteArray): VaultEnvelope =
        VaultEnvelopeCodec.decode(String(platformSealer.open(sealedBytes), Charsets.UTF_8))

    private fun persist(envelope: VaultEnvelope) {
        storage.write(platformSealer.seal(VaultEnvelopeCodec.encode(envelope).toByteArray(Charsets.UTF_8)))
    }

    internal fun peekMasterKey(): ByteArray? = masterKey
}
