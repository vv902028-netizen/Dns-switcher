package com.example.network

import java.io.ByteArrayOutputStream
import kotlin.random.Random

data class DnsWireResponse(
    val transactionId: Int,
    val rcode: Int,
    val ipv4Addresses: List<String>,
    val isSinkholed: Boolean
)

object DnsWireCodec {

    fun buildAQuery(domain: String, transactionId: Int = Random.nextInt(1, 65535)): ByteArray {
        val out = ByteArrayOutputStream(64)
        // Header (12 bytes)
        out.write((transactionId ushr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        // Flags: standard query, recursion desired (0x0100)
        out.write(0x01)
        out.write(0x00)
        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        repeat(6) { out.write(0x00) }

        // QNAME
        val labels = domain.trim().trimEnd('.').split('.')
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) continue
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // root terminator

        // QTYPE = A (1)
        out.write(0x00)
        out.write(0x01)
        // QCLASS = IN (1)
        out.write(0x00)
        out.write(0x01)

        return out.toByteArray()
    }

    fun parseResponse(packet: ByteArray): DnsWireResponse? {
        if (packet.size < 12) return null

        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return null

        val rcode = flags and 0x000F
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

        var offset = 12

        // Skip questions
        for (i in 0 until qdCount) {
            offset = skipName(packet, offset) ?: return null
            if (offset + 4 > packet.size) return null
            offset += 4 // QTYPE + QCLASS
        }

        val ips = mutableListOf<String>()

        // Parse answers
        for (i in 0 until anCount) {
            offset = skipName(packet, offset) ?: break
            if (offset + 10 > packet.size) break
            val type = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val rdLength = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > packet.size) break

            if (type == 1 && rdLength == 4) {
                val b0 = packet[offset].toInt() and 0xFF
                val b1 = packet[offset + 1].toInt() and 0xFF
                val b2 = packet[offset + 2].toInt() and 0xFF
                val b3 = packet[offset + 3].toInt() and 0xFF
                ips.add("$b0.$b1.$b2.$b3")
            }
            offset += rdLength
        }

        val sinkholed = ips.isNotEmpty() && ips.all { isSinkholeIp(it) }
        return DnsWireResponse(
            transactionId = txId,
            rcode = rcode,
            ipv4Addresses = ips,
            isSinkholed = sinkholed
        )
    }

    private fun skipName(packet: ByteArray, startOffset: Int): Int? {
        var offset = startOffset
        var safety = 0
        while (offset < packet.size && safety < 128) {
            val len = packet[offset].toInt() and 0xFF
            if (len == 0) {
                return offset + 1
            }
            if ((len and 0xC0) == 0xC0) {
                return if (offset + 2 <= packet.size) offset + 2 else null
            }
            offset += 1 + len
            safety++
        }
        return null
    }

    private fun isSinkholeIp(ip: String): Boolean {
        return ip == "0.0.0.0" || ip == "127.0.0.1" || ip.startsWith("127.")
    }
}
