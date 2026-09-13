package com.securewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One conversation between one registered number (through one agent) and one
 * counterparty.
 *
 * [agentId] and [registeredNumberId] are nullable with SET_NULL deletion:
 * deleting an agent or a number must never delete the history of what it said,
 * it only detaches the history from the deleted configuration.
 */
@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = RegisteredNumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["registered_number_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["registered_number_id", "counterparty_e164", "channel_kind"]),
        Index("agent_id"),
        Index("last_message_at")
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String?,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "counterparty_e164") val counterpartyE164: String,
    @ColumnInfo(name = "channel_kind") val channelKind: String,
    @ColumnInfo(name = "state") val state: String = "OPEN",
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * One message inside one conversation.
 *
 * Content is stored as plain text in this milestone. The database file is
 * protected by `allowBackup=false`, by the data extraction rules and by device
 * encryption; encrypting the message bodies themselves is part of milestone 3,
 * which introduces the key this column will be sealed with. This limitation is
 * recorded in docs/MILESTONES.md and is not presented as encryption at rest.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index("created_at"),
        Index(value = ["conversation_id", "external_id"], unique = true)
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "role") val role: String,
    /** Twilio MessageSid for inbound and outbound messages, provider id otherwise. */
    @ColumnInfo(name = "external_id") val externalId: String? = null,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "status") val status: String = "COMPLETED",
    @ColumnInfo(name = "provider_kind") val providerKind: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "agent_revision") val agentRevision: Long? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int? = null,
    @ColumnInfo(name = "latency_millis") val latencyMillis: Long? = null,
    @ColumnInfo(name = "error_redacted") val errorRedacted: String? = null,
    @ColumnInfo(name = "correlation_id") val correlationId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Durable record of one inbound message.
 *
 * [idempotencyKey] is unique: inserting the same inbound message twice fails,
 * which is what makes redelivery from the receiver safe. The state machine
 * (`RECEIVED` → `PROCESSING` → `COMPLETED` / `FAILED`) is what makes processing
 * after process death resumable.
 */
@Entity(
    tableName = "inbound_message_receipts",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["state", "next_attempt_at"]),
        Index("received_at")
    ]
)
data class InboundMessageReceiptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "channel_kind") val channelKind: String,
    @ColumnInfo(name = "destination_e164") val destinationE164: String,
    @ColumnInfo(name = "sender_e164") val senderE164: String,
    @ColumnInfo(name = "external_message_id") val externalMessageId: String,
    @ColumnInfo(name = "body") val body: String? = null,
    @ColumnInfo(name = "signature_valid") val signatureValid: Boolean = false,
    @ColumnInfo(name = "replay_rejected") val replayRejected: Boolean = false,
    @ColumnInfo(name = "state") val state: String = "RECEIVED",
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
    @ColumnInfo(name = "correlation_id") val correlationId: String? = null,
    @ColumnInfo(name = "last_error_redacted") val lastErrorRedacted: String? = null,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * One attempt to send a reply through Twilio.
 *
 * The unique idempotency key is what stops a retry after a timeout from
 * producing a second WhatsApp message for the same turn.
 */
@Entity(
    tableName = "outbound_message_attempts",
    foreignKeys = [
        ForeignKey(
            entity = InboundMessageReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["inbound_receipt_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = TwilioConfigurationEntity::class,
            parentColumns = ["id"],
            childColumns = ["twilio_configuration_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index("inbound_receipt_id"),
        Index("conversation_id"),
        Index("created_at")
    ]
)
data class OutboundMessageAttemptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "inbound_receipt_id") val inboundReceiptId: String? = null,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "twilio_configuration_id") val twilioConfigurationId: String? = null,
    @ColumnInfo(name = "to_e164") val toE164: String,
    @ColumnInfo(name = "from_address") val fromAddress: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "twilio_message_sid") val twilioMessageSid: String? = null,
    @ColumnInfo(name = "status") val status: String = "PENDING",
    @ColumnInfo(name = "attempt") val attempt: Int = 1,
    @ColumnInfo(name = "last_error_redacted") val lastErrorRedacted: String? = null,
    @ColumnInfo(name = "correlation_id") val correlationId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** An auditable event in the life of one agent or one number. */
@Entity(
    tableName = "agent_events",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RegisteredNumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["registered_number_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("agent_id"), Index("registered_number_id"), Index("created_at")]
)
data class AgentEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String? = null,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "correlation_id") val correlationId: String? = null,
    /** Already redacted by the caller; this column never holds raw content. */
    @ColumnInfo(name = "detail_redacted") val detailRedacted: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Bounded application log. Retention is enforced by the repository using the
 * configured window so the table cannot grow without limit.
 */
@Entity(
    tableName = "app_logs",
    indices = [Index("timestamp"), Index("correlation_id")]
)
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "tag") val tag: String,
    /** Already redacted by the caller. */
    @ColumnInfo(name = "message_redacted") val messageRedacted: String,
    @ColumnInfo(name = "correlation_id") val correlationId: String? = null
)
