package com.securewa.core.security

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Twilio request validation.
 *
 * Twilio signs inbound webhook requests with
 * `Base64(HMAC-SHA1(secret, requestURL + concatenated sorted parameters))`
 * where the parameters are the POST form fields sorted by key with key and
 * value concatenated without a separator. The secret is the account Auth
 * Token. This is the documented Twilio validation algorithm and it is the only
 * mechanism used here to authenticate an inbound request.
 *
 * The comparison is constant time. A caller must additionally apply
 * [ReplayGuard]: a Twilio signature carries no expiry, so a captured request
 * stays valid forever unless the receiver records the signatures it has seen.
 */
object TwilioSignatureValidator {

    private const val HMAC_SHA1 = "HmacSHA1"

    /** Computes the expected `X-Twilio-Signature` value for a form-encoded request. */
    fun expectedSignature(authToken: String, url: String, params: Map<String, String>): String {
        val data = url + params.keys.sorted().joinToString("") { key -> key + (params[key] ?: "") }
        val mac = Mac.getInstance(HMAC_SHA1).apply {
            init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), HMAC_SHA1))
        }
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return androidBase64Encode(raw)
    }

    /**
     * Validates an inbound request.
     *
     * @param providedSignature value of the `X-Twilio-Signature` header.
     * @param replayGuard       nonce store; a signature that was already accepted
     *                          is rejected as a replay.
     */
    fun validate(
        authToken: String,
        url: String,
        params: Map<String, String>,
        providedSignature: String?,
        replayGuard: ReplayGuard,
        nowMillis: Long
    ): ValidationResult {
        if (authToken.isBlank()) return ValidationResult.REJECTED_NO_SECRET
        if (providedSignature.isNullOrBlank()) return ValidationResult.REJECTED_MISSING_SIGNATURE

        val expected = expectedSignature(authToken, url, params)
        if (!constantTimeEquals(expected, providedSignature)) return ValidationResult.REJECTED_BAD_SIGNATURE

        return if (replayGuard.checkAndRecord(providedSignature, nowMillis)) {
            ValidationResult.ACCEPTED
        } else {
            ValidationResult.REJECTED_REPLAY
        }
    }

    /** Constant-time comparison that does not short circuit on length or content. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        if (aBytes.size != bBytes.size) return false
        var diff = 0
        for (i in aBytes.indices) diff = diff or (aBytes[i].toInt() xor bBytes[i].toInt())
        return diff == 0
    }

    /**
     * Parses an `application/x-www-form-urlencoded` body into the parameter map
     * used for signature computation. Twilio sends inbound webhooks in this
     * format. Values are URL-decoded exactly once, mirroring the encoding Twilio
     * applied when it signed the request.
     */
    fun parseFormBody(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        body.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawValue = if (idx >= 0) pair.substring(idx + 1) else ""
            out[percentDecode(rawKey)] = percentDecode(rawValue)
        }
        return out
    }

    private fun percentDecode(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3)
                    val parsed = hex.toIntOrNull(16)
                    if (parsed != null) {
                        out.append(parsed.toChar())
                        i += 3
                    } else {
                        out.append(c)
                        i += 1
                    }
                }
                c == '+' -> {
                    out.append(' ')
                    i += 1
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }

    /**
     * Base64 encoding without a line break, matching what Twilio sends.
     * Implemented here so the domain module needs no platform codec.
     */
    private fun androidBase64Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            out.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Lower-case locale-independent helper kept for callers normalising URLs. */
    fun normalizeUrl(url: String): String = url.trim().lowercase(Locale.ROOT)
}

/** Outcome of inbound request validation. */
enum class ValidationResult(val accepted: Boolean, val auditMessage: String) {
    ACCEPTED(true, "signature accepted"),
    REJECTED_NO_SECRET(false, "rejected: no auth token configured"),
    REJECTED_MISSING_SIGNATURE(false, "rejected: no X-Twilio-Signature header"),
    REJECTED_BAD_SIGNATURE(false, "rejected: signature mismatch"),
    REJECTED_REPLAY(false, "rejected: signature already used (replay)")
}
