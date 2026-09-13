package com.securewa.data.provider

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.securewa.core.routing.ProviderKind
import com.securewa.core.routing.ProviderRef
import com.securewa.core.security.AeadCipher
import com.securewa.data.db.SecureWaDatabase
import com.securewa.data.db.entity.AiProviderEntity
import com.securewa.data.db.entity.CredentialSlotEntity
import com.securewa.data.vault.PlatformSealer
import com.securewa.data.vault.VaultRepository
import com.securewa.data.vault.VaultStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Credentials reach the adapters through this class, so it is tested against a
 * real database and a real vault rather than a fake: the point is that the key
 * comes out of the encrypted slot, for the right provider, over a URL a key may
 * be sent to.
 *
 * Only the Android Keystore is stood in for, exactly as the vault tests do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ProviderEndpointResolverTest {

    private val passphrase = "a sufficiently long passphrase".toCharArray()
    // 100_000 iterations is the minimum this project accepts; the derivation is
    // not what is under test here and the faster setting keeps the suite quick.
    private val testIterations = 100_000

    private lateinit var database: SecureWaDatabase
    private lateinit var vault: VaultRepository
    private lateinit var resolver: ProviderEndpointResolver

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SecureWaDatabase::class.java
        ).allowMainThreadQueries().build()

        vault = VaultRepository(
            storage = InMemoryVaultStorage(),
            platformSealer = FakePlatformSealer(),
            iterations = testIterations
        )
        vault.create(passphrase)

        resolver = ProviderEndpointResolver(database.providersDao(), vault)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class InMemoryVaultStorage : VaultStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes
        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }
        override fun clear() {
            bytes = null
        }
    }

    private class FakePlatformSealer(private val key: ByteArray = ByteArray(32) { 7 }) : PlatformSealer {
        override fun seal(plaintext: ByteArray): ByteArray {
            val sealed = AeadCipher.seal(key, plaintext)
            return sealed.nonce + sealed.ciphertext
        }

        override fun open(sealed: ByteArray): ByteArray = AeadCipher.open(
            key,
            AeadCipher.Sealed(
                nonce = sealed.copyOfRange(0, AeadCipher.NONCE_BYTES),
                ciphertext = sealed.copyOfRange(AeadCipher.NONCE_BYTES, sealed.size)
            )
        )
    }

    private suspend fun givenProvider(
        id: String = "provider-openai",
        kind: ProviderKind = ProviderKind.OPENAI,
        baseUrl: String = "https://api.openai.com/v1",
        enabled: Boolean = true
    ): String {
        database.providersDao().insertProvider(
            AiProviderEntity(
                id = id,
                providerKind = kind.name,
                displayName = "Test provider",
                baseUrl = baseUrl,
                isEnabled = enabled,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        return id
    }

    private suspend fun givenSlot(
        id: String,
        providerId: String?,
        kind: ProviderKind = ProviderKind.OPENAI,
        credential: ByteArray? = null
    ) {
        database.providersDao().insertCredentialSlot(
            CredentialSlotEntity(
                id = id,
                providerId = providerId,
                label = "slot $id",
                providerKind = kind.name,
                encryptedCredential = credential,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
    }

    private fun sealedFor(slotId: String, key: String): ByteArray =
        AeadCipher.encode(vault.sealCredential(slotId, key.toByteArray()))

    private fun ref(slotId: String = "slot-openai", kind: ProviderKind = ProviderKind.OPENAI) =
        ProviderRef(providerKind = kind, modelId = "a-model", credentialSlotId = slotId, configurationRevision = 1)

    @Test
    fun `a slot that holds a credential resolves to something callable`() = runBlocking {
        val providerId = givenProvider()
        givenSlot("slot-openai", providerId, credential = sealedFor("slot-openai", "sk-a-test-key"))

        val resolution = resolver.resolve(ref())

        val ready = resolution as EndpointResolution.Ready
        assertEquals("https://api.openai.com/v1", ready.endpoint.baseUrl)
        assertEquals(ProviderKind.OPENAI, ready.endpoint.kind)
        assertEquals("sk-a-test-key", ready.endpoint.apiKey)
    }

    @Test
    fun `a slot with no credential yet is reported as one`() = runBlocking {
        val providerId = givenProvider()
        givenSlot("slot-openai", providerId, credential = null)

        assertTrue(resolver.resolve(ref()) is EndpointResolution.NoCredential)
    }

    @Test
    fun `a locked vault keeps every credential closed`() = runBlocking {
        val providerId = givenProvider()
        givenSlot("slot-openai", providerId, credential = sealedFor("slot-openai", "sk-a-test-key"))
        vault.lock()

        assertTrue(resolver.resolve(ref()) is EndpointResolution.VaultLocked)
    }

    @Test
    fun `a credential is bound to its own slot`() = runBlocking {
        val providerId = givenProvider()
        givenSlot("slot-a", providerId, credential = sealedFor("slot-a", "sk-a-test-key"))
        givenSlot("slot-b", providerId, credential = sealedFor("slot-a", "sk-a-test-key"))

        assertTrue("a credential sealed for one slot must not open as another",
            resolver.resolve(ref("slot-b")) is EndpointResolution.CorruptCredential)
    }

    @Test
    fun `a slot holding another provider family's credential is refused`() = runBlocking {
        val providerId = givenProvider()
        givenSlot("slot-openai", providerId, kind = ProviderKind.GEMINI, credential = sealedFor("slot-openai", "sk-a-test-key"))

        val resolution = resolver.resolve(ref())
        assertTrue("a key must never cross provider families", resolution is EndpointResolution.KindMismatch)
    }

    @Test
    fun `a credential is never sent over plain http to another host`() = runBlocking {
        val providerId = givenProvider(baseUrl = "http://api.example.com/v1")
        givenSlot("slot-openai", providerId, credential = sealedFor("slot-openai", "sk-a-test-key"))

        assertTrue(resolver.resolve(ref()) is EndpointResolution.InsecureEndpoint)
    }

    @Test
    fun `a provider that is switched off is not called`() = runBlocking {
        val providerId = givenProvider(enabled = false)
        givenSlot("slot-openai", providerId, credential = sealedFor("slot-openai", "sk-a-test-key"))

        assertTrue(resolver.resolve(ref()) is EndpointResolution.ProviderDisabled)
    }

    @Test
    fun `a slot whose provider row is gone is reported as such`() = runBlocking {
        givenSlot("slot-orphan", null, credential = sealedFor("slot-orphan", "sk-a-test-key"))

        assertTrue(resolver.resolve(ref("slot-orphan")) is EndpointResolution.ProviderMissing)
    }

    @Test
    fun `a slot that no longer exists is reported as such`() = runBlocking {
        assertTrue(resolver.resolve(ref("slot-deleted")) is EndpointResolution.SlotMissing)
    }
}
