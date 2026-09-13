package com.securewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.securewa.data.db.entity.InboundMessageReceiptEntity
import com.securewa.data.db.entity.OutboundMessageAttemptEntity

/**
 * Durable inbound receipts and outbound attempts.
 *
 * Both tables are keyed by an idempotency key with a unique index, so a
 * redelivered inbound message or a retried outbound send fails the insert
 * instead of duplicating work. That is the persistence half of "process exactly
 * once"; the other half is the retry state machine below.
 */
@Dao
interface DeliveryDao {

    // --- inbound ------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: InboundMessageReceiptEntity)

    @Query("SELECT * FROM inbound_message_receipts WHERE idempotency_key = :idempotencyKey")
    suspend fun receiptByIdempotencyKey(idempotencyKey: String): InboundMessageReceiptEntity?

    @Query("SELECT * FROM inbound_message_receipts WHERE id = :id")
    suspend fun receiptById(id: String): InboundMessageReceiptEntity?

    /**
     * Receipts that are ready to be processed, oldest first. Bounded by
     * [limit] so a large backlog is drained in batches instead of in one query.
     */
    @Query(
        "SELECT * FROM inbound_message_receipts WHERE state = 'RECEIVED' " +
            "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) " +
            "ORDER BY received_at ASC LIMIT :limit"
    )
    suspend fun claimableReceipts(now: Long, limit: Int): List<InboundMessageReceiptEntity>

    @Query(
        "UPDATE inbound_message_receipts SET state = :state, attempts = :attempts, " +
            "next_attempt_at = :nextAttemptAt, correlation_id = :correlationId, " +
            "last_error_redacted = :lastErrorRedacted, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateReceiptState(
        id: String,
        state: String,
        attempts: Int,
        nextAttemptAt: Long?,
        correlationId: String?,
        lastErrorRedacted: String?,
        updatedAt: Long
    )

    @Query(
        "SELECT COUNT(*) FROM inbound_message_receipts WHERE state IN ('RECEIVED', 'PROCESSING')"
    )
    suspend fun pendingReceiptCount(): Int

    @Query(
        "SELECT MIN(received_at) FROM inbound_message_receipts " +
            "WHERE state IN ('RECEIVED', 'PROCESSING')"
    )
    suspend fun oldestPendingReceivedAt(): Long?

    /**
     * Marks a receipt as being processed. Returns the number of rows changed:
     * 1 when this caller claimed it, 0 when another worker already did, which
     * is what makes processing after process death safe.
     */
    @Transaction
    suspend fun tryClaimReceipt(id: String, now: Long): Boolean {
        val receipt = receiptById(id) ?: return false
        if (receipt.state != "RECEIVED") return false
        if (receipt.nextAttemptAt != null && receipt.nextAttemptAt > now) return false
        updateReceiptState(
            id = id,
            state = "PROCESSING",
            attempts = receipt.attempts + 1,
            nextAttemptAt = null,
            correlationId = receipt.correlationId,
            lastErrorRedacted = receipt.lastErrorRedacted,
            updatedAt = now
        )
        return true
    }

    // --- outbound -----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: OutboundMessageAttemptEntity)

    @Query("SELECT * FROM outbound_message_attempts WHERE idempotency_key = :idempotencyKey")
    suspend fun attemptByIdempotencyKey(idempotencyKey: String): OutboundMessageAttemptEntity?

    @Query("SELECT * FROM outbound_message_attempts WHERE inbound_receipt_id = :receiptId ORDER BY attempt ASC")
    suspend fun attemptsForReceipt(receiptId: String): List<OutboundMessageAttemptEntity>

    @Query(
        "UPDATE outbound_message_attempts SET status = :status, twilio_message_sid = :twilioSid, " +
            "last_error_redacted = :lastErrorRedacted, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateAttemptStatus(
        id: String,
        status: String,
        twilioSid: String?,
        lastErrorRedacted: String?,
        updatedAt: Long
    )

    @Query(
        "SELECT * FROM outbound_message_attempts WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun pendingAttempts(limit: Int): List<OutboundMessageAttemptEntity>
}
