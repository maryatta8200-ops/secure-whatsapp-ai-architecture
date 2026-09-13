package com.securewa.core.security

/**
 * Text codec for [VaultEnvelope].
 *
 * The envelope has to be persisted, and a line-oriented text format was chosen
 * over a binary one so the stored form is inspectable and so a future reader can
 * tell a corrupted record from an unknown version. Nothing in it is secret: the
 * salt, the iteration count and the wrapped key are all safe to store, and the
 * passphrase is never part of the record.
 *
 * Format, one field per line:
 *
 * ```
 * v1
 * iterations
 * keyChecksum
 * salt (hex)
 * nonce (hex)
 * ciphertext (hex)
 * ```
 */
object VaultEnvelopeCodec {

    fun encode(envelope: VaultEnvelope): String = buildString {
        append("v").append(envelope.version).append('\n')
        append(envelope.iterations).append('\n')
        append(envelope.keyChecksum).append('\n')
        append(Hex.encode(envelope.salt)).append('\n')
        append(Hex.encode(envelope.wrappedMasterKey.nonce)).append('\n')
        append(Hex.encode(envelope.wrappedMasterKey.ciphertext)).append('\n')
    }

    /**
     * @throws IllegalArgumentException when the text is not a well formed
     *         envelope. A malformed record must be reported as such, never
     *         treated as "no vault" - that would silently destroy a user's
     *         ability to recover their configuration.
     */
    fun decode(text: String): VaultEnvelope {
        val lines = text.trim().split('\n').map { it.trim() }
        require(lines.size == 6) { "malformed vault envelope: expected 6 lines, got ${lines.size}" }
        require(lines[0] == "v${VaultEnvelope.CURRENT_VERSION}") {
            "unsupported vault envelope version '${lines[0]}'"
        }
        val iterations = lines[1].toIntOrNull()
            ?: throw IllegalArgumentException("malformed iteration count")
        return VaultEnvelope(
            version = VaultEnvelope.CURRENT_VERSION,
            salt = Hex.decode(lines[3]),
            iterations = iterations,
            keyChecksum = lines[2],
            wrappedMasterKey = AeadCipher.Sealed(
                nonce = Hex.decode(lines[4]),
                ciphertext = Hex.decode(lines[5])
            )
        )
    }
}

/** Hex encoding without a platform dependency, used by [VaultEnvelopeCodec]. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val high = text[i * 2].digitToIntOrNull(16) ?: throw IllegalArgumentException("not hex: '${text[i * 2]}'")
            val low = text[i * 2 + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException("not hex: '${text[i * 2 + 1]}'")
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
