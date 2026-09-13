package com.securewa.data.vault

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Atomicity and lifecycle of the file that holds the sealed envelope.
 *
 * A temporary directory is created per test rather than with a JUnit rule so
 * the same source compiles against the real JUnit 4 in CI and against the local
 * shim in the sandbox, which implements only plain assertions.
 */
class FileVaultStorageTest {

    private val createdDirectories = mutableListOf<File>()

    private fun storage(): FileVaultStorage {
        val directory = File.createTempFile("vault-test", "").apply {
            delete()
            mkdirs()
        }
        createdDirectories += directory
        return FileVaultStorage(directory)
    }

    private fun cleanUp() {
        createdDirectories.forEach { it.deleteRecursively() }
        createdDirectories.clear()
    }

    @Test
    fun `a written envelope can be read back`() {
        try {
            val storage = storage()
            val bytes = "v1\n210000".toByteArray()
            storage.write(bytes)
            assertArrayEquals(bytes, storage.read()!!)
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `reading before anything was written returns null`() {
        try {
            assertNull(storage().read())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `clear removes the envelope`() {
        try {
            val storage = storage()
            storage.write("content".toByteArray())
            storage.clear()
            assertNull(storage.read())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `a write leaves no temporary file behind`() {
        try {
            val storage = storage()
            storage.write("content".toByteArray())
            val files = storage.directory.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertEquals("a half written file must never be left next to the vault", listOf("vault.bin"), files)
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `overwriting replaces the previous content`() {
        try {
            val storage = storage()
            storage.write("first".toByteArray())
            storage.write("second".toByteArray())
            assertEquals("second", String(storage.read()!!, Charsets.UTF_8))
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `an empty write is still a readable envelope location`() {
        try {
            val storage = storage()
            storage.write(ByteArray(0))
            assertTrue(storage.read()!!.isEmpty())
        } finally {
            cleanUp()
        }
    }
}
