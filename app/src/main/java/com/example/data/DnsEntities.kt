package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "dns_servers")
data class DnsServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    val transportMode: String = TransportMode.AUTO.name,
    val detectedTransport: String? = null,
    val resolvedIp: String? = null,
    val isSelected: Boolean = false,
    val isPreloaded: Boolean = false,
    val lastLatencyMs: Int? = null,
    val lastAiAvailableCount: Int? = null,
    val lastAiTotalCount: Int = AiTargets.defaultTargets.size,
    val lastTestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_test_results",
    foreignKeys = [
        ForeignKey(
            entity = DnsServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["serverId"])]
)
data class AiTestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val serviceId: String,
    val serviceName: String,
    val domain: String,
    val status: String,
    val resolvedIp: String?,
    val dnsLatencyMs: Int?,
    val httpCode: Int?,
    val detailMessage: String,
    val testedAt: Long = System.currentTimeMillis()
)
