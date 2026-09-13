package com.securewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.securewa.data.db.entity.ConversationEntity
import com.securewa.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Conversations and messages.
 *
 * Every read is bounded: the UI and the pipeline both ask for a window of
 * messages rather than the whole history, which is what keeps memory and CPU
 * bounded on a device that may hold thousands of messages per number.
 */
@Dao
interface MessagingDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversationById(id: String): ConversationEntity?

    @Query(
        "SELECT * FROM conversations WHERE registered_number_id = :numberId " +
            "AND counterparty_e164 = :counterparty AND channel_kind = :channelKind " +
            "ORDER BY last_message_at DESC LIMIT 1"
    )
    suspend fun openConversation(
        numberId: String,
        counterparty: String,
        channelKind: String
    ): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE registered_number_id = :numberId ORDER BY last_message_at DESC")
    fun observeConversations(numberId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE agent_id = :agentId ORDER BY last_message_at DESC")
    suspend fun conversationsForAgent(agentId: String): List<ConversationEntity>

    // --- messages -----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    /**
     * The most recent [limit] messages of one conversation, oldest first, which
     * is the shape the provider request needs.
     */
    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
    )
    suspend fun latestMessages(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun messagePage(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE external_id = :externalId")
    suspend fun messageByExternalId(externalId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int
}
