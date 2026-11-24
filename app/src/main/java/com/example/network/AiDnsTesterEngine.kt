package com.example.network

import android.os.SystemClock
import com.example.data.ActiveTransport
import com.example.data.AiAccessStatus
import com.example.data.AiTarget
import com.example.data.AiTargets
import com.example.data.AiTestResultEntity
import com.example.data.DnsServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class ServerAiBenchmark(
    val serverId: Long,
    val detectedTransport: ActiveTransport?,
    val resolvedIp: String?,
    val avgLatencyMs: Int?,
    val results: List<AiTestResultEntity>,
    val testedAt: Long
)

class AiDnsTesterEngine(
    private val upstreamClient: DnsUpstreamClient = DnsUpstreamClient()
) {

    private val probeBaseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4200, TimeUnit.MILLISECONDS)
        .readTimeout(4200, TimeUnit.MILLISECONDS)
        .writeTimeout(4200, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun testServer(
        server: DnsServerEntity,
        targets: List<AiTarget> = AiTargets.defaultTargets
    ): ServerAiBenchmark = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val upstreamResult = upstreamClient.resolveUpstream(server)

        val upstream = upstreamResult.getOrNull()
        if (upstream == null) {
            val reason = upstreamResult.exceptionOrNull()?.message ?: "Сервер не отвечает"
            val failed = targets.map { target ->
                AiTestResultEntity(
                    serverId = server.id,
                    serviceId = target.id,
                    serviceName = target.name,
                    domain = target.domain,
                    status = AiAccessStatus.UNREACHABLE.name,
                    resolvedIp = null,
                    dnsLatencyMs = null,
                    httpCode = null,
                    detailMessage = reason,
                    testedAt = now
                )
            }
            return@withContext ServerAiBenchmark(
                serverId = server.id,
                detectedTransport = null,
                resolvedIp = null,
                avgLatencyMs = null,
                results = failed,
                testedAt = now
            )
        }

        val results = coroutineScope {
            targets.map { target ->
                async { probeSingleTarget(server.id, upstream, target, now) }
            }.awaitAll()
        }

        val latencies = results.mapNotNull { it.dnsLatencyMs }
        val avgLatency = if (latencies.isNotEmpty()) latencies.average().toInt() else null

        ServerAiBenchmark(
            serverId = server.id,
            detectedTransport = upstream.transport,
            resolvedIp = upstream.resolvedIp,
            avgLatencyMs = avgLatency,
            results = results,
            testedAt = now
        )
    }

    private fun probeSingleTarget(
        serverId: Long,
        upstream: ResolvedUpstream,
        target: AiTarget,
        testedAt: Long
    ): AiTestResultEntity {
        val query = DnsWireCodec.buildAQuery(target.domain)
        val startMs = SystemClock.elapsedRealtime()
        val rawResp = upstreamClient.forward(upstream, query, timeoutMs = 3500)
        val dnsLatency = (SystemClock.elapsedRealtime() - startMs).toInt().coerceAtLeast(1)

        if (rawResp == null) {
            return AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = null,
                dnsLatencyMs = null,
                httpCode = null,
                detailMessage = "Таймаут DNS (${upstream.transport.label})",
                testedAt = testedAt
            )
        }

        val parsed = DnsWireCodec.parseResponse(rawResp)
        if (parsed == null || parsed.rcode != 0) {
            val codeText = when (parsed?.rcode) {
                2 -> "SERVFAIL"
                3 -> "NXDOMAIN"
                5 -> "REFUSED"
                else -> "Ошибка DNS"
            }
            return AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = null,
                dnsLatencyMs = dnsLatency,
                httpCode = null,
                detailMessage = codeText,
                testedAt = testedAt
            )
        }

        if (parsed.isSinkholed || parsed.ipv4Addresses.isEmpty()) {
            return AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = parsed.ipv4Addresses.firstOrNull(),
                dnsLatencyMs = dnsLatency,
                httpCode = null,
                detailMessage = if (parsed.isSinkholed) "Заглушка DNS" else "Нет A-записи",
                testedAt = testedAt
            )
        }

        val targetIp = parsed.ipv4Addresses.first()
        return verifyHttpsReachability(
            serverId = serverId,
            target = target,
            resolvedIp = targetIp,
            dnsLatencyMs = dnsLatency,
            testedAt = testedAt
        )
    }

    private fun verifyHttpsReachability(
        serverId: Long,
        target: AiTarget,
        resolvedIp: String,
        dnsLatencyMs: Int,
        testedAt: Long
    ): AiTestResultEntity {
        return try {
            val forcedAddress = InetAddress.getByName(resolvedIp)
            val client = probeBaseClient.newBuilder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = listOf(forcedAddress)
                })
                .build()

            val request = Request.Builder()
                .url(target.probeUrl)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val cfMitigated = response.header("cf-mitigated")
                val serverHeader = response.header("server").orEmpty().lowercase()
                val locationHeader = response.header("location").orEmpty().lowercase()

                val snippet = if (code == 403 || code == 451 || code == 200) {
                    try {
                        response.peekBody(4096).string().lowercase()
                    } catch (_: Exception) {
                        ""
                    }
                } else {
                    ""
                }

                val hasGeoBlockCue = snippet.contains("unsupported_country") ||
                    snippet.contains("not available in your country") ||
                    snippet.contains("region is not supported") ||
                    snippet.contains("country is not supported") ||
                    snippet.contains("unavailable in your region") ||
                    locationHeader.contains("unsupported") ||
                    locationHeader.contains("not-available")

                val isCloudflareChallenge = cfMitigated.equals("challenge", ignoreCase = true) ||
                    (serverHeader.contains("cloudflare") &&
                        (snippet.contains("just a moment") || snippet.contains("cf-browser-verification")))

                val (status, detail) = when {
                    code == 451 || hasGeoBlockCue -> {
                        AiAccessStatus.RESTRICTED to "Гео-ограничение (HTTP $code)"
                    }
                    code in 200..399 -> {
                        AiAccessStatus.AVAILABLE to "Доступно · HTTP $code"
                    }
                    code == 403 && isCloudflareChallenge -> {
                        AiAccessStatus.AVAILABLE to "TLS OK · Cloudflare (HTTP 403)"
                    }
                    code == 403 -> {
                        AiAccessStatus.RESTRICTED to "Ограничено · HTTP 403"
                    }
                    code in 400..499 -> {
                        AiAccessStatus.AVAILABLE to "TLS OK · HTTP $code"
                    }
                    else -> {
                        AiAccessStatus.RESTRICTED to "Ошибка сервера (HTTP $code)"
                    }
                }

                AiTestResultEntity(
                    serverId = serverId,
                    serviceId = target.id,
                    serviceName = target.name,
                    domain = target.domain,
                    status = status.name,
                    resolvedIp = resolvedIp,
                    dnsLatencyMs = dnsLatencyMs,
                    httpCode = code,
                    detailMessage = detail,
                    testedAt = testedAt
                )
            }
        } catch (_: SocketTimeoutException) {
            AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = resolvedIp,
                dnsLatencyMs = dnsLatencyMs,
                httpCode = null,
                detailMessage = "Таймаут HTTPS / блокировка",
                testedAt = testedAt
            )
        } catch (_: SSLException) {
            AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = resolvedIp,
                dnsLatencyMs = dnsLatencyMs,
                httpCode = null,
                detailMessage = "Сброс TLS / DPI",
                testedAt = testedAt
            )
        } catch (_: ConnectException) {
            AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = resolvedIp,
                dnsLatencyMs = dnsLatencyMs,
                httpCode = null,
                detailMessage = "Соединение отклонено",
                testedAt = testedAt
            )
        } catch (_: Exception) {
            AiTestResultEntity(
                serverId = serverId,
                serviceId = target.id,
                serviceName = target.name,
                domain = target.domain,
                status = AiAccessStatus.UNREACHABLE.name,
                resolvedIp = resolvedIp,
                dnsLatencyMs = dnsLatencyMs,
                httpCode = null,
                detailMessage = "Нет доступа по HTTPS",
                testedAt = testedAt
            )
        }
    }
}
