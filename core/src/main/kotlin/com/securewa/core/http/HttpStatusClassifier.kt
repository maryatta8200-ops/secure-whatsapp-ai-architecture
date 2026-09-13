package com.securewa.core.http

/**
 * What an HTTP status from a third party means to a request we might retry.
 *
 * This is shared by every API this app calls, so the retry decision is made
 * once. Two classifiers that disagree is a bug that is very hard to see: one
 * endpoint would be hammered while another gives up too early.
 */
enum class HttpStatusOutcome {
    /** The credential was refused. Trying again can only burn quota. */
    AUTHENTICATION,

    /** Too many requests. Waiting and trying again can work. */
    RATE_LIMITED,

    /** The request itself was refused. Retrying it unchanged cannot work. */
    INVALID_REQUEST,

    /** The other side gave up waiting before answering. */
    TIMEOUT,

    /** The other side failed. It may well recover. */
    SERVER_ERROR,

    /** A status this app does not know how to interpret. */
    UNEXPECTED_STATUS
}

/** An [HttpStatusOutcome] together with whether another attempt is worth making. */
data class HttpStatusVerdict(
    val outcome: HttpStatusOutcome,
    val retryable: Boolean
)

object HttpStatusClassifier {

    fun verdictFor(statusCode: Int): HttpStatusVerdict = when (statusCode) {
        401, 403 -> HttpStatusVerdict(HttpStatusOutcome.AUTHENTICATION, retryable = false)
        408, 409 -> HttpStatusVerdict(HttpStatusOutcome.TIMEOUT, retryable = true)
        429 -> HttpStatusVerdict(HttpStatusOutcome.RATE_LIMITED, retryable = true)
        in 400..499 -> HttpStatusVerdict(HttpStatusOutcome.INVALID_REQUEST, retryable = false)
        in 500..599 -> HttpStatusVerdict(HttpStatusOutcome.SERVER_ERROR, retryable = true)
        else -> HttpStatusVerdict(HttpStatusOutcome.UNEXPECTED_STATUS, retryable = false)
    }

    /**
     * Whether a transport failure of these kinds is worth another attempt.
     *
     * A timeout and a dropped connection are; anything else is treated as
     * permanent, because retrying a request that was refused on its merits only
     * delays the user.
     */
    fun isRetryableTransportFailure(outcome: HttpStatusOutcome): Boolean = when (outcome) {
        HttpStatusOutcome.TIMEOUT -> true
        HttpStatusOutcome.SERVER_ERROR -> true
        HttpStatusOutcome.RATE_LIMITED -> true
        else -> false
    }
}
