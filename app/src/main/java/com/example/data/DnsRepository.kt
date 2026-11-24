package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DnsRepository(private val dnsDao: DnsDao) {

    val servers: Flow<List<DnsServerEntity>> = dnsDao.observeServers()

    val testResultsByServer: Flow<Map<Long, List<AiTestResultEntity>>> =
        dnsDao.observeAllTestResults().map { list -> list.groupBy { it.serverId } }

    suspend fun ensureSeeded() {
        if (dnsDao.getServerCount() > 0) return
        val now = System.currentTimeMillis()
        val initialServers = listOf(
            "xbox-dns.ru",
            "dns.geohide.ru",
            "dns.comss.one",
            "dns.dns-ai.ru",
            "dns.bezmezhau.com",
            "api.bezshansov.ru",
            "dns.magicdns.ru",
            "dns.dagami.ru"
        ).mapIndexed { index, domain ->
            DnsServerEntity(
                name = domain,
                address = domain,
                transportMode = TransportMode.AUTO.name,
                isSelected = index == 0,
                isPreloaded = true,
                createdAt = now + index
            )
        }
        dnsDao.insertServers(initialServers)
    }

    suspend fun getAllServers(): List<DnsServerEntity> = dnsDao.getAllServers()

    suspend fun getServerById(id: Long): DnsServerEntity? = dnsDao.getServerById(id)

    suspend fun addServer(
        name: String,
        address: String,
        transportMode: TransportMode,
        selectImmediately: Boolean = true
    ): Long {
        val cleanName = name.trim().ifEmpty { address }
        val entity = DnsServerEntity(
            name = cleanName,
            address = address,
            transportMode = transportMode.name,
            isSelected = false,
            isPreloaded = false
        )
        val newId = dnsDao.insertServer(entity)
        if (selectImmediately) {
            dnsDao.selectServer(newId)
        }
        return newId
    }

    suspend fun updateServer(
        existing: DnsServerEntity,
        name: String,
        address: String,
        transportMode: TransportMode
    ) {
        val addressChanged = existing.address != address || existing.transportMode != transportMode.name
        val updated = existing.copy(
            name = name.trim().ifEmpty { address },
            address = address,
            transportMode = transportMode.name,
            detectedTransport = if (addressChanged) null else existing.detectedTransport,
            resolvedIp = if (addressChanged) null else existing.resolvedIp
        )
        dnsDao.updateServer(updated)
    }

    suspend fun selectServer(serverId: Long) {
        dnsDao.selectServer(serverId)
    }

    suspend fun deleteServer(serverId: Long) {
        val wasSelected = dnsDao.getServerById(serverId)?.isSelected == true
        dnsDao.deleteServerById(serverId)
        if (wasSelected) {
            val remaining = dnsDao.getAllServers()
            remaining.firstOrNull()?.let { dnsDao.selectServer(it.id) }
        }
    }

    suspend fun updateServerResolution(
        serverId: Long,
        detectedTransport: ActiveTransport?,
        resolvedIp: String?
    ) {
        dnsDao.updateServerResolution(
            serverId = serverId,
            detectedTransport = detectedTransport?.name,
            resolvedIp = resolvedIp
        )
    }

    suspend fun saveTestResults(
        serverId: Long,
        results: List<AiTestResultEntity>,
        detectedTransport: ActiveTransport?,
        resolvedIp: String?,
        avgLatencyMs: Int?,
        testedAt: Long
    ) {
        val availableCount = results.count {
            AiAccessStatus.fromName(it.status) == AiAccessStatus.AVAILABLE
        }
        dnsDao.replaceTestResultsForServer(
            serverId = serverId,
            results = results,
            detectedTransport = detectedTransport?.name,
            resolvedIp = resolvedIp,
            avgLatencyMs = avgLatencyMs,
            availableCount = availableCount,
            totalCount = results.size,
            testedAt = testedAt
        )
    }
}
