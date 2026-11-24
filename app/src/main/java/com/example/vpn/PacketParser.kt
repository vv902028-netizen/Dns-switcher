package com.example.vpn

data class ParsedDnsUdpPacket(
    val isIpv6: Boolean,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsPayload: ByteArray
)

object PacketParser {

    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53

    fun parseDnsQueryPacket(packet: ByteArray, length: Int): ParsedDnsUdpPacket? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4Udp(packet, length)
            6 -> parseIpv6Udp(packet, length)
            else -> null
        }
    }

    private fun parseIpv4Udp(packet: ByteArray, length: Int): ParsedDnsUdpPacket? {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val srcPort = readUInt16(packet, ihl)
        val dstPort = readUInt16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return null

        val udpLength = readUInt16(packet, ihl + 4)
        val payloadOffset = ihl + 8
        val payloadLength = (udpLength - 8).coerceAtMost(length - payloadOffset)
        if (payloadLength < 12) return null

        return ParsedDnsUdpPacket(
            isIpv6 = false,
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        )
    }

    private fun parseIpv6Udp(packet: ByteArray, length: Int): ParsedDnsUdpPacket? {
        if (length < 48) return null
        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader != PROTOCOL_UDP) return null

        val udpOffset = 40
        val srcPort = readUInt16(packet, udpOffset)
        val dstPort = readUInt16(packet, udpOffset + 2)
        if (dstPort != DNS_PORT) return null

        val udpLength = readUInt16(packet, udpOffset + 4)
        val payloadOffset = udpOffset + 8
        val payloadLength = (udpLength - 8).coerceAtMost(length - payloadOffset)
        if (payloadLength < 12) return null

        return ParsedDnsUdpPacket(
            isIpv6 = true,
            srcIp = packet.copyOfRange(8, 24),
            dstIp = packet.copyOfRange(24, 40),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        )
    }

    fun buildDnsResponsePacket(request: ParsedDnsUdpPacket, dnsResponse: ByteArray): ByteArray {
        return if (request.isIpv6) {
            buildIpv6UdpResponse(request, dnsResponse)
        } else {
            buildIpv4UdpResponse(request, dnsResponse)
        }
    }

    private fun buildIpv4UdpResponse(request: ParsedDnsUdpPacket, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + dnsResponse.size
        val totalLen = ipHeaderLen + udpLen
        val out = ByteArray(totalLen)

        // IPv4 Header (swap src and dst)
        out[0] = 0x45.toByte() // Version 4, IHL 5
        out[1] = 0x00
        writeUInt16(out, 2, totalLen)
        writeUInt16(out, 4, (System.nanoTime() and 0xFFFF).toInt())
        writeUInt16(out, 6, 0x4000) // Don't Fragment
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        // Checksum initially 0
        System.arraycopy(request.dstIp, 0, out, 12, 4) // new src = old dst
        System.arraycopy(request.srcIp, 0, out, 16, 4) // new dst = old src

        val ipChecksum = computeIpHeaderChecksum(out, 0, ipHeaderLen)
        writeUInt16(out, 10, ipChecksum)

        // UDP Header
        writeUInt16(out, 20, request.dstPort)
        writeUInt16(out, 22, request.srcPort)
        writeUInt16(out, 24, udpLen)
        writeUInt16(out, 26, 0) // checksum placeholder
        System.arraycopy(dnsResponse, 0, out, 28, dnsResponse.size)

        val udpChecksum = computeIpv4UdpChecksum(out, 12, 16, 20, udpLen)
        writeUInt16(out, 26, udpChecksum)
        return out
    }

    private fun buildIpv6UdpResponse(request: ParsedDnsUdpPacket, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = 40
        val udpLen = 8 + dnsResponse.size
        val out = ByteArray(ipHeaderLen + udpLen)

        // IPv6 Header
        out[0] = 0x60.toByte() // Version 6
        out[1] = 0x00
        out[2] = 0x00
        out[3] = 0x00
        writeUInt16(out, 4, udpLen)
        out[6] = PROTOCOL_UDP.toByte()
        out[7] = 64 // Hop limit
        System.arraycopy(request.dstIp, 0, out, 8, 16)
        System.arraycopy(request.srcIp, 0, out, 24, 16)

        // UDP Header
        writeUInt16(out, 40, request.dstPort)
        writeUInt16(out, 42, request.srcPort)
        writeUInt16(out, 44, udpLen)
        writeUInt16(out, 46, 0)
        System.arraycopy(dnsResponse, 0, out, 48, dnsResponse.size)

        val udpChecksum = computeIpv6UdpChecksum(out, 8, 24, 40, udpLen)
        writeUInt16(out, 46, udpChecksum)
        return out
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun computeIpHeaderChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            sum += readUInt16(data, i)
            i += 2
        }
        return finishChecksum(sum)
    }

    private fun computeIpv4UdpChecksum(
        packet: ByteArray,
        srcIpOffset: Int,
        dstIpOffset: Int,
        udpOffset: Int,
        udpLen: Int
    ): Int {
        var sum = 0L
        sum += readUInt16(packet, srcIpOffset)
        sum += readUInt16(packet, srcIpOffset + 2)
        sum += readUInt16(packet, dstIpOffset)
        sum += readUInt16(packet, dstIpOffset + 2)
        sum += PROTOCOL_UDP
        sum += udpLen
        sum += sumBytes(packet, udpOffset, udpLen)
        val result = finishChecksum(sum)
        return if (result == 0) 0xFFFF else result
    }

    private fun computeIpv6UdpChecksum(
        packet: ByteArray,
        srcIpOffset: Int,
        dstIpOffset: Int,
        udpOffset: Int,
        udpLen: Int
    ): Int {
        var sum = 0L
        var i = 0
        while (i < 16) {
            sum += readUInt16(packet, srcIpOffset + i)
            sum += readUInt16(packet, dstIpOffset + i)
            i += 2
        }
        sum += (udpLen ushr 16) and 0xFFFF
        sum += udpLen and 0xFFFF
        sum += PROTOCOL_UDP
        sum += sumBytes(packet, udpOffset, udpLen)
        val result = finishChecksum(sum)
        return if (result == 0) 0xFFFF else result
    }

    private fun sumBytes(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += readUInt16(data, i)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun finishChecksum(initialSum: Long): Int {
        var sum = initialSum
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }
}
