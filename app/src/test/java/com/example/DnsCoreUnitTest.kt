package com.example

import com.example.data.AddressValidator
import com.example.data.ParsedAddressKind
import com.example.network.DnsWireCodec
import com.example.vpn.PacketParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCoreUnitTest {

    @Test
    fun addressValidator_parsesDomainsIpv4DohAndDot() {
        val domain = AddressValidator.parse("xbox-dns.ru")
        assertNotNull(domain)
        assertEquals("xbox-dns.ru", domain!!.host)
        assertEquals(ParsedAddressKind.DOMAIN, domain.kind)

        val ipv4 = AddressValidator.parse("176.99.11.77")
        assertNotNull(ipv4)
        assertEquals("176.99.11.77", ipv4!!.host)
        assertEquals(ParsedAddressKind.IPV4, ipv4.kind)

        val doh = AddressValidator.parse("https://dns.comss.one/dns-query")
        assertNotNull(doh)
        assertEquals("dns.comss.one", doh!!.host)
        assertEquals("https://dns.comss.one/dns-query", doh.dohUrl)
        assertEquals(ParsedAddressKind.DOH_URL, doh.kind)

        val dot = AddressValidator.parse("tls://dns.geohide.ru")
        assertNotNull(dot)
        assertEquals("dns.geohide.ru", dot!!.host)
        assertEquals(853, dot.port)
        assertEquals(ParsedAddressKind.DOT_HOST, dot.kind)

        assertNull(AddressValidator.parse("not a valid address"))
        assertNull(AddressValidator.parse(""))
    }

    @Test
    fun dnsWireCodec_buildsQueryAndParsesAnswer() {
        val query = DnsWireCodec.buildAQuery("chatgpt.com", transactionId = 0x1234)
        assertTrue(query.size > 16)
        assertEquals(0x12.toByte(), query[0])
        assertEquals(0x34.toByte(), query[1])

        // Synthesize a minimal RFC 1035 response with 1 A record = 104.18.32.47
        val response = query.copyOf(query.size + 16)
        response[2] = 0x81.toByte() // QR=1, RD=1
        response[3] = 0x80.toByte() // RA=1, RCODE=0
        response[6] = 0x00
        response[7] = 0x01 // ANCOUNT=1

        val ansOffset = query.size
        response[ansOffset] = 0xC0.toByte() // Pointer to offset 12
        response[ansOffset + 1] = 0x0C.toByte()
        response[ansOffset + 2] = 0x00
        response[ansOffset + 3] = 0x01 // TYPE A
        response[ansOffset + 4] = 0x00
        response[ansOffset + 5] = 0x01 // CLASS IN
        response[ansOffset + 6] = 0x00
        response[ansOffset + 7] = 0x00
        response[ansOffset + 8] = 0x00
        response[ansOffset + 9] = 0x3C // TTL 60
        response[ansOffset + 10] = 0x00
        response[ansOffset + 11] = 0x04 // RDLENGTH 4
        response[ansOffset + 12] = 104.toByte()
        response[ansOffset + 13] = 18.toByte()
        response[ansOffset + 14] = 32.toByte()
        response[ansOffset + 15] = 47.toByte()

        val parsed = DnsWireCodec.parseResponse(response)
        assertNotNull(parsed)
        assertEquals(0x1234, parsed!!.transactionId)
        assertEquals(0, parsed.rcode)
        assertFalse(parsed.isSinkholed)
        assertEquals(listOf("104.18.32.47"), parsed.ipv4Addresses)
    }

    @Test
    fun packetParser_parsesAndRebuildsIpv4DnsPacket() {
        val dnsQuery = DnsWireCodec.buildAQuery("gemini.google.com", transactionId = 0x4321)
        val totalLen = 20 + 8 + dnsQuery.size
        val ipPacket = ByteArray(totalLen)
        ipPacket[0] = 0x45.toByte()
        ipPacket[2] = ((totalLen ushr 8) and 0xFF).toByte()
        ipPacket[3] = (totalLen and 0xFF).toByte()
        ipPacket[8] = 64
        ipPacket[9] = 17 // UDP
        // src = 10.120.0.1
        ipPacket[12] = 10; ipPacket[13] = 120; ipPacket[14] = 0; ipPacket[15] = 1
        // dst = 10.120.0.2
        ipPacket[16] = 10; ipPacket[17] = 120; ipPacket[18] = 0; ipPacket[19] = 2
        // srcPort = 54321, dstPort = 53
        ipPacket[20] = ((54321 ushr 8) and 0xFF).toByte()
        ipPacket[21] = (54321 and 0xFF).toByte()
        ipPacket[22] = 0
        ipPacket[23] = 53
        val udpLen = 8 + dnsQuery.size
        ipPacket[24] = ((udpLen ushr 8) and 0xFF).toByte()
        ipPacket[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsQuery, 0, ipPacket, 28, dnsQuery.size)

        val parsed = PacketParser.parseDnsQueryPacket(ipPacket, ipPacket.size)
        assertNotNull(parsed)
        assertFalse(parsed!!.isIpv6)
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(dnsQuery, parsed.dnsPayload)

        val replyPacket = PacketParser.buildDnsResponsePacket(parsed, dnsQuery)
        assertEquals(totalLen, replyPacket.size)
        // Swapped src = 10.120.0.2, dst = 10.120.0.1
        assertEquals(2.toByte(), replyPacket[15])
        assertEquals(1.toByte(), replyPacket[19])
        // Swapped srcPort = 53, dstPort = 54321
        assertEquals(53, ((replyPacket[20].toInt() and 0xFF) shl 8) or (replyPacket[21].toInt() and 0xFF))
        assertEquals(54321, ((replyPacket[22].toInt() and 0xFF) shl 8) or (replyPacket[23].toInt() and 0xFF))
    }
}
