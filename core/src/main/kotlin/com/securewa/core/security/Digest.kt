package com.securewa.core.security

import java.security.MessageDigest

/**
 * Digest helpers used for idempotency keys, correlation identifiers and
 * configuration revision digests.
 *
 * SHA-256 is used so that identifiers can be derived from message identifiers
 * without storing the identifier itself where that is desirable, while keeping
 * collisions out of practical reach.
 */
object Digest {
    private const val HEX = "0123456789abcdef"

    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    /** Stable digest of a configuration payload, used to pin an agent revision. */
    fun configDigest(parts: List<String>): String = sha256Hex(parts.joinToString("\u0000"))
}
