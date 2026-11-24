package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AddressValidator
import com.example.data.AiTestResultEntity
import com.example.data.AppDatabase
import com.example.data.DnsRepository
import com.example.data.DnsServerEntity
import com.example.data.TransportMode
import com.example.network.AiDnsTesterEngine
import com.example.vpn.DnsVpnService
import com.example.vpn.VpnConnectionStatus
import com.example.vpn.VpnRuntimeState
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class TesterProgressState(
    val isTestingAll: Boolean = false,
    val testingServerIds: Set<Long> = emptySet(),
    val completedCount: Int = 0,
    val totalCount: Int = 0
)

data class PulsUiState(
    val servers: List<DnsServerEntity> = emptyList(),
    val selectedServer: DnsServerEntity? = null,
    val vpnState: VpnRuntimeState = VpnRuntimeState(),
    val testResultsByServer: Map<Long, List<AiTestResultEntity>> = emptyMap(),
    val testerProgress: TesterProgressState = TesterProgressState(),
    val bestServerId: Long? = null,
    val snackbarMessage: String? = null
)

class DnsVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DnsRepository(AppDatabase.getInstance(application).dnsDao())
    private val testerEngine = AiDnsTesterEngine()

    private val testerProgress = MutableStateFlow(TesterProgressState())
    private val snackbarMessage = MutableStateFlow<String?>(null)

    private var batchTestJob: Job? = null

    val uiState: StateFlow<PulsUiState> = combine(
        repository.servers,
        VpnStateHolder.state,
        repository.testResultsByServer,
        testerProgress,
        snackbarMessage
    ) { servers, vpnState, resultsMap, progress, message ->
        val selected = servers.firstOrNull { it.isSelected } ?: servers.firstOrNull()
        val best = findBestServer(servers)
        PulsUiState(
            servers = servers,
            selectedServer = selected,
            vpnState = vpnState,
            testResultsByServer = resultsMap,
            testerProgress = progress,
            bestServerId = best?.id,
            snackbarMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PulsUiState()
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun clearSnackbar() {
        snackbarMessage.value = null
    }

    fun showMessage(message: String) {
        snackbarMessage.value = message
    }

    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            repository.selectServer(serverId)
            val currentVpn = VpnStateHolder.state.value
            if (currentVpn.status == VpnConnectionStatus.CONNECTED ||
                currentVpn.status == VpnConnectionStatus.CONNECTING
            ) {
                DnsVpnService.switchServer(getApplication(), serverId)
            }
        }
    }

    fun startVpnWithSelectedServer() {
        val selected = uiState.value.selectedServer
        if (selected == null) {
            snackbarMessage.value = "Добавьте или выберите DNS-сервер"
            return
        }
        DnsVpnService.start(getApplication(), selected.id)
    }

    fun stopVpn() {
        DnsVpnService.stop(getApplication())
    }

    fun addServer(name: String, rawAddress: String, mode: TransportMode): Boolean {
        val parsed = AddressValidator.parse(rawAddress) ?: return false
        viewModelScope.launch {
            val newId = repository.addServer(
                name = name.ifBlank { parsed.host },
                address = parsed.normalizedInput,
                transportMode = mode,
                selectImmediately = true
            )
            val currentVpn = VpnStateHolder.state.value
            if (currentVpn.status == VpnConnectionStatus.CONNECTED ||
                currentVpn.status == VpnConnectionStatus.CONNECTING
            ) {
                DnsVpnService.switchServer(getApplication(), newId)
            }
            snackbarMessage.value = "Сервер ${parsed.normalizedInput} добавлен"
        }
        return true
    }

    fun updateServer(
        existing: DnsServerEntity,
        name: String,
        rawAddress: String,
        mode: TransportMode
    ): Boolean {
        val parsed = AddressValidator.parse(rawAddress) ?: return false
        viewModelScope.launch {
            repository.updateServer(
                existing = existing,
                name = name.ifBlank { parsed.host },
                address = parsed.normalizedInput,
                transportMode = mode
            )
            val currentVpn = VpnStateHolder.state.value
            if (currentVpn.activeServerId == existing.id &&
                (currentVpn.status == VpnConnectionStatus.CONNECTED ||
                    currentVpn.status == VpnConnectionStatus.CONNECTING)
            ) {
                DnsVpnService.switchServer(getApplication(), existing.id)
            }
            snackbarMessage.value = "Сервер обновлён"
        }
        return true
    }

    fun deleteServer(server: DnsServerEntity) {
        viewModelScope.launch {
            val currentVpn = VpnStateHolder.state.value
            if (currentVpn.activeServerId == server.id &&
                (currentVpn.status == VpnConnectionStatus.CONNECTED ||
                    currentVpn.status == VpnConnectionStatus.CONNECTING)
            ) {
                DnsVpnService.stop(getApplication())
            }
            repository.deleteServer(server.id)
            snackbarMessage.value = "Сервер ${server.address} удалён"
        }
    }

    fun runTestAll() {
        val serversSnapshot = uiState.value.servers
        if (serversSnapshot.isEmpty()) {
            snackbarMessage.value = "Нет серверов для проверки"
            return
        }
        batchTestJob?.cancel()
        batchTestJob = viewModelScope.launch {
            testerProgress.value = TesterProgressState(
                isTestingAll = true,
                testingServerIds = emptySet(),
                completedCount = 0,
                totalCount = serversSnapshot.size
            )
            val semaphore = Semaphore(3)
            try {
                coroutineScope {
                    serversSnapshot.map { server ->
                        async {
                            semaphore.withPermit {
                                testerProgress.update {
                                    it.copy(testingServerIds = it.testingServerIds + server.id)
                                }
                                try {
                                    val benchmark = testerEngine.testServer(server)
                                    repository.saveTestResults(
                                        serverId = server.id,
                                        results = benchmark.results,
                                        detectedTransport = benchmark.detectedTransport,
                                        resolvedIp = benchmark.resolvedIp,
                                        avgLatencyMs = benchmark.avgLatencyMs,
                                        testedAt = benchmark.testedAt
                                    )
                                } finally {
                                    testerProgress.update {
                                        it.copy(
                                            testingServerIds = it.testingServerIds - server.id,
                                            completedCount = it.completedCount + 1
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
                snackbarMessage.value = "Проверка завершена"
            } finally {
                testerProgress.update {
                    it.copy(isTestingAll = false, testingServerIds = emptySet())
                }
            }
        }
    }

    fun runTestSingle(server: DnsServerEntity) {
        if (server.id in testerProgress.value.testingServerIds) return
        viewModelScope.launch {
            testerProgress.update {
                it.copy(testingServerIds = it.testingServerIds + server.id)
            }
            try {
                val benchmark = testerEngine.testServer(server)
                repository.saveTestResults(
                    serverId = server.id,
                    results = benchmark.results,
                    detectedTransport = benchmark.detectedTransport,
                    resolvedIp = benchmark.resolvedIp,
                    avgLatencyMs = benchmark.avgLatencyMs,
                    testedAt = benchmark.testedAt
                )
            } finally {
                testerProgress.update {
                    it.copy(testingServerIds = it.testingServerIds - server.id)
                }
            }
        }
    }

    fun stopTesting() {
        batchTestJob?.cancel()
        batchTestJob = null
        testerProgress.value = TesterProgressState()
    }

    fun selectBestServer() {
        val best = findBestServer(uiState.value.servers)
        if (best == null) {
            snackbarMessage.value = "Сначала запустите проверку серверов"
            return
        }
        selectServer(best.id)
        val score = "${best.lastAiAvailableCount ?: 0}/${best.lastAiTotalCount}"
        val ping = best.lastLatencyMs?.let { " · $it мс" }.orEmpty()
        snackbarMessage.value = "Выбран ${best.address} ($score AI$ping)"
    }

    private fun findBestServer(servers: List<DnsServerEntity>): DnsServerEntity? {
        return servers
            .filter { (it.lastAiAvailableCount ?: 0) > 0 }
            .sortedWith(
                compareByDescending<DnsServerEntity> { it.lastAiAvailableCount ?: 0 }
                    .thenBy { it.lastLatencyMs ?: Int.MAX_VALUE }
            )
            .firstOrNull()
    }
}
