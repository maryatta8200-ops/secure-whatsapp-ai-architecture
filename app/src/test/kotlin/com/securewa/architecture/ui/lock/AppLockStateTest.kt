package com.securewa.architecture.ui.lock

import com.securewa.core.security.AeadCipher
import com.securewa.data.vault.PlatformSealer
import com.securewa.data.vault.VaultRepository
import com.securewa.data.vault.VaultStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock is the gate in front of every credential in the app, so its state
 * machine is asserted directly: a wrong password must never reach the unlocked
 * state, and a platform failure must be reported as one rather than as a wrong
 * password.
 */
class AppLockStateTest {

    private class MemoryStorage : VaultStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(value: ByteArray) {
            bytes = value.copyOf()
        }
        override fun clear() {
            bytes = null
        }
    }

    private class FakeSealer : PlatformSealer {
        private val key = ByteArray(32) { 4 }
        override fun seal(plaintext: ByteArray): ByteArray {
            val sealed = AeadCipher.seal(key, plaintext)
            return sealed.nonce + sealed.ciphertext
        }
        override fun open(sealed: ByteArray): ByteArray = AeadCipher.open(
            key,
            AeadCipher.Sealed(
                nonce = sealed.copyOfRange(0, 12),
                ciphertext = sealed.copyOfRange(12, sealed.size)
            )
        )
    }

    private val password = "a long enough password".toCharArray()

    private fun state(storage: MemoryStorage = MemoryStorage()): AppLockState {
        // 100_000 is the minimum this project accepts; it keeps the suite fast.
        return AppLockState(VaultRepository(storage, FakeSealer(), 100_000))
    }

    @Test
    fun `a fresh install asks for a new password`() {
        assertEquals(LockState.NeedsPassword, state().lockState.value)
    }

    @Test
    fun `an existing vault starts locked`() {
        val storage = MemoryStorage()
        state(storage).createPassword(password, password)
        assertEquals(LockState.Locked, state(storage).lockState.value)
    }

    @Test
    fun `a short password is refused before a vault is created`() {
        val lock = state()
        lock.createPassword("short".toCharArray(), "short".toCharArray())
        val message = lock.lockState.value
        assertTrue("$message", message is LockState.Message)
        assertTrue((message as LockState.Message).text.contains("12"))
        assertEquals(LockState.NeedsPassword, message.after)
    }

    @Test
    fun `a mismatched confirmation is refused`() {
        val lock = state()
        lock.createPassword(password, "a different long password".toCharArray())
        val message = lock.lockState.value as LockState.Message
        assertTrue(message.text.contains("do not match"))
    }

    @Test
    fun `creating the vault unlocks it`() {
        val lock = state()
        lock.createPassword(password, password)
        assertEquals(LockState.Unlocked, lock.lockState.value)
    }

    @Test
    fun `a wrong password never unlocks`() {
        val storage = MemoryStorage()
        state(storage).createPassword(password, password)

        val lock = state(storage)
        lock.unlock("not the right password".toCharArray())
        val message = lock.lockState.value as LockState.Message
        assertTrue(message.text.contains("not correct"))
        assertEquals(LockState.Locked, message.after)
    }

    @Test
    fun `the correct password unlocks`() {
        val storage = MemoryStorage()
        state(storage).createPassword(password, password)
        val lock = state(storage)
        lock.unlock(password)
        assertEquals(LockState.Unlocked, lock.lockState.value)
    }

    @Test
    fun `locking returns to the locked state`() {
        val storage = MemoryStorage()
        state(storage).createPassword(password, password)
        val lock = state(storage)
        lock.unlock(password)
        lock.lock()
        assertEquals(LockState.Locked, lock.lockState.value)
    }

    @Test
    fun `dismissing a message returns to the state behind it`() {
        val storage = MemoryStorage()
        state(storage).createPassword(password, password)
        val lock = state(storage)
        lock.unlock("wrong".toCharArray())
        lock.dismissMessage()
        assertEquals(LockState.Locked, lock.lockState.value)
    }

    @Test
    fun `password validation reports the reason`() {
        assertEquals(
            "Use at least 12 characters.",
            AppLockState.validateNewPassword("tiny".toCharArray(), "tiny".toCharArray())
        )
        assertEquals(
            "The two entries do not match.",
            AppLockState.validateNewPassword(password, "another long password".toCharArray())
        )
        assertEquals(
            null,
            AppLockState.validateNewPassword(password, password.copyOf())
        )
    }
}
