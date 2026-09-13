package com.securewa.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A configured AI provider endpoint: Gemini, OpenAI, Anthropic or an
 * OpenAI-compatible base URL.
 */
@Entity(
    tableName = "ai_providers",
    indices = [Index(value = ["provider_kind", "base_url"], unique = true)]
)
data class AiProviderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_kind") val providerKind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * A named slot holding one encrypted credential.
 *
 * `encrypted_credential` is produced by the credential vault (milestone 3) and
 * is opaque to this module. Several agents may reference the same slot; sharing
 * a slot shares authentication only, never prompts, memory, conversations or
 * configuration.
 */
@Entity(
    tableName = "credential_slots",
    foreignKeys = [
        ForeignKey(
            entity = AiProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("provider_id")]
)
data class CredentialSlotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String? = null,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "provider_kind") val providerKind: String,
    /** Ciphertext only. Never a plaintext key, token or passphrase. */
    @ColumnInfo(name = "encrypted_credential") val encryptedCredential: ByteArray? = null,
    /** Android Keystore alias of the key that sealed this slot. */
    @ColumnInfo(name = "key_alias") val keyAlias: String? = null,
    @ColumnInfo(name = "validation_state") val validationState: String = "UNCONFIGURED",
    @ColumnInfo(name = "last_validated_at") val lastValidatedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialSlotEntity) return false
        return id == other.id &&
            providerId == other.providerId &&
            label == other.label &&
            providerKind == other.providerKind &&
            (encryptedCredential == null && other.encryptedCredential == null ||
                encryptedCredential.contentEquals(other.encryptedCredential)) &&
            keyAlias == other.keyAlias &&
            validationState == other.validationState &&
            lastValidatedAt == other.lastValidatedAt &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (providerId?.hashCode() ?: 0)
        result = 31 * result + label.hashCode()
        result = 31 * result + providerKind.hashCode()
        result = 31 * result + (encryptedCredential?.contentHashCode() ?: 0)
        result = 31 * result + (keyAlias?.hashCode() ?: 0)
        result = 31 * result + validationState.hashCode()
        result = 31 * result + (lastValidatedAt?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/** A model offered by a provider, with the limits the UI and the safety layer enforce. */
@Entity(
    tableName = "model_configurations",
    foreignKeys = [
        ForeignKey(
            entity = AiProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["provider_id", "model_id"], unique = true)]
)
data class ModelConfigurationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "max_output_tokens") val maxOutputTokens: Int? = null,
    @ColumnInfo(name = "max_input_tokens") val maxInputTokens: Int? = null,
    @ColumnInfo(name = "supports_temperature") val supportsTemperature: Boolean = true,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
