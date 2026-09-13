package com.securewa.core.security

/**
 * Seals and opens credential material for one credential slot.
 *
 * Two properties matter here:
 *
 * 1. **Binding.** The slot id is used as associated data, so a credential
 *    sealed for one slot cannot be opened as another slot's credential, even
 *    with the correct master key. Sharing a slot between agents is therefore a
 *    decision the user makes, not something a copy can achieve.
 * 2. **No key material in the ciphertext.** This class takes the master key as
 *    an argument and never stores it. Where the master key lives is decided by
 *    the platform layer (Android Keystore plus the passphrase in milestone 3).
 */
object CredentialSealer {

    private const val CREDENTIAL_AAD_PREFIX = "credential-slot|"
    private const val MASTER_KEY_AAD = "securewa|master-key|v1"

    /** Associated data binding a sealed credential to its slot. */
    fun credentialAssociatedData(slotId: String): ByteArray =
        (CREDENTIAL_AAD_PREFIX + slotId).toByteArray(Charsets.UTF_8)

    fun sealCredential(masterKey: ByteArray, slotId: String, plaintext: ByteArray): AeadCipher.Sealed =
        AeadCipher.seal(masterKey, plaintext, credentialAssociatedData(slotId))

    fun openCredential(masterKey: ByteArray, slotId: String, sealed: AeadCipher.Sealed): ByteArray =
        AeadCipher.open(masterKey, sealed, credentialAssociatedData(slotId))

    /**
     * Wraps the master key with a key derived from the passphrase, so the master
     * key can be persisted without being readable by anyone who does not know
     * the passphrase.
     */
    fun wrapMasterKey(keyEncryptionKey: ByteArray, masterKey: ByteArray): AeadCipher.Sealed =
        AeadCipher.seal(keyEncryptionKey, masterKey, MASTER_KEY_AAD.toByteArray(Charsets.UTF_8))

    /**
     * @throws javax.crypto.AEADBadTagException when the passphrase was wrong. A
     *         wrong passphrase is a normal, expected outcome and must be
     *         reported as such, never treated as corruption.
     */
    fun unwrapMasterKey(keyEncryptionKey: ByteArray, sealed: AeadCipher.Sealed): ByteArray =
        AeadCipher.open(keyEncryptionKey, sealed, MASTER_KEY_AAD.toByteArray(Charsets.UTF_8))
}
