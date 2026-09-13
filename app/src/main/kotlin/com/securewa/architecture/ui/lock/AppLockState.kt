package com.securewa.architecture.ui.lock

import com.securewa.data.vault.UnlockResult
import com.securewa.data.vault.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the lock screen must show. */
sealed interface LockState {
    /** No vault exists yet: the user has to choose an application password. */
    data object NeedsPassword : LockState

    /** A vault exists and is locked. */
    data object Locked : LockState

    /** The vault is unlocked and credentials can be used. */
    data object Unlocked : LockState

    /** A message that must be shown to the user, verbatim and without guessing. */
    data class Message(val text: String, val after: LockState) : LockState
}

/** Minimum length for the application password. */
const val MIN_PASSWORD_LENGTH = 12

/**
 * Drives the application lock.
 *
 * The passphrase is handled as a `CharArray` end to end: it is never converted
 * to a String here, and it is cleared as soon as the vault has used it. No
 * failure is ever reported as success - a wrong password, an unusable device key
 * and a damaged envelope are shown as three different messages, because they
 * have three different remedies.
 */
class AppLockState(private val vaultRepository: VaultRepository) {

    private val state = MutableStateFlow<LockState>(initialState())

    val lockState: StateFlow<LockState> = state.asStateFlow()

    private fun initialState(): LockState =
        if (vaultRepository.hasVault()) LockState.Locked else LockState.NeedsPassword

    /** Creates the vault with [password], which must match [confirmation]. */
    fun createPassword(password: CharArray, confirmation: CharArray) {
        val problem = validateNewPassword(password, confirmation)
        if (problem != null) {
            state.value = LockState.Message(problem, LockState.NeedsPassword)
            return
        }
        if (!vaultRepository.create(password)) {
            state.value = LockState.Message(
                "A vault already exists on this device. Enter its password instead.",
                LockState.Locked
            )
            return
        }
        state.value = LockState.Unlocked
    }

    fun unlock(password: CharArray) {
        if (password.isEmpty()) {
            state.value = LockState.Message("Enter the application password.", LockState.Locked)
            return
        }
        state.value = when (val result = vaultRepository.unlock(password)) {
            UnlockResult.Success -> LockState.Unlocked
            UnlockResult.WrongPassphrase -> LockState.Message("That password is not correct.", LockState.Locked)
            UnlockResult.NoVault -> LockState.Message("No vault exists on this device yet.", LockState.NeedsPassword)
            is UnlockResult.PlatformFailure -> LockState.Message(
                "This device cannot open the vault (${result.reason}). The vault is tied to this installation.",
                LockState.Locked
            )
            is UnlockResult.CorruptEnvelope -> LockState.Message(
                "The vault on this device is damaged (${result.reason}). Restore from an encrypted backup.",
                LockState.Locked
            )
        }
    }

    fun lock() {
        vaultRepository.lock()
        state.value = LockState.Locked
    }

    fun dismissMessage() {
        val current = state.value
        if (current is LockState.Message) state.value = current.after
    }

    companion object {
        /**
         * Returns the reason a new password is not acceptable, or `null` when it
         * is. Length is checked before anything else because a short passphrase
         * is the one mistake a user cannot see.
         */
        fun validateNewPassword(password: CharArray, confirmation: CharArray): String? = when {
            password.size < MIN_PASSWORD_LENGTH ->
                "Use at least $MIN_PASSWORD_LENGTH characters."
            !password.contentEquals(confirmation) ->
                "The two entries do not match."
            else -> null
        }
    }
}
