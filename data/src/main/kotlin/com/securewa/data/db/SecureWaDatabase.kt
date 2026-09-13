package com.securewa.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.securewa.data.db.dao.AgentsDao
import com.securewa.data.db.dao.AuditDao
import com.securewa.data.db.dao.DeliveryDao
import com.securewa.data.db.dao.MessagingDao
import com.securewa.data.db.dao.NumbersDao
import com.securewa.data.db.dao.ProvidersDao
import com.securewa.data.db.entity.AgentEntity
import com.securewa.data.db.entity.AgentEventEntity
import com.securewa.data.db.entity.AgentRoutingRuleEntity
import com.securewa.data.db.entity.AiProviderEntity
import com.securewa.data.db.entity.AppLogEntity
import com.securewa.data.db.entity.ConversationEntity
import com.securewa.data.db.entity.CredentialSlotEntity
import com.securewa.data.db.entity.InboundMessageReceiptEntity
import com.securewa.data.db.entity.MessagingChannelConfigurationEntity
import com.securewa.data.db.entity.MessageEntity
import com.securewa.data.db.entity.ModelConfigurationEntity
import com.securewa.data.db.entity.OutboundMessageAttemptEntity
import com.securewa.data.db.entity.RegisteredNumberEntity
import com.securewa.data.db.entity.TwilioConfigurationEntity
import com.securewa.data.db.entity.UserSettingsEntity
import com.securewa.data.db.entity.UserTypeEntity
import com.securewa.data.db.entity.UserTypeHistoryEntity

/**
 * Local persistence for the whole application.
 *
 * Design rules encoded in the schema rather than in code:
 *  - a number is the tenant boundary, and its E.164 is unique;
 *  - user types are a reference table, so the database rejects an invalid type
 *    through a foreign key instead of a convention;
 *  - deleting configuration (a number, an agent, a provider) never deletes
 *    history: conversations and messages are detached with SET_NULL, and audit
 *    rows survive, because "delete the agent" must not mean "erase the record
 *    of what it said";
 *  - inbound receipts and outbound attempts carry unique idempotency keys, so
 *    redelivery and retry cannot duplicate work.
 *
 * Schema exports for every version are written to `data/schemas`, which is what
 * makes each migration reviewable.
 */
@Database(
    entities = [
        UserSettingsEntity::class,
        UserTypeEntity::class,
        UserTypeHistoryEntity::class,
        RegisteredNumberEntity::class,
        TwilioConfigurationEntity::class,
        MessagingChannelConfigurationEntity::class,
        AiProviderEntity::class,
        CredentialSlotEntity::class,
        ModelConfigurationEntity::class,
        AgentEntity::class,
        AgentRoutingRuleEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        InboundMessageReceiptEntity::class,
        OutboundMessageAttemptEntity::class,
        AgentEventEntity::class,
        AppLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SecureWaDatabase : RoomDatabase() {
    abstract fun numbersDao(): NumbersDao
    abstract fun agentsDao(): AgentsDao
    abstract fun providersDao(): ProvidersDao
    abstract fun messagingDao(): MessagingDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val DATABASE_NAME = "secure-whatsapp-ai.db"
    }
}
