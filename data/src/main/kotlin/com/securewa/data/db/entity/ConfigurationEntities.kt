package com.securewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single-row application settings table. `id` is always `1`; the row is created
 * on first launch and updated in place.
 */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Long = 1L,
    /** ISO 3166-1 alpha-2 region used to interpret national-format numbers. */
    @ColumnInfo(name = "default_region") val defaultRegion: String = "PK",
    @ColumnInfo(name = "receiver_poll_interval_seconds") val receiverPollIntervalSeconds: Int = 300,
    @ColumnInfo(name = "log_retention_hours") val logRetentionHours: Int = 72,
    @ColumnInfo(name = "telemetry_enabled") val telemetryEnabled: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * Reference table of the supported user types. Seeded from the domain enum so
 * the database can enforce the valid values with a foreign key instead of a
 * check constraint that SQLite cannot express.
 */
@Entity(tableName = "user_types")
data class UserTypeEntity(
    // Note: @PrimaryKey(name = ...) names the primary key *index*, not the
    // column. The column name comes from @ColumnInfo, which is what the foreign
    // keys and queries reference.
    @PrimaryKey
    @ColumnInfo(name = "storage_key")
    val storageKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "default_retention_days") val defaultRetentionDays: Int,
    @ColumnInfo(name = "sensitivity") val sensitivity: String
)

/**
 * Every change of a number's user type is recorded, because the user type
 * governs routing, safety and retention: a silent change would make past
 * routing decisions unexplainable.
 */
@Entity(
    tableName = "user_type_history",
    foreignKeys = [
        ForeignKey(
            entity = RegisteredNumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["registered_number_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserTypeEntity::class,
            parentColumns = ["storage_key"],
            childColumns = ["to_type_key"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("registered_number_id"), Index("changed_at")]
)
data class UserTypeHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String,
    @ColumnInfo(name = "from_type_key") val fromTypeKey: String?,
    @ColumnInfo(name = "to_type_key") val toTypeKey: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    /** Why the type changed, as supplied by the user or the system. */
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "correlation_id") val correlationId: String?
)

/**
 * A number registered by the user. Numbers are the tenant boundary of the
 * whole system: agents, conversations, routing rules and credentials hang off
 * them, and nothing is shared across them unless the user configures it.
 */
@Entity(
    tableName = "registered_numbers",
    foreignKeys = [
        ForeignKey(
            entity = UserTypeEntity::class,
            parentColumns = ["storage_key"],
            childColumns = ["user_type_key"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        // One active registration per E.164 number. Disabling a number keeps
        // its configuration; the uniqueness of the stored E.164 is the guard
        // against double registration.
        Index(value = ["e164"], unique = true),
        Index("user_type_key")
    ]
)
data class RegisteredNumberEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "e164") val e164: String,
    @ColumnInfo(name = "country_code") val countryCode: String,
    @ColumnInfo(name = "user_type_key") val userTypeKey: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * Twilio credentials and endpoint configuration for one number.
 *
 * The auth token itself is never stored here: [credentialSlotId] points at an
 * encrypted slot in `credential_slots`.
 */
@Entity(
    tableName = "twilio_configurations",
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
        )
    ],
    indices = [Index("registered_number_id"), Index("credential_slot_id")]
)
data class TwilioConfigurationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String,
    @ColumnInfo(name = "account_sid") val accountSid: String,
    @ColumnInfo(name = "credential_slot_id") val credentialSlotId: String? = null,
    @ColumnInfo(name = "api_base_url") val apiBaseUrl: String = "https://api.twilio.com/2010-04-01",
    @ColumnInfo(name = "status_callback_url") val statusCallbackUrl: String? = null,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * One messaging channel (WhatsApp via Twilio, SMS via Twilio, ...) on one
 * number, including where inbound messages for it are received from.
 */
@Entity(
    tableName = "messaging_channel_configurations",
    foreignKeys = [
        ForeignKey(
            entity = RegisteredNumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["registered_number_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TwilioConfigurationEntity::class,
            parentColumns = ["id"],
            childColumns = ["twilio_configuration_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CredentialSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiver_credential_slot_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["registered_number_id", "channel_kind"], unique = true),
        Index("twilio_configuration_id")
    ]
)
data class MessagingChannelConfigurationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "registered_number_id") val registeredNumberId: String,
    @ColumnInfo(name = "channel_kind") val channelKind: String,
    /** The Twilio sender, for example `whatsapp:+15557654321`. */
    @ColumnInfo(name = "sender_address") val senderAddress: String,
    /** Public HTTPS base URL of the documented inbound receiver. */
    @ColumnInfo(name = "receiver_base_url") val receiverBaseUrl: String? = null,
    @ColumnInfo(name = "receiver_credential_slot_id") val receiverCredentialSlotId: String? = null,
    @ColumnInfo(name = "twilio_configuration_id") val twilioConfigurationId: String? = null,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
