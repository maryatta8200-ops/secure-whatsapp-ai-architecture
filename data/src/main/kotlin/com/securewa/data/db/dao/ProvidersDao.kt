package com.securewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.securewa.data.db.entity.AiProviderEntity
import com.securewa.data.db.entity.CredentialSlotEntity
import com.securewa.data.db.entity.ModelConfigurationEntity
import kotlinx.coroutines.flow.Flow

/** Providers, their models and the encrypted credential slots. */
@Dao
interface ProvidersDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProvider(provider: AiProviderEntity)

    @Update
    suspend fun updateProvider(provider: AiProviderEntity)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("SELECT * FROM ai_providers ORDER BY display_name")
    fun observeProviders(): Flow<List<AiProviderEntity>>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun providerById(id: String): AiProviderEntity?

    // --- models -------------------------------------------------------------

    @Upsert
    suspend fun upsertModel(model: ModelConfigurationEntity)

    @Query("SELECT * FROM model_configurations WHERE provider_id = :providerId ORDER BY display_name")
    suspend fun modelsForProvider(providerId: String): List<ModelConfigurationEntity>

    @Query("SELECT * FROM model_configurations WHERE id = :id")
    suspend fun modelById(id: String): ModelConfigurationEntity?

    // --- credential slots ---------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCredentialSlot(slot: CredentialSlotEntity)

    @Update
    suspend fun updateCredentialSlot(slot: CredentialSlotEntity)

    @Query("DELETE FROM credential_slots WHERE id = :id")
    suspend fun deleteCredentialSlot(id: String)

    @Query("SELECT * FROM credential_slots ORDER BY label")
    fun observeCredentialSlots(): Flow<List<CredentialSlotEntity>>

    @Query("SELECT * FROM credential_slots WHERE id = :id")
    suspend fun credentialSlotById(id: String): CredentialSlotEntity?

    /**
     * Records the outcome of a real validation call. Only an authenticated
     * check may set a slot to a connected state; nothing here infers success.
     */
    @Query(
        "UPDATE credential_slots SET validation_state = :state, last_validated_at = :validatedAt, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun recordValidation(id: String, state: String, validatedAt: Long?, updatedAt: Long)
}
