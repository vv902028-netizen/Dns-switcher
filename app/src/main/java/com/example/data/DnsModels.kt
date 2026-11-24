package com.example.data

enum class TransportMode(val label: String) {
    AUTO("Авто"),
    UDP("UDP 53"),
    DOH("DoH"),
    DOT("DoT");

    companion object {
        fun fromName(value: String?): TransportMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class ActiveTransport(val label: String) {
    UDP("UDP 53"),
    DOH("DoH"),
    DOT("DoT");

    companion object {
        fun fromName(value: String?): ActiveTransport? =
            entries.firstOrNull { it.name == value }
    }
}

enum class AiAccessStatus {
    AVAILABLE,
    RESTRICTED,
    UNREACHABLE;

    companion object {
        fun fromName(value: String?): AiAccessStatus =
            entries.firstOrNull { it.name == value } ?: UNREACHABLE
    }
}

data class AiTarget(
    val id: String,
    val name: String,
    val domain: String,
    val probeUrl: String
)

object AiTargets {
    val defaultTargets: List<AiTarget> = listOf(
        AiTarget("chatgpt", "ChatGPT", "chatgpt.com", "https://chatgpt.com/"),
        AiTarget("gemini", "Gemini", "gemini.google.com", "https://gemini.google.com/"),
        AiTarget("claude", "Claude", "claude.ai", "https://claude.ai/"),
        AiTarget("perplexity", "Perplexity", "www.perplexity.ai", "https://www.perplexity.ai/")
    )
}

enum class ParsedAddressKind(val hint: String) {
    IPV4("IPv4-адрес"),
    DOMAIN("Доменное имя"),
    DOH_URL("DNS-over-HTTPS"),
    DOT_HOST("DNS-over-TLS")
}

data class ParsedDnsAddress(
    val normalizedInput: String,
    val host: String,
    val port: Int?,
    val dohUrl: String?,
    val kind: ParsedAddressKind
)

object AddressValidator {
    private val ipv4Regex = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val domainRegex = Regex(
        "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$"
    )

    fun isValidIpv4(value: String): Boolean = ipv4Regex.matches(value)

    fun parse(rawInput: String): ParsedDnsAddress? {
        val trimmed = rawInput.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

        // Full DoH URL: https://host[:port][/path]
        if (trimmed.startsWith("https://")) {
            val withoutScheme = trimmed.removePrefix("https://")
            val slashIndex = withoutScheme.indexOf('/')
            val authority = if (slashIndex >= 0) withoutScheme.substring(0, slashIndex) else withoutScheme
            val path = if (slashIndex >= 0) withoutScheme.substring(slashIndex) else "/dns-query"
            val hostPort = splitHostPort(authority) ?: return null
            if (!isValidIpv4(hostPort.first) && !domainRegex.matches(hostPort.first)) return null
            val fullUrl = "https://$authority${if (path == "/" || path.isEmpty()) "/dns-query" else path}"
            return ParsedDnsAddress(
                normalizedInput = fullUrl,
                host = hostPort.first,
                port = hostPort.second ?: 443,
                dohUrl = fullUrl,
                kind = ParsedAddressKind.DOH_URL
            )
        }

        // DoT URI: tls://host[:port]
        if (trimmed.startsWith("tls://")) {
            val authority = trimmed.removePrefix("tls://").substringBefore('/')
            val hostPort = splitHostPort(authority) ?: return null
            if (!isValidIpv4(hostPort.first) && !domainRegex.matches(hostPort.first)) return null
            val port = hostPort.second ?: 853
            return ParsedDnsAddress(
                normalizedInput = "tls://${hostPort.first}" + if (port != 853) ":$port" else "",
                host = hostPort.first,
                port = port,
                dohUrl = null,
                kind = ParsedAddressKind.DOT_HOST
            )
        }

        // Plain host or host:port
        val cleaned = trimmed.removePrefix("http://").substringBefore('/')
        val hostPort = splitHostPort(cleaned) ?: return null
        val host = hostPort.first
        val port = hostPort.second

        if (isValidIpv4(host)) {
            return ParsedDnsAddress(
                normalizedInput = if (port != null && port != 53) "$host:$port" else host,
                host = host,
                port = port,
                dohUrl = null,
                kind = if (port == 853) ParsedAddressKind.DOT_HOST else ParsedAddressKind.IPV4
            )
        }

        if (domainRegex.matches(host)) {
            return ParsedDnsAddress(
                normalizedInput = if (port != null && port != 53) "$host:$port" else host,
                host = host,
                port = port,
                dohUrl = null,
                kind = if (port == 853) ParsedAddressKind.DOT_HOST else ParsedAddressKind.DOMAIN
            )
        }

        return null
    }

    private fun splitHostPort(authority: String): Pair<String, Int?>? {
        if (authority.isEmpty()) return null
        val colonCount = authority.count { it == ':' }
        if (colonCount == 0) return authority to null
        if (colonCount > 1) return null
        val idx = authority.indexOf(':')
        val host = authority.substring(0, idx)
        val port = authority.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535 || host.isEmpty()) return null
        return host to port
    }
}
