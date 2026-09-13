package com.securewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.securewa.data.db.entity.AgentEventEntity
import com.securewa.data.db.entity.AppLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Audit events and the application log.
 *
 * Both tables are write-mostly and bounded: the caller passes content that has
 * already been through the redactor, and retention is enforced by
 * [trimLogsBefore] so the log cannot grow without limit on a device.
 */
@Dao
interface AuditDao {

    // --- agent events -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAgentEvent(event: AgentEventEntity)

    @Query(
        "SELECT * FROM agent_events WHERE agent_id = :agentId ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun eventsForAgent(agentId: String, limit: Int = 100): List<AgentEventEntity>

    @Query(
        "SELECT * FROM agent_events WHERE registered_number_id = :numberId ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun eventsForNumber(numberId: String, limit: Int = 100): List<AgentEventEntity>

    @Query("SELECT * FROM agent_events WHERE correlation_id = :correlationId ORDER BY created_at ASC")
    suspend fun eventsForCorrelation(correlationId: String): List<AgentEventEntity>

    // --- application log ----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLog(entry: AppLogEntity)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeLogs(limit: Int = 500): Flow<List<AppLogEntity>>

    @Query("SELECT * FROM app_logs WHERE correlation_id = :correlationId ORDER BY timestamp ASC")
    suspend fun logsForCorrelation(correlationId: String): List<AppLogEntity>

    /** Drops log rows older than the retention window. */
    @Query("DELETE FROM app_logs WHERE timestamp < :cutoff")
    suspend fun trimLogsBefore(cutoff: Long): Int

    @Query("DELETE FROM app_logs")
    suspend fun clearLogs()

    @Query("SELECT COUNT(*) FROM app_logs")
    suspend fun logCount(): Int
}
