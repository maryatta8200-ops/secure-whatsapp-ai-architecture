package com.securewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.securewa.data.db.entity.MessagingChannelConfigurationEntity
import com.securewa.data.db.entity.RegisteredNumberEntity
import com.securewa.data.db.entity.TwilioConfigurationEntity
import com.securewa.data.db.entity.UserSettingsEntity
import com.securewa.data.db.entity.UserTypeEntity
import com.securewa.data.db.entity.UserTypeHistoryEntity
import kotlinx.coroutines.flow.Flow

/** Numbers, their user types, their history and their channel configuration. */
@Dao
interface NumbersDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNumber(number: RegisteredNumberEntity)

    @Update
    suspend fun updateNumber(number: RegisteredNumberEntity)

    @Query("DELETE FROM registered_numbers WHERE id = :id")
    suspend fun deleteNumber(id: String)

    @Query("SELECT * FROM registered_numbers WHERE id = :id")
    suspend fun numberById(id: String): RegisteredNumberEntity?

    @Query("SELECT * FROM registered_numbers WHERE e164 = :e164")
    suspend fun numberByE164(e164: String): RegisteredNumberEntity?

    @Query("SELECT * FROM registered_numbers ORDER BY display_name")
    fun observeNumbers(): Flow<List<RegisteredNumberEntity>>

    @Query("SELECT * FROM registered_numbers WHERE is_active = 1 ORDER BY display_name")
    suspend fun activeNumbers(): List<RegisteredNumberEntity>

    // --- user types ---------------------------------------------------------

    @Upsert
    suspend fun upsertUserTypes(types: List<UserTypeEntity>)

    @Query("SELECT * FROM user_types ORDER BY storage_key")
    suspend fun userTypes(): List<UserTypeEntity>

    @Query("SELECT * FROM user_types WHERE storage_key = :storageKey")
    suspend fun userType(storageKey: String): UserTypeEntity?

    // --- user type history --------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUserTypeHistory(entry: UserTypeHistoryEntity)

    @Query(
        "SELECT * FROM user_type_history WHERE registered_number_id = :numberId " +
            "ORDER BY changed_at DESC, id DESC"
    )
    suspend fun userTypeHistory(numberId: String): List<UserTypeHistoryEntity>

    /**
     * Changes the user type of a number and records the change, atomically.
     * The history row is what makes a later routing decision explainable, so
     * the two writes must never be separable.
     */
    @Transaction
    suspend fun changeUserType(number: RegisteredNumberEntity, history: UserTypeHistoryEntity) {
        updateNumber(number)
        insertUserTypeHistory(history)
    }

    // --- twilio and channel configuration -----------------------------------

    @Upsert
    suspend fun upsertTwilioConfiguration(configuration: TwilioConfigurationEntity)

    @Query("SELECT * FROM twilio_configurations WHERE registered_number_id = :numberId")
    suspend fun twilioConfiguration(numberId: String): TwilioConfigurationEntity?

    @Query("DELETE FROM twilio_configurations WHERE id = :id")
    suspend fun deleteTwilioConfiguration(id: String)

    @Upsert
    suspend fun upsertChannelConfiguration(configuration: MessagingChannelConfigurationEntity)

    @Query("SELECT * FROM messaging_channel_configurations WHERE registered_number_id = :numberId")
    suspend fun channelConfigurations(numberId: String): List<MessagingChannelConfigurationEntity>

    @Query(
        "SELECT * FROM messaging_channel_configurations " +
            "WHERE registered_number_id = :numberId AND channel_kind = :channelKind"
    )
    suspend fun channelConfiguration(numberId: String, channelKind: String): MessagingChannelConfigurationEntity?

    @Query("DELETE FROM messaging_channel_configurations WHERE id = :id")
    suspend fun deleteChannelConfiguration(id: String)

    // --- settings -----------------------------------------------------------

    @Upsert
    suspend fun upsertSettings(settings: UserSettingsEntity)

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun settings(): UserSettingsEntity?
}
