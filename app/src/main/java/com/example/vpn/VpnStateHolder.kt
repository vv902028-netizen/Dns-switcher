package com.example.vpn

import com.example.data.ActiveTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VpnConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class VpnRuntimeState(
    val status: VpnConnectionStatus = VpnConnectionStatus.DISCONNECTED,
    val activeServerId: Long? = null,
    val activeServerName: String? = null,
    val activeAddress: String? = null,
    val resolvedIp: String? = null,
    val activeTransport: ActiveTransport? = null,
    val queriesHandled: Long = 0L,
    val connectedSinceMs: Long? = null,
    val errorMessage: String? = null
)

object VpnStateHolder {
    private val _state = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    fun setConnecting(serverId: Long, name: String, address: String) {
        _state.value = VpnRuntimeState(
            status = VpnConnectionStatus.CONNECTING,
            activeServerId = serverId,
            activeServerName = name,
            activeAddress = address,
            errorMessage = null
        )
    }

    fun setConnected(
        serverId: Long,
        name: String,
        address: String,
        resolvedIp: String,
        transport: ActiveTransport,
        keepSessionStats: Boolean = false
    ) {
        _state.update { current ->
            VpnRuntimeState(
                status = VpnConnectionStatus.CONNECTED,
                activeServerId = serverId,
                activeServerName = name,
                activeAddress = address,
                resolvedIp = resolvedIp,
                activeTransport = transport,
                queriesHandled = if (keepSessionStats) current.queriesHandled else 0L,
                connectedSinceMs = if (keepSessionStats) current.connectedSinceMs ?: System.currentTimeMillis() else System.currentTimeMillis(),
                errorMessage = null
            )
        }
    }

    fun incrementQueries() {
        _state.update { current ->
            if (current.status == VpnConnectionStatus.CONNECTED) {
                current.copy(queriesHandled = current.queriesHandled + 1)
            } else {
                current
            }
        }
    }

    fun setError(message: String) {
        _state.value = VpnRuntimeState(
            status = VpnConnectionStatus.ERROR,
            errorMessage = message
        )
    }

    fun setDisconnected() {
        _state.value = VpnRuntimeState(status = VpnConnectionStatus.DISCONNECTED)
    }
}
