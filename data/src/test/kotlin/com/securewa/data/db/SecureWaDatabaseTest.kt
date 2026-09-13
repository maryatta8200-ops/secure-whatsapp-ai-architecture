package com.securewa.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.securewa.data.db.entity.AgentEntity
import com.securewa.data.db.entity.AgentEventEntity
import com.securewa.data.db.entity.AgentRoutingRuleEntity
import com.securewa.data.db.entity.AppLogEntity
import com.securewa.data.db.entity.ConversationEntity
import com.securewa.data.db.entity.CredentialSlotEntity
import com.securewa.data.db.entity.InboundMessageReceiptEntity
import com.securewa.data.db.entity.MessageEntity
import com.securewa.data.db.entity.RegisteredNumberEntity
import com.securewa.data.db.entity.UserSettingsEntity
import com.securewa.data.db.entity.UserTypeHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the schema itself: constraints, cascade and set-null deletion
 * policies, idempotency keys and bounded reads.
 *
 * These rules are the isolation and retention guarantees of the product, so
 * they are asserted against a real SQLite database rather than assumed from the
 * annotations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SecureWaDatabaseTest {

    private lateinit var database: SecureWaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SecureWaDatabase::class.java
        )
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Room enables foreign keys, but the constraint behaviour is
                    // the thing under test, so it is made explicit.
                    db.execSQL("PRAGMA foreign_keys=ON")
                }
            })
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seedUserTypes() = runBlocking {
        database.numbersDao().upsertUserTypes(UserTypeSeed.entities())
    }

    private fun doctorNumber(
        id: String = "num-doctor",
        e164: String = "+15551000001",
        userTypeKey: String = "doctor"
    ) = RegisteredNumberEntity(
        id = id,
        displayName = "Dr Office",
        e164 = e164,
        countryCode = "US",
        userTypeKey = userTypeKey,
        isActive = true,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun agent(
        id: String = "agent-a1",
        numberId: String = "num-doctor",
        userTypeKey: String = "doctor",
        prompt: String = "You are a clinic assistant.",
        credentialSlotId: String? = null
    ) = AgentEntity(
        id = id,
        registeredNumberId = numberId,
        name = "Agent $id",
        description = null,
        userTypeKey = userTypeKey,
        systemPrompt = prompt,
        systemPromptDigest = prompt.hashCode().toString(),
        modelConfigurationId = "model-1",
        fallbackModelConfigurationId = null,
        credentialSlotId = credentialSlotId,
        temperatureMilli = 700,
        maxOutputTokens = 1024,
        isEnabled = true,
        revision = 1L,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    // --- user types and numbers ---------------------------------------------

    @Test
    fun `the three supported user types exist and are referenceable`() = runBlocking {
        seedUserTypes()
        val types = database.numbersDao().userTypes()
        assertEquals(3, types.size)
        assertEquals(listOf("common_user", "doctor", "patient"), types.map { it.storageKey })
        assertNotNull(database.numbersDao().userType("patient"))
    }

    @Test
    fun `a registered number can be read back by e164`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        val stored = database.numbersDao().numberByE164("+15551000001")
        assertNotNull(stored)
        assertEquals("doctor", stored!!.userTypeKey)
        assertEquals(1, database.numbersDao().activeNumbers().size)
    }

    @Test
    fun `the same number cannot be registered twice`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber(id = "num-1"))
        val duplicate = runCatching {
            database.numbersDao().insertNumber(doctorNumber(id = "num-2"))
        }
        assertTrue(
            "a duplicate E.164 registration must be rejected by the database",
            duplicate.isFailure && duplicate.exceptionOrNull() is SQLiteConstraintException
        )
    }

    @Test
    fun `an unknown user type is rejected by the foreign key`() = runBlocking {
        seedUserTypes()
        val invalid = runCatching {
            database.numbersDao().insertNumber(doctorNumber(userTypeKey = "nurse"))
        }
        assertTrue(
            "an invalid user type must not be storable: ${invalid.exceptionOrNull()}",
            invalid.isFailure
        )
        assertNull(database.numbersDao().numberByE164("+15551000001"))
    }

    @Test
    fun `changing a user type records history atomically`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.numbersDao().changeUserType(
            number = doctorNumber(userTypeKey = "patient").copy(updatedAt = 2_000L),
            history = UserTypeHistoryEntity(
                id = "hist-1",
                registeredNumberId = "num-doctor",
                fromTypeKey = "doctor",
                toTypeKey = "patient",
                changedAt = 2_000L,
                reason = "reception reclassified the line",
                correlationId = "corr-1"
            )
        )
        assertEquals("patient", database.numbersDao().numberById("num-doctor")!!.userTypeKey)
        val history = database.numbersDao().userTypeHistory("num-doctor")
        assertEquals(1, history.size)
        assertEquals("doctor", history[0].fromTypeKey)
        assertEquals("patient", history[0].toTypeKey)
    }

    // --- isolation and deletion policy --------------------------------------

    @Test
    fun `deleting an agent keeps its conversations and messages`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.agentsDao().insertAgent(agent())
        val conversation = ConversationEntity(
            id = "conv-1",
            registeredNumberId = "num-doctor",
            agentId = "agent-a1",
            counterpartyE164 = "+15557654321",
            channelKind = "WHATSAPP_TWILIO",
            lastMessageAt = 1_000L,
            createdAt = 1_000L
        )
        database.messagingDao().insertConversation(conversation)
        database.messagingDao().insertMessage(
            MessageEntity(
                id = "msg-1",
                conversationId = "conv-1",
                direction = "OUTBOUND",
                role = "ASSISTANT",
                content = "Please arrive ten minutes early.",
                createdAt = 1_000L
            )
        )

        database.agentsDao().deleteAgent("agent-a1")

        assertNull("the agent must be gone", database.agentsDao().agentById("agent-a1"))
        val surviving = database.messagingDao().conversationById("conv-1")
        assertNotNull("history must survive agent deletion", surviving)
        assertNull("the conversation is detached, not deleted", surviving!!.agentId)
        assertEquals(
            "messages must survive agent deletion",
            1,
            database.messagingDao().messageCount("conv-1")
        )
    }

    @Test
    fun `deleting a number removes its agents but keeps its conversations`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.agentsDao().insertAgent(agent())
        database.messagingDao().insertConversation(
            ConversationEntity(
                id = "conv-1",
                registeredNumberId = "num-doctor",
                agentId = "agent-a1",
                counterpartyE164 = "+15557654321",
                channelKind = "WHATSAPP_TWILIO",
                lastMessageAt = 1_000L,
                createdAt = 1_000L
            )
        )

        database.numbersDao().deleteNumber("num-doctor")

        assertTrue(
            "agents are configuration owned by the number",
            database.agentsDao().agentsForNumber("num-doctor").isEmpty()
        )
        val conversation = database.messagingDao().conversationById("conv-1")
        assertNotNull("conversation history must survive", conversation)
        assertNull(conversation!!.registeredNumberId)
    }

    @Test
    fun `two agents can share a credential slot without sharing anything else`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.providersDao().insertCredentialSlot(
            CredentialSlotEntity(
                id = "slot-shared",
                providerId = null,
                label = "shared gemini key",
                providerKind = "GEMINI",
                encryptedCredential = byteArrayOf(1, 2, 3),
                keyAlias = "alias",
                createdAt = 1_000L,
                updatedAt = 1_000L
            )
        )
        database.agentsDao().insertAgent(agent(id = "agent-a1", prompt = "You are agent one.", credentialSlotId = "slot-shared"))
        database.agentsDao().insertAgent(agent(id = "agent-a2", prompt = "You are agent two.", credentialSlotId = "slot-shared"))

        val shared = database.agentsDao().agentsUsingCredentialSlot("slot-shared")
        assertEquals(2, shared.size)
        assertEquals("You are agent one.", database.agentsDao().agentById("agent-a1")!!.systemPrompt)
        assertEquals("You are agent two.", database.agentsDao().agentById("agent-a2")!!.systemPrompt)
        assertEquals(1L, database.agentsDao().agentById("agent-a1")!!.revision)

        database.providersDao().deleteCredentialSlot("slot-shared")
        assertNull(
            "removing a shared slot detaches it, it does not delete the agents",
            database.agentsDao().agentById("agent-a1")!!.credentialSlotId
        )
        assertNotNull(database.agentsDao().agentById("agent-a2"))
    }

    @Test
    fun `routing rules are returned in evaluation order`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.agentsDao().insertAgent(agent(id = "agent-a1"))
        database.agentsDao().insertAgent(agent(id = "agent-a2"))
        listOf(
            AgentRoutingRuleEntity("rule-3", "num-doctor", "agent-a1", priority = 30, createdAt = 1L, updatedAt = 1L),
            AgentRoutingRuleEntity("rule-1", "num-doctor", "agent-a2", priority = 10, createdAt = 1L, updatedAt = 1L),
            AgentRoutingRuleEntity("rule-2", "num-doctor", "agent-a1", priority = 10, createdAt = 1L, updatedAt = 1L)
        ).forEach { database.agentsDao().insertRule(it) }

        assertEquals(
            listOf("rule-1", "rule-2", "rule-3"),
            database.agentsDao().rulesForNumber("num-doctor").map { it.id }
        )
    }

    // --- idempotency and delivery -------------------------------------------

    private fun receipt(
        id: String = "receipt-1",
        idempotencyKey: String = "key-1",
        state: String = "RECEIVED"
    ) = InboundMessageReceiptEntity(
        id = id,
        idempotencyKey = idempotencyKey,
        channelKind = "WHATSAPP_TWILIO",
        destinationE164 = "+15551000001",
        senderE164 = "+15557654321",
        externalMessageId = "SM00000000000000000000000000000001",
        body = "hello",
        signatureValid = true,
        state = state,
        receivedAt = 1_000L,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    @Test
    fun `the same inbound message cannot be stored twice`() = runBlocking {
        database.deliveryDao().insertReceipt(receipt())
        val duplicate = runCatching {
            database.deliveryDao().insertReceipt(receipt(id = "receipt-2"))
        }
        assertTrue(
            "a redelivered message must be rejected by the unique idempotency key",
            duplicate.isFailure && duplicate.exceptionOrNull() is SQLiteConstraintException
        )
        assertEquals("receipt-1", database.deliveryDao().receiptByIdempotencyKey("key-1")!!.id)
    }

    @Test
    fun `a receipt can only be claimed once`() = runBlocking {
        database.deliveryDao().insertReceipt(receipt())
        assertTrue(database.deliveryDao().tryClaimReceipt("receipt-1", now = 2_000L))
        assertFalse(
            "a second claim would duplicate the AI call and the outbound message",
            database.deliveryDao().tryClaimReceipt("receipt-1", now = 2_001L)
        )
        assertEquals("PROCESSING", database.deliveryDao().receiptById("receipt-1")!!.state)
        assertEquals(1, database.deliveryDao().receiptById("receipt-1")!!.attempts)
    }

    @Test
    fun `a receipt scheduled for later is not claimable yet`() = runBlocking {
        database.deliveryDao().insertReceipt(receipt())
        database.deliveryDao().updateReceiptState(
            id = "receipt-1",
            state = "RECEIVED",
            attempts = 1,
            nextAttemptAt = 5_000L,
            correlationId = null,
            lastErrorRedacted = "provider timeout",
            updatedAt = 2_000L
        )
        assertFalse(database.deliveryDao().tryClaimReceipt("receipt-1", now = 3_000L))
        assertEquals(0, database.deliveryDao().claimableReceipts(now = 3_000L, limit = 10).size)
        assertEquals(1, database.deliveryDao().claimableReceipts(now = 5_000L, limit = 10).size)
    }

    @Test
    fun `an unacknowledged receipt is visible as pending work`() = runBlocking {
        database.deliveryDao().insertReceipt(receipt())
        assertEquals(1, database.deliveryDao().pendingReceiptCount())
        assertEquals(1_000L, database.deliveryDao().oldestPendingReceivedAt())
    }

    // --- bounded reads and retention ----------------------------------------

    @Test
    fun `message reads are bounded by a window`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.agentsDao().insertAgent(agent())
        database.messagingDao().insertConversation(
            ConversationEntity(
                id = "conv-1",
                registeredNumberId = "num-doctor",
                agentId = "agent-a1",
                counterpartyE164 = "+15557654321",
                channelKind = "WHATSAPP_TWILIO",
                lastMessageAt = 25L,
                createdAt = 1L
            )
        )
        repeat(25) { index ->
            database.messagingDao().insertMessage(
                MessageEntity(
                    id = "msg-$index",
                    conversationId = "conv-1",
                    direction = "INBOUND",
                    role = "USER",
                    content = "message $index",
                    createdAt = index.toLong()
                )
            )
        }
        assertEquals(25, database.messagingDao().messageCount("conv-1"))
        assertEquals(10, database.messagingDao().latestMessages("conv-1", 10).size)
        assertEquals(
            "the window must return the newest messages",
            "message 24",
            database.messagingDao().latestMessages("conv-1", 10).first().content
        )
        assertEquals(5, database.messagingDao().messagePage("conv-1", limit = 5, offset = 20).size)
    }

    @Test
    fun `log retention removes only rows older than the window`() = runBlocking {
        repeat(5) { index ->
            database.auditDao().insertLog(
                AppLogEntity(timestamp = index.toLong(), level = "INFO", tag = "Test", messageRedacted = "entry $index")
            )
        }
        assertEquals(2, database.auditDao().trimLogsBefore(cutoff = 3L))
        assertEquals(3, database.auditDao().logCount())
    }

    @Test
    fun `audit events survive the deletion of the agent they describe`() = runBlocking {
        seedUserTypes()
        database.numbersDao().insertNumber(doctorNumber())
        database.agentsDao().insertAgent(agent())
        database.auditDao().insertAgentEvent(
            AgentEventEntity(
                id = "event-1",
                agentId = "agent-a1",
                registeredNumberId = "num-doctor",
                type = "AGENT_TESTED",
                correlationId = "corr-1",
                detailRedacted = "outcome=connected",
                createdAt = 1_000L
            )
        )
        database.agentsDao().deleteAgent("agent-a1")
        val events = database.auditDao().eventsForCorrelation("corr-1")
        assertEquals(1, events.size)
        assertNull("the event is detached, not deleted", events[0].agentId)
    }

    // --- settings -----------------------------------------------------------

    @Test
    fun `settings are a single row`() = runBlocking {
        database.numbersDao().upsertSettings(
            UserSettingsEntity(createdAt = 1L, updatedAt = 1L)
        )
        database.numbersDao().upsertSettings(
            UserSettingsEntity(defaultRegion = "GB", logRetentionHours = 24, createdAt = 1L, updatedAt = 2L)
        )
        val settings = database.numbersDao().settings()
        assertEquals("GB", settings!!.defaultRegion)
        assertEquals(24, settings.logRetentionHours)
    }
}
