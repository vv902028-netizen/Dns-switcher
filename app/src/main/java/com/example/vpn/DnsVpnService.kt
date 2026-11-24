package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DnsRepository
import com.example.data.DnsServerEntity
import com.example.network.DnsUpstreamClient
import com.example.network.ResolvedUpstream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

class DnsVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val upstreamClient = DnsUpstreamClient()
    private val activeUpstream = AtomicReference<ResolvedUpstream?>(null)
    private val tunWriteMutex = Mutex()

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerJob: Job? = null
    private var setupJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnTunnel()
                return START_NOT_STICKY
            }

            ACTION_SWITCH_SERVER -> {
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                if (serverId > 0L) {
                    switchUpstreamServer(serverId)
                }
                return START_STICKY
            }

            ACTION_START -> {
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                if (serverId > 0L) {
                    startVpnTunnel(serverId)
                } else {
                    stopVpnTunnel()
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopVpnTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnelResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpnTunnel(serverId: Long) {
        setupJob?.cancel()
        setupJob = serviceScope.launch {
            val repository = DnsRepository(AppDatabase.getInstance(applicationContext).dnsDao())
            val server = repository.getServerById(serverId)
            if (server == null) {
                VpnStateHolder.setError("Выбранный DNS-сервер не найден")
                stopSelf()
                return@launch
            }

            VpnStateHolder.setConnecting(server.id, server.name, server.address)
            startForegroundWithNotification(
                title = getString(R.string.status_connecting),
                subtitle = server.address
            )

            val resolvedResult = upstreamClient.resolveUpstream(server)
            val resolved = resolvedResult.getOrElse { error ->
                VpnStateHolder.setError(error.message ?: "Не удалось подключиться к DNS")
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            activeUpstream.set(resolved)
            repository.updateServerResolution(server.id, resolved.transport, resolved.resolvedIp)

            if (vpnInterface == null) {
                val descriptor = establishTunInterface()
                if (descriptor == null) {
                    VpnStateHolder.setError("Система отклонила создание VPN-интерфейса")
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                vpnInterface = descriptor
                startPacketLoop(descriptor)
            }

            VpnStateHolder.setConnected(
                serverId = server.id,
                name = server.name,
                address = server.address,
                resolvedIp = resolved.resolvedIp,
                transport = resolved.transport,
                keepSessionStats = false
            )
            updateNotification(server, resolved)
        }
    }

    private fun switchUpstreamServer(serverId: Long) {
        if (vpnInterface == null) {
            startVpnTunnel(serverId)
            return
        }
        setupJob?.cancel()
        setupJob = serviceScope.launch {
            val repository = DnsRepository(AppDatabase.getInstance(applicationContext).dnsDao())
            val server = repository.getServerById(serverId) ?: return@launch
            VpnStateHolder.setConnecting(server.id, server.name, server.address)

            val resolvedResult = upstreamClient.resolveUpstream(server)
            val resolved = resolvedResult.getOrElse { error ->
                VpnStateHolder.setError(error.message ?: "Ошибка переключения DNS")
                stopVpnTunnel()
                return@launch
            }

            activeUpstream.set(resolved)
            repository.updateServerResolution(server.id, resolved.transport, resolved.resolvedIp)
            VpnStateHolder.setConnected(
                serverId = server.id,
                name = server.name,
                address = server.address,
                resolvedIp = resolved.resolvedIp,
                transport = resolved.transport,
                keepSessionStats = true
            )
            updateNotification(server, resolved)
        }
    }

    private fun establishTunInterface(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Пульс DNS")
                .setMtu(1500)
                .addAddress(VIRTUAL_IPV4_CLIENT, 32)
                .addDnsServer(VIRTUAL_IPV4_DNS)
                .addRoute(VIRTUAL_IPV4_DNS, 32)
                .addAddress(VIRTUAL_IPV6_CLIENT, 128)
                .addDnsServer(VIRTUAL_IPV6_DNS)
                .addRoute(VIRTUAL_IPV6_DNS, 128)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
                // Fallback if package cannot be excluded; sockets are also protected explicitly.
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.establish()
        } catch (_: Exception) {
            null
        }
    }

    private fun startPacketLoop(descriptor: ParcelFileDescriptor) {
        readerJob?.cancel()
        readerJob = serviceScope.launch {
            val input = FileInputStream(descriptor.fileDescriptor)
            val output = FileOutputStream(descriptor.fileDescriptor)
            val buffer = ByteArray(32767)

            try {
                while (isActive) {
                    val length = input.read(buffer)
                    if (length <= 0) {
                        if (length < 0) break
                        continue
                    }

                    val parsed = PacketParser.parseDnsQueryPacket(buffer, length) ?: continue
                    val currentUpstream = activeUpstream.get() ?: continue

                    launch {
                        val dnsReply = upstreamClient.forward(
                            upstream = currentUpstream,
                            dnsPayload = parsed.dnsPayload,
                            timeoutMs = 4000,
                            protectDatagram = { socket -> protect(socket) },
                            protectSocket = { socket -> protect(socket) }
                        ) ?: return@launch

                        val responsePacket = PacketParser.buildDnsResponsePacket(parsed, dnsReply)
                        tunWriteMutex.withLock {
                            try {
                                output.write(responsePacket)
                                VpnStateHolder.incrementQueries()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Expected when ParcelFileDescriptor is closed on stop
            }
        }
    }

    private fun stopVpnTunnel() {
        setupJob?.cancel()
        closeTunnelResources()
        VpnStateHolder.setDisconnected()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeTunnelResources() {
        readerJob?.cancel()
        readerJob = null
        activeUpstream.set(null)
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun updateNotification(server: DnsServerEntity, resolved: ResolvedUpstream) {
        val subtitle = "${server.address} · ${resolved.transport.label} (${resolved.resolvedIp})"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_title_connected), subtitle)
        )
    }

    private fun startForegroundWithNotification(title: String, subtitle: String) {
        ensureNotificationChannel()
        val notification = buildNotification(title, subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notification_action_stop), stopPending)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.vpn.ACTION_START"
        const val ACTION_STOP = "com.example.vpn.ACTION_STOP"
        const val ACTION_SWITCH_SERVER = "com.example.vpn.ACTION_SWITCH_SERVER"
        const val EXTRA_SERVER_ID = "extra_server_id"

        private const val CHANNEL_ID = "puls_vpn_channel"
        private const val NOTIFICATION_ID = 1042

        private const val VIRTUAL_IPV4_CLIENT = "10.120.0.1"
        private const val VIRTUAL_IPV4_DNS = "10.120.0.2"
        private const val VIRTUAL_IPV6_CLIENT = "fd00:120::1"
        private const val VIRTUAL_IPV6_DNS = "fd00:120::2"

        fun start(context: Context, serverId: Long) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_ID, serverId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun switchServer(context: Context, serverId: Long) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_SWITCH_SERVER
                putExtra(EXTRA_SERVER_ID, serverId)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
