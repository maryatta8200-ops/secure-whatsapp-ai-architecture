package com.securewa.data.twilio

import com.securewa.core.provider.EndpointPolicy
import com.securewa.data.db.dao.NumbersDao
import com.securewa.data.db.dao.ProvidersDao
import com.securewa.data.vault.VaultRepository
import com.securewa.core.security.AeadCipher
import java.security.GeneralSecurityException

/**
 * Why a Twilio configuration could not be used.
 *
 * Separate states, for the same reason as the provider resolver: "the vault is
 * locked" and "you have not entered an auth token yet" need different answers
 * from the user, and collapsing them would hide that.
 */
sealed interface TwilioCredentialResolution {

    val configurationId: String

    /** Human readable and redacted. Empty for [Ready]. */
    val detail: String

    data class Ready(
        override val configurationId: String,
        val accountSid: String,
        /** In memory only, for one call. Never persisted outside the vault. */
        val authToken: String,
        val apiBaseUrl: String,
        val statusCallbackUrl: String?
    ) : TwilioCredentialResolution {
        override val detail: String get() = ""
    }

    data class ConfigurationMissing(override val configurationId: String) : TwilioCredentialResolution {
        override val detail: String get() = "no Twilio configuration exists for this number"
    }

    data class Disabled(override val configurationId: String) : TwilioCredentialResolution {
        override val detail: String get() = "this Twilio configuration is switched off"
    }

    data class NoCredential(override val configurationId: String) : TwilioCredentialResolution {
        override val detail: String get() = "no auth token is stored in this configuration's credential slot"
    }

    data class VaultLocked(override val configurationId: String) : TwilioCredentialResolution {
        override val detail: String get() = "the vault is locked, so the auth token cannot be opened"
    }

    data class CorruptCredential(override val configurationId: String) : TwilioCredentialResolution {
        override val detail: String get() = "the stored auth token cannot be opened"
    }

    data class InsecureEndpoint(
        override val configurationId: String,
        val baseUrl: String
    ) : TwilioCredentialResolution {
        override val detail: String get() = "no credential may be sent to $baseUrl"
    }
}

interface TwilioCredentialResolver {
    suspend fun resolve(configurationId: String): TwilioCredentialResolution
}

/**
 * Reads the auth token out of the vault for one Twilio configuration.
 *
 * The account SID is an identifier and lives in the database row. The auth token
 * is a secret and lives only in an encrypted credential slot, so the database
 * never holds it and a database export cannot leak it.
 */
class VaultTwilioCredentialResolver(
    private val numbers: NumbersDao,
    private val providers: ProvidersDao,
    private val vault: VaultRepository
) : TwilioCredentialResolver {

    override suspend fun resolve(configurationId: String): TwilioCredentialResolution {
        val configuration = numbers.twilioConfigurationById(configurationId)
            ?: return TwilioCredentialResolution.ConfigurationMissing(configurationId)
        if (!configuration.isEnabled) {
            return TwilioCredentialResolution.Disabled(configurationId)
        }

        val urlProblem = EndpointPolicy.problemWith(configuration.apiBaseUrl)
        if (urlProblem != null) {
            return TwilioCredentialResolution.InsecureEndpoint(configurationId, configuration.apiBaseUrl)
        }

        val slotId = configuration.credentialSlotId
            ?: return TwilioCredentialResolution.NoCredential(configurationId)
        val slot = providers.credentialSlotById(slotId)
            ?: return TwilioCredentialResolution.NoCredential(configurationId)
        val storedToken = slot.encryptedCredential
            ?: return TwilioCredentialResolution.NoCredential(configurationId)
        if (!vault.isUnlocked) {
            return TwilioCredentialResolution.VaultLocked(configurationId)
        }

        val sealed = try {
            AeadCipher.decode(storedToken)
        } catch (malformed: IllegalArgumentException) {
            return TwilioCredentialResolution.CorruptCredential(configurationId)
        }

        val tokenBytes = try {
            vault.openCredential(slot.id, sealed)
        } catch (locked: IllegalStateException) {
            return TwilioCredentialResolution.VaultLocked(configurationId)
        } catch (corrupt: GeneralSecurityException) {
            return TwilioCredentialResolution.CorruptCredential(configurationId)
        }

        return TwilioCredentialResolution.Ready(
            configurationId = configurationId,
            accountSid = configuration.accountSid,
            // Basic auth takes a String, so the token has to become one for the
            // length of a single call, exactly as the provider key does.
            authToken = tokenBytes.toString(Charsets.UTF_8),
            apiBaseUrl = configuration.apiBaseUrl.trim().trimEnd('/'),
            statusCallbackUrl = configuration.statusCallbackUrl?.trim()?.takeIf { it.isNotEmpty() }
        )
    }
}
