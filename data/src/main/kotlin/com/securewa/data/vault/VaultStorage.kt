package com.securewa.data.vault

import java.io.File

/**
 * Where the sealed vault envelope is kept.
 *
 * The envelope is deliberately not a database row: it is a distinct security
 * artefact with a different lifecycle from application data, and keeping it out
 * of the database means a schema migration can never touch it.
 */
interface VaultStorage {
    /** `null` when no vault exists yet. */
    fun read(): ByteArray?

    fun write(bytes: ByteArray)

    fun clear()
}

/**
 * Stores the envelope as a file inside the application's private storage.
 *
 * Writes are atomic: the bytes go to a temporary file that is then renamed into
 * place. A crash or a killed process therefore leaves either the old envelope or
 * the new one, never a half-written one, which matters because a corrupt
 * envelope loses access to every stored credential.
 */
class FileVaultStorage(
    /** Visible for tests so they can assert on what is left on disk. */
    internal val directory: File,
    internal val fileName: String = "vault.bin"
) : VaultStorage {

    private val target: File get() = File(directory, fileName)
    private val temporary: File get() = File(directory, "$fileName.tmp")

    override fun read(): ByteArray? {
        val file = target
        if (!file.exists()) return null
        return file.readBytes()
    }

    override fun write(bytes: ByteArray) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("cannot create vault directory ${directory.absolutePath}")
        }
        val temp = temporary
        temp.writeBytes(bytes)
        // fsync before the rename so the data is on disk before it becomes
        // visible under its final name. The stream must be opened in append
        // mode: opening it for writing would truncate what was just written.
        java.io.FileOutputStream(temp, true).use { it.fd.sync() }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("cannot move the vault file into place")
        }
    }

    override fun clear() {
        target.delete()
        temporary.delete()
    }
}
