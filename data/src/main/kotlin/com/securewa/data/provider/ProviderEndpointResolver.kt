package com.securewa.data.provider

import com.securewa.core.provider.EndpointPolicy
import com.securewa.core.routing.ProviderKind
import com.securewa.core.routing.ProviderRef
import com.securewa.core.security.AeadCipher
import com.securewa.data.db.dao.ProvidersDao
import com.securewa.data.vault.VaultRepository
import java.security.GeneralSecurityException

/**
 * Why a [ProviderRef] could not be turned into something callable.
 *
 * These are distinct on purpose. Collapsing them into one "unavailable" would
 * hide the difference between a slot the user has not filled in yet and a vault
 * that is locked, and those two need different answers from the user.
 */
sealed interface EndpointResolution {

    val ref: ProviderRef

    /** Human readable and redacted. Empty for [Ready]. */
    val detail: String

    data class Ready(override val ref: ProviderRef, val endpoint: ProviderEndpoint) : EndpointResolution {
        override val detail: String get() = ""
    }

    /** The credential slot this agent points at has been deleted. */
    data class SlotMissing(override val ref: ProviderRef) : EndpointResolution {
        override val detail: String get() = "credential slot ${ref.credentialSlotId} no longer exists"
    }

    /** The slot exists but holds no credential yet. */
    data class NoCredential(override val ref: ProviderRef) : EndpointResolution {
        override val detail: String get() = "no credential is stored in slot ${ref.credentialSlotId}"
    }

    data class VaultLocked(override val ref: ProviderRef) : EndpointResolution {
        override val detail: String
            get() = "the vault is locked, so the credential in slot ${ref.credentialSlotId} cannot be opened"
    }

    /** Stored bytes that are not a credential this key can open. */
    data class CorruptCredential(override val ref: ProviderRef) : EndpointResolution {
        override val detail: String get() = "the credential in slot ${ref.credentialSlotId} cannot be opened"
    }

    data class ProviderMissing(override val ref: ProviderRef) : EndpointResolution {
        override val detail: String get() = "the provider for slot ${ref.credentialSlotId} no longer exists"
    }

    data class ProviderDisabled(override val ref: ProviderRef) : EndpointResolution {
        override val detail: String get() = "the provider for slot ${ref.credentialSlotId} is switched off"
    }

    /** A base URL no credential may be sent to, per [EndpointPolicy]. */
    data class InsecureEndpoint(override val ref: ProviderRef, val baseUrl: String) : EndpointResolution {
        override val detail: String get() = "no credential may be sent to $baseUrl"
    }

    /**
     * The slot or the provider row is for a different provider family than the
     * agent's configuration says. Calling anyway would send a key belonging to
     * one organisation to another's endpoint.
     */
    data class KindMismatch(
        override val ref: ProviderRef,
        val expected: ProviderKind,
        val stored: String
    ) : EndpointResolution {
        override val detail: String
            get() = "slot ${ref.credentialSlotId} holds a $stored credential, not a $expected one"
    }
}

/** Supplies callable endpoints. The runner depends on this, so tests can too. */
interface EndpointResolver {
    suspend fun resolve(ref: ProviderRef): EndpointResolution
}

/**
 * Turns a [ProviderRef] into a [ProviderEndpoint].
 *
 * A reference names a credential slot; it holds no secret, which is why routing
 * can pass it around freely. The key is read from the vault at the moment of the
 * call and handed straight to the adapter, which puts it in a header and drops
 * it. It is never written to the database, never logged, and never part of the
 * reference the router sees.
 *
 * Several agents may share one slot. Sharing a slot shares authentication only:
 * prompts, history and configuration stay per agent.
 */
class ProviderEndpointResolver(
    private val providers: ProvidersDao,
    private val vault: VaultRepository
) : EndpointResolver {

    override suspend fun resolve(ref: ProviderRef): EndpointResolution {
        val slot = providers.credentialSlotById(ref.credentialSlotId)
            ?: return EndpointResolution.SlotMissing(ref)

        if (providerKindOf(slot.providerKind) != ref.providerKind) {
            return EndpointResolution.KindMismatch(ref, ref.providerKind, slot.providerKind)
        }

        val provider = slot.providerId?.let { providers.providerById(it) }
            ?: return EndpointResolution.ProviderMissing(ref)
        if (!provider.isEnabled) return EndpointResolution.ProviderDisabled(ref)
        if (providerKindOf(provider.providerKind) != ref.providerKind) {
            return EndpointResolution.KindMismatch(ref, ref.providerKind, provider.providerKind)
        }

        val urlProblem = EndpointPolicy.problemWith(provider.baseUrl)
        if (urlProblem != null) return EndpointResolution.InsecureEndpoint(ref, provider.baseUrl)

        val storedCredential = slot.encryptedCredential
            ?: return EndpointResolution.NoCredential(ref)
        if (!vault.isUnlocked) return EndpointResolution.VaultLocked(ref)

        val sealed = try {
            AeadCipher.decode(storedCredential)
        } catch (malformed: IllegalArgumentException) {
            return EndpointResolution.CorruptCredential(ref)
        }

        val keyBytes = try {
            vault.openCredential(slot.id, sealed)
        } catch (locked: IllegalStateException) {
            // The check above covers the normal case; this covers a vault locked
            // by another thread between there and here.
            return EndpointResolution.VaultLocked(ref)
        } catch (corrupt: GeneralSecurityException) {
            return EndpointResolution.CorruptCredential(ref)
        }

        return EndpointResolution.Ready(
            ref,
            ProviderEndpoint(
                kind = ref.providerKind,
                baseUrl = provider.baseUrl.trim().trimEnd('/'),
                // OkHttp only accepts header values as strings, so the key has to
                // become one for the length of a single call. Nothing here keeps
                // it: it is never persisted, never logged and never returned as
                // part of a ProviderRef.
                apiKey = keyBytes.toString(Charsets.UTF_8)
            )
        )
    }

    private fun providerKindOf(stored: String): ProviderKind? =
        ProviderKind.entries.firstOrNull { it.name == stored }
}
