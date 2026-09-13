package com.securewa.core.security

/**
 * Removes secrets and personal identifiers from anything that may leave the
 * processing boundary: logs, notifications, crash reports, exported diagnostics
 * and audit explanations.
 *
 * Nothing in this class is a placeholder: redaction is applied to the real
 * strings before they are written. Values that cannot be safely rendered are
 * replaced, not truncated and not "anonymised" in a reversible way.
 */
object Redactor {

    /**
     * Field names whose value is never safe to log, regardless of content.
     * Keys are normalised (lower case, `-` folded to `_`) before lookup.
     */
    private val SENSITIVE_FIELD_NAMES = setOf(
        "authtoken", "authorization", "apikey", "password", "passphrase",
        "secret", "token", "credential", "signature", "signatureheader",
        "xtwiliosignature", "twilioauthtoken", "privatekey", "bearer"
    )

    private const val REDACTED = "***REDACTED***"

    private val OPENAI_KEY = Regex("sk-[A-Za-z0-9_-]{16,}")
    private val ANTHROPIC_KEY = Regex("sk-ant-[A-Za-z0-9_-]{16,}")
    private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_-]{20,}")
    private val SLACK_TOKEN = Regex("xox[baprs]-[A-Za-z0-9-]{10,}")
    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /**
     * A phone-like digit run. The lookarounds are essential: without them a
     * Twilio MessageSid (which contains a long run of hex digits) would be
     * mangled and the log would lose exactly the identifier needed to
     * troubleshoot a delivery.
     */
    private val LONG_PHONE = Regex("(?<![A-Za-z0-9])\\+?\\d{7,15}(?![A-Za-z0-9])")

    /** A bare 32 character hex secret, for example a Twilio auth token. */
    private val HEX_SECRET = Regex("(?<![A-Za-z0-9])[0-9a-f]{32}(?![A-Za-z0-9])")

    /** Redacts free text that is about to be logged. */
    fun redactText(input: String?): String {
        if (input == null) return ""
        var out = input
        out = BEARER.replace(out, "Bearer $REDACTED")
        out = ANTHROPIC_KEY.replace(out, REDACTED)
        out = OPENAI_KEY.replace(out, REDACTED)
        out = GOOGLE_KEY.replace(out, REDACTED)
        out = SLACK_TOKEN.replace(out, REDACTED)
        out = HEX_SECRET.replace(out, REDACTED)
        out = EMAIL.replace(out, REDACTED)
        out = LONG_PHONE.replace(out) { match -> maskNumber(match.value) }
        return out
    }

    /**
     * Redacts a structured field map. Keys matching [SENSITIVE_FIELD_NAMES] are
     * removed entirely; every other value is passed through [redactText].
     */
    fun redactFields(fields: Map<String, String?>): Map<String, String> =
        fields.entries.associate { (key, value) ->
            // Strip every separator so Auth-Token, auth_token and authToken all
            // collapse to the same entry.
            val normalizedKey = key.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            if (normalizedKey in SENSITIVE_FIELD_NAMES) {
                key to REDACTED
            } else {
                key to redactText(value)
            }
        }

    /**
     * Masks a phone number for display, keeping the country calling code and at
     * most the last two digits. Every digit in between is replaced.
     */
    fun maskNumber(value: String): String {
        if (value.isBlank()) return ""
        val plus = value.startsWith("+")
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return REDACTED
        if (digits.length <= 3) return (if (plus) "+" else "") + "X".repeat(digits.length)
        val keepTail = if (digits.length >= 6) 2 else 0
        val headLength = minOf(3, digits.length - keepTail)
        val head = digits.substring(0, headLength)
        val tail = if (keepTail > 0) digits.substring(digits.length - keepTail) else ""
        val masked = "X".repeat(digits.length - headLength - keepTail)
        return (if (plus) "+" else "") + head + masked + tail
    }

    /** Redaction marker used across the app so callers can assert on it. */
    fun marker(): String = REDACTED
}
