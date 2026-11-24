package com.example.network

import com.example.data.ActiveTransport
import com.example.data.AddressValidator
import com.example.data.DnsServerEntity
import com.example.data.ParsedAddressKind
import com.example.data.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ResolvedUpstream(
    val serverId: Long,
    val displayName: String,
    val rawAddress: String,
    val host: String,
    val resolvedIp: String,
    val transport: ActiveTransport,
    val port: Int,
    val dohUrl: String?
)

class DnsUpstreamClient {

    private val dnsMessageMediaType = "application/dns-message".toMediaType()
    private val baseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .writeTimeout(3500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun resolveUpstream(
        server: DnsServerEntity,
        probeTimeoutMs: Int = 2600
    ): Result<ResolvedUpstream> = withContext(Dispatchers.IO) {
        val parsed = AddressValidator.parse(server.address)
            ?: return@withContext Result.failure(IllegalArgumentException("Некорректный адрес DNS: ${server.address}"))

        val mode = TransportMode.fromName(server.transportMode)
        val resolvedIp = resolveHostToIpv4(parsed.host)
            ?: server.resolvedIp?.takeIf { AddressValidator.isValidIpv4(it) }
            ?: return@withContext Result.failure(
                IllegalStateException("Не удалось определить IP для ${parsed.host}")
            )

        // Direct explicit modes or explicit URLs
        when {
            mode == TransportMode.DOH || (mode == TransportMode.AUTO && parsed.kind == ParsedAddressKind.DOH_URL) -> {
                val url = parsed.dohUrl ?: "https://${parsed.host}/dns-query"
                return@withContext Result.success(
                    ResolvedUpstream(
                        serverId = server.id,
                        displayName = server.name,
                        rawAddress = server.address,
                        host = parsed.host,
                        resolvedIp = resolvedIp,
                        transport = ActiveTransport.DOH,
                        port = parsed.port ?: 443,
                        dohUrl = url
                    )
                )
            }

            mode == TransportMode.DOT || (mode == TransportMode.AUTO && parsed.kind == ParsedAddressKind.DOT_HOST) -> {
                return@withContext Result.success(
                    ResolvedUpstream(
                        serverId = server.id,
                        displayName = server.name,
                        rawAddress = server.address,
                        host = parsed.host,
                        resolvedIp = resolvedIp,
                        transport = ActiveTransport.DOT,
                        port = parsed.port ?: 853,
                        dohUrl = null
                    )
                )
            }

            mode == TransportMode.UDP || (mode == TransportMode.AUTO && parsed.kind == ParsedAddressKind.IPV4) -> {
                return@withContext Result.success(
                    ResolvedUpstream(
                        serverId = server.id,
                        displayName = server.name,
                        rawAddress = server.address,
                        host = parsed.host,
                        resolvedIp = resolvedIp,
                        transport = ActiveTransport.UDP,
                        port = parsed.port ?: 53,
                        dohUrl = null
                    )
                )
            }
        }

        // AUTO mode for domain names: probe DoH, DoT, and UDP 53 in parallel
        val probeQuery = DnsWireCodec.buildAQuery("example.com")
        val dohCandidate = ResolvedUpstream(
            serverId = server.id,
            displayName = server.name,
            rawAddress = server.address,
            host = parsed.host,
            resolvedIp = resolvedIp,
            transport = ActiveTransport.DOH,
            port = 443,
            dohUrl = "https://${parsed.host}/dns-query"
        )
        val dotCandidate = ResolvedUpstream(
            serverId = server.id,
            displayName = server.name,
            rawAddress = server.address,
            host = parsed.host,
            resolvedIp = resolvedIp,
            transport = ActiveTransport.DOT,
            port = 853,
            dohUrl = null
        )
        val udpCandidate = ResolvedUpstream(
            serverId = server.id,
            displayName = server.name,
            rawAddress = server.address,
            host = parsed.host,
            resolvedIp = resolvedIp,
            transport = ActiveTransport.UDP,
            port = parsed.port ?: 53,
            dohUrl = null
        )

        val chosen = coroutineScope {
            val dohOk = async { probeCandidate(dohCandidate, probeQuery, probeTimeoutMs) }
            val dotOk = async { probeCandidate(dotCandidate, probeQuery, probeTimeoutMs) }
            val udpOk = async { probeCandidate(udpCandidate, probeQuery, probeTimeoutMs) }

            when {
                dohOk.await() -> dohCandidate
                dotOk.await() -> dotCandidate
                udpOk.await() -> udpCandidate
                else -> udpCandidate // Fallback to resolved IPv4 UDP 53
            }
        }

        Result.success(chosen)
    }

    private fun probeCandidate(
        candidate: ResolvedUpstream,
        probeQuery: ByteArray,
        timeoutMs: Int
    ): Boolean {
        val response = forward(candidate, probeQuery, timeoutMs = timeoutMs) ?: return false
        val parsed = DnsWireCodec.parseResponse(response) ?: return false
        return parsed.rcode == 0 || parsed.ipv4Addresses.isNotEmpty()
    }

    fun forward(
        upstream: ResolvedUpstream,
        dnsPayload: ByteArray,
        timeoutMs: Int = 3500,
        protectDatagram: ((DatagramSocket) -> Unit)? = null,
        protectSocket: ((Socket) -> Unit)? = null
    ): ByteArray? {
        return when (upstream.transport) {
            ActiveTransport.UDP -> forwardUdp(upstream, dnsPayload, timeoutMs, protectDatagram)
            ActiveTransport.DOH -> forwardDoh(upstream, dnsPayload, timeoutMs)
            ActiveTransport.DOT -> forwardDot(upstream, dnsPayload, timeoutMs, protectSocket)
        }
    }

    private fun forwardUdp(
        upstream: ResolvedUpstream,
        dnsPayload: ByteArray,
        timeoutMs: Int,
        protectDatagram: ((DatagramSocket) -> Unit)?
    ): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protectDatagram?.invoke(socket)
            socket.soTimeout = timeoutMs
            val targetAddress = InetAddress.getByName(upstream.resolvedIp)
            val requestPacket = DatagramPacket(dnsPayload, dnsPayload.size, targetAddress, upstream.port)
            socket.send(requestPacket)

            val buffer = ByteArray(4096)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            buffer.copyOf(responsePacket.length)
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun forwardDoh(
        upstream: ResolvedUpstream,
        dnsPayload: ByteArray,
        timeoutMs: Int
    ): ByteArray? {
        val url = upstream.dohUrl ?: return null
        return try {
            val fixedIp = InetAddress.getByName(upstream.resolvedIp)
            val client = baseHttpClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = listOf(fixedIp)
                })
                .build()

            val request = Request.Builder()
                .url(url)
                .post(dnsPayload.toRequestBody(dnsMessageMediaType))
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()?.takeIf { it.size >= 12 }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun forwardDot(
        upstream: ResolvedUpstream,
        dnsPayload: ByteArray,
        timeoutMs: Int,
        protectSocket: ((Socket) -> Unit)?
    ): ByteArray? {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            rawSocket = Socket()
            protectSocket?.invoke(rawSocket)
            rawSocket.connect(InetSocketAddress(upstream.resolvedIp, upstream.port), timeoutMs)
            rawSocket.soTimeout = timeoutMs

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = factory.createSocket(rawSocket, upstream.host, upstream.port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()

            val out = DataOutputStream(sslSocket.outputStream)
            out.writeShort(dnsPayload.size)
            out.write(dnsPayload)
            out.flush()

            val input = DataInputStream(sslSocket.inputStream)
            val responseLen = input.readUnsignedShort()
            if (responseLen < 12 || responseLen > 65535) return null
            val responseBytes = ByteArray(responseLen)
            input.readFully(responseBytes)
            responseBytes
        } catch (_: Exception) {
            null
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {
            }
            try {
                rawSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveHostToIpv4(host: String): String? {
        if (AddressValidator.isValidIpv4(host)) return host

        // Primary: system resolver
        try {
            val addresses = InetAddress.getAllByName(host)
            val ipv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress
            if (!ipv4.isNullOrBlank()) return ipv4
        } catch (_: Exception) {
        }

        // Fallback bootstrap DNS servers in case local resolver fails
        val bootstrapServers = listOf("77.88.8.8", "1.1.1.1", "8.8.8.8")
        val query = DnsWireCodec.buildAQuery(host)
        for (bootstrapIp in bootstrapServers) {
            val fakeUpstream = ResolvedUpstream(
                serverId = -1,
                displayName = bootstrapIp,
                rawAddress = bootstrapIp,
                host = bootstrapIp,
                resolvedIp = bootstrapIp,
                transport = ActiveTransport.UDP,
                port = 53,
                dohUrl = null
            )
            val resp = forwardUdp(fakeUpstream, query, timeoutMs = 2000, protectDatagram = null) ?: continue
            val parsed = DnsWireCodec.parseResponse(resp) ?: continue
            val ip = parsed.ipv4Addresses.firstOrNull { !it.startsWith("127.") && it != "0.0.0.0" }
            if (ip != null) return ip
        }
        return null
    }
}
