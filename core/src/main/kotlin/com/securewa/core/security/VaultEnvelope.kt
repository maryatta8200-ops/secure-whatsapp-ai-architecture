package com.securewa.core.security

/**
 * The on-disk shape of a vault: everything needed to re-derive the key that
 * unwraps the master key, plus the wrapped master key itself.
 *
 * Nothing in this record is secret on its own. The security comes from the
 * passphrase, which is never persisted, and from the platform key that seals
 * this envelope at rest.
 *
 * [version] exists so a future change to the derivation or the cipher can be
 * detected and migrated rather than guessed at.
 */
data class VaultEnvelope(
    val version: Int = CURRENT_VERSION,
    val salt: ByteArray,
    val iterations: Int,
    val keyChecksum: String,
    val wrappedMasterKey: AeadCipher.Sealed
) {
    companion object {
        const val CURRENT_VERSION = 1
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultEnvelope) return false
        return version == other.version &&
            salt.contentEquals(other.salt) &&
            iterations == other.iterations &&
            keyChecksum == other.keyChecksum &&
            wrappedMasterKey.nonce.contentEquals(other.wrappedMasterKey.nonce) &&
            wrappedMasterKey.ciphertext.contentEquals(other.wrappedMasterKey.ciphertext)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + keyChecksum.hashCode()
        result = 31 * result + wrappedMasterKey.nonce.contentHashCode()
        result = 31 * result + wrappedMasterKey.ciphertext.contentHashCode()
        return result
    }
}

/**
 * Result of creating or unlocking a vault.
 *
 * [masterKey] is 32 bytes of key material held in memory for the lifetime of an
 * unlocked session. It is never serialised by this module.
 */
data class UnlockedVault(
    val masterKey: ByteArray,
    val envelope: VaultEnvelope
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnlockedVault) return false
        return masterKey.contentEquals(other.masterKey) && envelope == other.envelope
    }

    override fun hashCode(): Int = 31 * masterKey.contentHashCode() + envelope.hashCode()
}

/**
 * Creates and opens vaults.
 *
 * This is the part of the security model that is pure and testable: given a
 * passphrase and stored parameters it either produces the master key or fails
 * with an authentication error. Where the envelope is stored, and the platform
 * key that seals it at rest, are supplied by the Android layer.
 */
object Vault {

    /**
     * Creates a new vault: generates random master key material and wraps it
     * with a key derived from [passphrase].
     *
     * [keyChecksum] is a digest of the derived key, not of the passphrase. It
     * lets the UI tell "wrong passphrase" apart from "corrupt envelope" without
     * storing anything that weakens the passphrase.
     */
    fun create(
        passphrase: CharArray,
        salt: ByteArray = PassphraseKeyDerivation.generateSalt(),
        iterations: Int = PassphraseKeyDerivation.DEFAULT_ITERATIONS
    ): UnlockedVault {
        val masterKey = ByteArray(AeadCipher.KEY_BYTES).also {
            java.security.SecureRandom().nextBytes(it)
        }
        val keyEncryptionKey = PassphraseKeyDerivation.derive(passphrase, salt, iterations)
        return try {
            UnlockedVault(
                masterKey = masterKey,
                envelope = VaultEnvelope(
                    version = VaultEnvelope.CURRENT_VERSION,
                    salt = salt,
                    iterations = iterations,
                    keyChecksum = Digest.sha256Hex(keyEncryptionKey.joinToString(",") { it.toString() }),
                    wrappedMasterKey = CredentialSealer.wrapMasterKey(keyEncryptionKey, masterKey)
                )
            )
        } finally {
            keyEncryptionKey.fill(0)
        }
    }

    /**
     * Opens an existing vault.
     *
     * @throws javax.crypto.AEADBadTagException when the passphrase does not match.
     */
    fun open(passphrase: CharArray, envelope: VaultEnvelope): ByteArray {
        require(envelope.version == VaultEnvelope.CURRENT_VERSION) {
            "unsupported vault version ${envelope.version}"
        }
        val keyEncryptionKey = PassphraseKeyDerivation.derive(passphrase, envelope.salt, envelope.iterations)
        return try {
            CredentialSealer.unwrapMasterKey(keyEncryptionKey, envelope.wrappedMasterKey)
        } finally {
            keyEncryptionKey.fill(0)
        }
    }

    /** True when [passphrase] derives the key recorded in [envelope]. */
    fun matchesChecksum(passphrase: CharArray, envelope: VaultEnvelope): Boolean {
        val keyEncryptionKey = PassphraseKeyDerivation.derive(passphrase, envelope.salt, envelope.iterations)
        return try {
            Digest.sha256Hex(keyEncryptionKey.joinToString(",") { it.toString() }) == envelope.keyChecksum
        } finally {
            keyEncryptionKey.fill(0)
        }
    }

    /**
     * Re-wraps the master key under a new passphrase. The master key, and
     * therefore every credential sealed with it, is unchanged: changing the
     * application passphrase does not require re-entering provider credentials.
     */
    fun changePassphrase(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        envelope: VaultEnvelope,
        newIterations: Int = PassphraseKeyDerivation.DEFAULT_ITERATIONS
    ): VaultEnvelope {
        val masterKey = open(currentPassphrase, envelope)
        return try {
            create(newPassphrase, PassphraseKeyDerivation.generateSalt(), newIterations).let { created ->
                created.envelope.copy(
                    wrappedMasterKey = CredentialSealer.wrapMasterKey(
                        PassphraseKeyDerivation.derive(newPassphrase, created.envelope.salt, newIterations),
                        masterKey
                    )
                )
            }
        } finally {
            masterKey.fill(0)
        }
    }
}
