package com.securewa.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.securewa.data.db.entity.AgentEntity
import com.securewa.data.db.entity.AgentRoutingRuleEntity
import kotlinx.coroutines.flow.Flow

/** Agents and the rules that select between them. */
@Dao
interface AgentsDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAgent(agent: AgentEntity)

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgent(id: String)

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun agentById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE registered_number_id = :numberId ORDER BY name")
    suspend fun agentsForNumber(numberId: String): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE registered_number_id = :numberId AND is_enabled = 1 ORDER BY name")
    suspend fun enabledAgentsForNumber(numberId: String): List<AgentEntity>

    @Query("SELECT * FROM agents ORDER BY name")
    fun observeAgents(): Flow<List<AgentEntity>>

    /**
     * Agents that reference the same credential slot. Used by the UI to show
     * that a slot is shared, which is allowed, while the prompts, revisions and
     * conversations of those agents remain separate.
     */
    @Query("SELECT * FROM agents WHERE credential_slot_id = :credentialSlotId ORDER BY name")
    suspend fun agentsUsingCredentialSlot(credentialSlotId: String): List<AgentEntity>

    // --- routing rules ------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: AgentRoutingRuleEntity)

    @Update
    suspend fun updateRule(rule: AgentRoutingRuleEntity)

    @Query("DELETE FROM agent_routing_rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("SELECT * FROM agent_routing_rules WHERE id = :id")
    suspend fun ruleById(id: String): AgentRoutingRuleEntity?

    /**
     * Rules in evaluation order: priority ascending, then id ascending. The
     * router applies exactly this order, so the query must not reorder them.
     */
    @Query(
        "SELECT * FROM agent_routing_rules WHERE registered_number_id = :numberId " +
            "ORDER BY priority ASC, id ASC"
    )
    suspend fun rulesForNumber(numberId: String): List<AgentRoutingRuleEntity>

    @Query(
        "SELECT * FROM agent_routing_rules WHERE registered_number_id = :numberId AND is_enabled = 1 " +
            "ORDER BY priority ASC, id ASC"
    )
    suspend fun enabledRulesForNumber(numberId: String): List<AgentRoutingRuleEntity>

    @Upsert
    suspend fun upsertRules(rules: List<AgentRoutingRuleEntity>)
}
