package com.securewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An agent: an independently configurable AI responder bound to one registered
 * number and one user type.
 *
 * Several agents may reference the same credential slot or the same model
 * configuration. That shares the ability to authenticate and the model choice,
 * and nothing else: the prompt, the revision, the routing rules and the
 * conversations are per agent.
 *
 * [revision] increments on every saved change and is captured into the
 * immutable snapshot used for a message turn, so an in-flight message can never
 * combine settings from two revisions.
 */
@Entity(
    tableName = "agents",
    foreignKeys = [
        ForeignKey(
            entity = RegisteredNumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["registered_number_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CredentialSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["credential_slot_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ModelConfigurationEntity::class,
            parentColumns = ["id"],
            childColumns = ["model_configuration_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ModelConfigurationEntity::class,
            parentColumns = ["id"],
            childColumns = ["fallback_model_configuration_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("registered_number_id"),
        Index("credential_slot_id"),
        Index("model_configuration_id"),
        Index("fallback_model_configuration_id")
    ]
)
data class AgentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "user_type_key") val userTypeKey: String,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    /** Digest of [systemPrompt], safe to log, used to pin a configuration revision. */
    @ColumnInfo(name = "system_prompt_digest") val systemPromptDigest: String,
    @ColumnInfo(name = "model_configuration_id") val modelConfigurationId: String,
    @ColumnInfo(name = "fallback_model_configuration_id") val fallbackModelConfigurationId: String? = null,
    @ColumnInfo(name = "credential_slot_id") val credentialSlotId: String? = null,
    @ColumnInfo(name = "fallback_enabled") val fallbackEnabled: Boolean = false,
    @ColumnInfo(name = "fallback_reasons") val fallbackReasons: String = "",
    @ColumnInfo(name = "temperature_milli") val temperatureMilli: Int? = null,
    @ColumnInfo(name = "max_output_tokens") val maxOutputTokens: Int? = null,
    @ColumnInfo(name = "language_tag") val languageTag: String = "en",
    @ColumnInfo(name = "memory_window_messages") val memoryWindowMessages: Int = 10,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "revision") val revision: Long = 1L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * A user-defined rule that selects one agent for one inbound message.
 *
 * Set-valued conditions are stored as newline-separated values so the schema
 * stays normalised without extra tables; the repository is responsible for
 * parsing them, and the domain router is responsible for matching them.
 */
@Entity(
    tableName = "agent_routing_rules",
    foreignKeys = [
        ForeignKey(
            entity = RegisteredNumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["registered_number_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("registered_number_id"),
        Index("agent_id"),
        Index(value = ["registered_number_id", "priority", "id"])
    ]
)
data class AgentRoutingRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    /** Lower value wins. Equal priorities are broken by id ascending. */
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "user_type_keys") val userTypeKeys: String = "",
    @ColumnInfo(name = "keywords") val keywords: String = "",
    @ColumnInfo(name = "senders") val senders: String = "",
    @ColumnInfo(name = "conversation_tag") val conversationTag: String? = null,
    @ColumnInfo(name = "active_from_minute_of_day") val activeFromMinuteOfDay: Int? = null,
    @ColumnInfo(name = "active_until_minute_of_day") val activeUntilMinuteOfDay: Int? = null,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "policy_version") val policyVersion: Long = 1L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
