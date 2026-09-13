package com.securewa.core.policy

/**
 * Per-key token bucket rate limiter.
 *
 * Used to bound AI provider calls and outbound sends per registered number and
 * per agent, which is what keeps cost and CPU usage bounded: once a bucket is
 * empty the caller backs off instead of hammering the provider.
 *
 * The limiter is a pure state machine driven by an externally supplied clock,
 * so tests control time instead of sleeping. The number of tracked keys is
 * bounded: when the bound is exceeded the oldest key is evicted, which is safe
 * because a bucket is only an optimisation on top of the durable audit trail.
 */
class RateLimiter(
    private val capacity: Int = 10,
    private val refillTokensPerMinute: Double = 10.0,
    private val maxTrackedKeys: Int = 512
) {
    private data class Bucket(var tokens: Double, var lastRefillMillis: Long)

    private val buckets = LinkedHashMap<String, Bucket>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillTokensPerMinute > 0.0) { "refillTokensPerMinute must be positive" }
    }

    /**
     * Consumes [tokens] from the bucket identified by [key].
     *
     * @return `true` when the request is within the limit.
     */
    @Synchronized
    fun tryConsume(key: String, nowMillis: Long, tokens: Int = 1): Boolean {
        require(tokens >= 0) { "tokens must not be negative" }
        val bucket = buckets.getOrPut(key) {
            evictIfNeeded()
            Bucket(capacity.toDouble(), nowMillis).also { buckets[key] = it }
        }
        refill(bucket, nowMillis)
        return if (bucket.tokens >= tokens) {
            bucket.tokens -= tokens
            true
        } else {
            false
        }
    }

    /** Tokens currently available for [key] after refilling to [nowMillis]. */
    @Synchronized
    fun availableTokens(key: String, nowMillis: Long): Double {
        val bucket = buckets[key] ?: return capacity.toDouble()
        refill(bucket, nowMillis)
        return bucket.tokens
    }

    /** Drops buckets that have not been touched for [maxIdleMillis]. */
    @Synchronized
    fun trimIdle(nowMillis: Long, maxIdleMillis: Long = 60 * 60 * 1000L) {
        buckets.entries.removeIf { (_, bucket) -> nowMillis - bucket.lastRefillMillis >= maxIdleMillis }
    }

    @Synchronized
    fun trackedKeys(): Int = buckets.size

    private fun refill(bucket: Bucket, nowMillis: Long) {
        val elapsedMillis = (nowMillis - bucket.lastRefillMillis).coerceAtLeast(0L)
        if (elapsedMillis == 0L) return
        val refill = (elapsedMillis / 60_000.0) * refillTokensPerMinute
        bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + refill)
        bucket.lastRefillMillis = nowMillis
    }

    private fun evictIfNeeded() {
        while (buckets.size >= maxTrackedKeys) {
            val oldest = buckets.entries.firstOrNull() ?: break
            buckets.remove(oldest.key)
        }
    }
}
