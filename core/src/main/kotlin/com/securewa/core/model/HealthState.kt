package com.securewa.core.model

/**
 * Connection and health states shown throughout the UI.
 *
 * The UI must be able to distinguish every one of these states, because
 * "configured" is not "connected": a number whose credentials have never been
 * exercised is CONFIGURED, never CONNECTED.
 */
enum class ConnectionState(
    val displayName: String,
    val isOperational: Boolean,
    val isTerminal: Boolean = false
) {
    /** Settings exist but no authenticated check has ever succeeded. */
    UNCONFIGURED("Not configured", false),

    /** Settings exist but no check has run yet. */
    UNKNOWN("Unknown", false),

    /** An authenticated check is currently in flight. */
    VALIDATING("Validating", false),

    /** An authenticated check succeeded. */
    CONNECTED("Connected", true),

    /** A check succeeded but telemetry shows failures, latency or backlog. */
    DEGRADED("Degraded", true),

    /** A check failed or the last known good check has expired. */
    DISCONNECTED("Disconnected", false),

    /** The user switched this off; no checks are performed. */
    DISABLED("Disabled", false, isTerminal = true),

    /** The feature is not implemented in this build. */
    UNAVAILABLE("Unavailable", false, isTerminal = true)
}

/** How a [ConnectionState] was established. The UI shows this explicitly. */
enum class HealthEvidence {
    /** Derived from an authenticated request made by this app. */
    AUTHENTICATED_CHECK,
    /** Derived from telemetry reported by the configured receiver. */
    RECEIVER_TELEMETRY,
    /** Restored from the last persisted result. */
    PERSISTED_LAST_KNOWN,
    /** No evidence exists. */
    NONE
}

/** One observable health record. Never carries secrets. */
data class HealthSnapshot(
    val state: ConnectionState,
    val evidence: HealthEvidence,
    val checkedAtEpochMillis: Long? = null,
    /** Already redacted by the caller before being stored or displayed. */
    val detail: String? = null,
    val consecutiveFailures: Int = 0
) {
    val isOperational: Boolean get() = state.isOperational

    companion object {
        fun unconfigured(detail: String? = null) = HealthSnapshot(
            state = ConnectionState.UNCONFIGURED,
            evidence = HealthEvidence.NONE,
            detail = detail
        )
    }
}
