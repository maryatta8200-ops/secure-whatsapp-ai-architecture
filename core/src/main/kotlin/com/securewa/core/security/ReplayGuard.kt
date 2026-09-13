package com.securewa.core.security

/**
 * Bounded, in-memory replay protection for inbound request identifiers.
 *
 * Twilio signatures do not expire, so a receiver must remember the signatures
 * (or message identifiers) it has already accepted. This guard is the fast
 * path: it is intentionally bounded so a burst of traffic cannot exhaust
 * memory, and it only holds identifiers, never message content.
 *
 * Durable deduplication is the responsibility of the persistence layer, which
 * stores an idempotency key per inbound message (see `IdempotencyKeys`); this
 * guard is what stops an obvious replay before any database work happens.
 */
class ReplayGuard(
    private val maxEntries: Int = 1024,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS
) {
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Returns `true` when [nonce] has not been seen (or its entry expired) and
     * records it. Returns `false` for a replay.
     */
    @Synchronized
    fun checkAndRecord(nonce: String, nowMillis: Long): Boolean {
        purge(nowMillis)
        val existing = seen[nonce]
        if (existing != null && nowMillis - existing < ttlMillis) return false
        seen[nonce] = nowMillis
        trimToBound()
        return true
    }

    /** Removes expired entries. Called before every check to keep the map small. */
    @Synchronized
    fun purge(nowMillis: Long) {
        val expired = seen.entries.filter { (_, at) -> nowMillis - at >= ttlMillis }
        expired.forEach { seen.remove(it.key) }
    }

    @Synchronized
    fun size(): Int = seen.size

    private fun trimToBound() {
        while (seen.size > maxEntries) {
            val oldest = seen.entries.firstOrNull() ?: break
            seen.remove(oldest.key)
        }
    }

    companion object {
        /** 15 minutes, comfortably above Twilio's own retry window. */
        const val DEFAULT_TTL_MILLIS: Long = 15 * 60 * 1000L
    }
}
