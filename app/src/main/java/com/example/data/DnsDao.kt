package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsDao {
    @Query("SELECT * FROM dns_servers ORDER BY isSelected DESC, createdAt ASC")
    fun observeServers(): Flow<List<DnsServerEntity>>

    @Query("SELECT * FROM dns_servers ORDER BY createdAt ASC")
    suspend fun getAllServers(): List<DnsServerEntity>

    @Query("SELECT * FROM dns_servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): DnsServerEntity?

    @Query("SELECT COUNT(*) FROM dns_servers")
    suspend fun getServerCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: DnsServerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<DnsServerEntity>)

    @Update
    suspend fun updateServer(server: DnsServerEntity)

    @Query("UPDATE dns_servers SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE dns_servers SET isSelected = 1 WHERE id = :serverId")
    suspend fun markSelected(serverId: Long)

    @Transaction
    suspend fun selectServer(serverId: Long) {
        clearSelection()
        markSelected(serverId)
    }

    @Query("DELETE FROM dns_servers WHERE id = :serverId")
    suspend fun deleteServerById(serverId: Long)

    @Query(
        """
        UPDATE dns_servers
        SET detectedTransport = :detectedTransport,
            resolvedIp = :resolvedIp,
            lastLatencyMs = :latencyMs,
            lastAiAvailableCount = :availableCount,
            lastAiTotalCount = :totalCount,
            lastTestedAt = :testedAt
        WHERE id = :serverId
        """
    )
    suspend fun updateServerTestSummary(
        serverId: Long,
        detectedTransport: String?,
        resolvedIp: String?,
        latencyMs: Int?,
        availableCount: Int,
        totalCount: Int,
        testedAt: Long
    )

    @Query(
        """
        UPDATE dns_servers
        SET detectedTransport = :detectedTransport,
            resolvedIp = :resolvedIp
        WHERE id = :serverId
        """
    )
    suspend fun updateServerResolution(
        serverId: Long,
        detectedTransport: String?,
        resolvedIp: String?
    )

    @Query("SELECT * FROM ai_test_results ORDER BY id ASC")
    fun observeAllTestResults(): Flow<List<AiTestResultEntity>>

    @Query("DELETE FROM ai_test_results WHERE serverId = :serverId")
    suspend fun deleteTestResultsForServer(serverId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTestResults(results: List<AiTestResultEntity>)

    @Transaction
    suspend fun replaceTestResultsForServer(
        serverId: Long,
        results: List<AiTestResultEntity>,
        detectedTransport: String?,
        resolvedIp: String?,
        avgLatencyMs: Int?,
        availableCount: Int,
        totalCount: Int,
        testedAt: Long
    ) {
        deleteTestResultsForServer(serverId)
        insertTestResults(results)
        updateServerTestSummary(
            serverId = serverId,
            detectedTransport = detectedTransport,
            resolvedIp = resolvedIp,
            latencyMs = avgLatencyMs,
            availableCount = availableCount,
            totalCount = totalCount,
            testedAt = testedAt
        )
    }
}
