package com.shilapi.xcertplay.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarPlayControlDnsTest {
    private val gateway = InetAddress.getByName("192.0.2.1")
    private val phoneId = "AA:BB:CC:DD:EE:FF"
    private val service = "_carplay-ctrl._tcp.local"
    private val instance = "Phone.$service"
    private val host = "phone.local"

    private data class Rr(val name: String, val type: Int, val data: ByteArray, val ttl: Int = 120)
    private fun body(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray()
    private fun DataOutputStream.name(text: String) {
        text.split('.').forEach { label -> val bytes = label.toByteArray(); writeByte(bytes.size); write(bytes) }
        writeByte(0)
    }
    private fun records(id: String = phoneId, address: InetAddress = gateway, port: Int = 49153): List<Rr> = listOf(
        Rr(service, 12, body { name(instance) }),
        Rr(instance, 33, body { writeShort(0); writeShort(0); writeShort(port); name(host) }),
        Rr(instance, 16, body { val text = "id=$id".toByteArray(); writeByte(text.size); write(text) }),
        Rr(host, 1, address.address),
    )
    private fun packet(records: List<Rr>, id: Int = 123): ByteArray = body {
        writeShort(id); writeShort(0x8400); writeShort(0); writeShort(records.size); writeShort(0); writeShort(0)
        records.forEach { rr ->
            name(rr.name); writeShort(rr.type); writeShort(0x8001); writeInt(rr.ttl)
            writeShort(rr.data.size); write(rr.data)
        }
    }
    private fun answers(records: List<Rr>): CarPlayControlDns.Answers = CarPlayControlDns.Answers().apply {
        add(CarPlayControlDns.response(packet(records), 123))
    }

    @Test fun completeDiscoveryUsesTheAdvertisedPortAndMatchesThePairedPhone() {
        val endpoint = answers(records()).endpoints(gateway, "aabbccddeeff").single()
        assertEquals(49153, endpoint.port)
        assertEquals("192.0.2.1", endpoint.host)
        assertTrue(answers(records()).endpoints(gateway, "11:22:33:44:55:66").isEmpty())
        assertTrue(answers(records(id = "not-a-bluetooth-id")).endpoints(gateway, phoneId).isEmpty())
    }

    @Test fun gatewayAddressAloneOrUnrelatedHostNeverBecomesAControlEndpoint() {
        for (missing in 0..3) {
            assertTrue(answers(records().filterIndexed { index, _ -> index != missing }).endpoints(gateway, phoneId).isEmpty())
        }
        assertTrue(answers(records(address = InetAddress.getByName("192.0.2.88"))).endpoints(gateway, phoneId).isEmpty())
        assertTrue(answers(records(port = 0)).endpoints(gateway, phoneId).isEmpty())
        assertTrue(answers(records().map { it.copy(ttl = 0) }).endpoints(gateway, phoneId).isEmpty())
    }

    @Test fun splitAnswersRequestMissingSrvTxtAndAddressAndCombineWithinTheLookup() {
        val wire = records()
        val result = answers(wire.take(1))
        assertEquals(setOf(33, 16), result.followUpQuestions().map { it.type }.toSet())
        result.add(CarPlayControlDns.response(packet(wire.subList(1, 3)), 123))
        assertEquals(listOf(1), result.followUpQuestions().map { it.type })
        result.add(CarPlayControlDns.response(packet(wire.takeLast(1)), 123))
        assertEquals(1, result.endpoints(gateway, phoneId).size)
        assertTrue(result.followUpQuestions().isEmpty())
    }

    @Test fun rejectsWrongTransactionTruncationCompressionLoopsAndBrokenTxt() {
        assertTrue(CarPlayControlDns.response(packet(records(), 124), 123).isEmpty())
        val valid = packet(records())
        for (length in valid.indices) assertTrue(CarPlayControlDns.response(valid.copyOf(length), 123).isEmpty())
        val loop = body {
            writeShort(123); writeShort(0x8400); writeShort(1); writeShort(0); writeShort(0); writeShort(0)
            writeByte(0xc0); writeByte(12); writeShort(12); writeShort(1)
        }
        assertTrue(CarPlayControlDns.response(loop, 123).isEmpty())
        val malformed = records().map { if (it.type == 16) it.copy(data = byteArrayOf(100, 1)) else it }
        assertTrue(CarPlayControlDns.response(packet(malformed), 123).isEmpty())
        val truncated = valid.copyOf().apply { this[2] = 0x86.toByte() }
        assertTrue(CarPlayControlDns.response(truncated, 123).isEmpty())
    }

    @Test fun acceptsCompressedOwnerAndChecksEveryBoundBeforeFollowingPointers() {
        val compressed = body {
            writeShort(123); writeShort(0x8400); writeShort(1); writeShort(1); writeShort(0); writeShort(0)
            name(service); writeShort(12); writeShort(1)
            writeByte(0xc0); writeByte(12); writeShort(12); writeShort(1); writeInt(120)
            val value = body { writeByte(5); writeBytes("Phone"); writeByte(0xc0); writeByte(12) }
            writeShort(value.size); write(value)
        }
        val values = CarPlayControlDns.response(compressed, 123)
        assertEquals(1, values.size)
        assertEquals(CarPlayControlDns.service, values.single().name)
        assertEquals(listOf("phone") + CarPlayControlDns.service, values.single().value)
        val random = kotlin.random.Random(7)
        repeat(1000) { CarPlayControlDns.response(random.nextBytes(random.nextInt(0, 256)), 123) }
    }

    @Test fun queryUsesAnEphemeralUnicastCompatibleIdAndDnsSdQuestion() {
        val query = CarPlayControlDns.query(123, listOf(CarPlayControlDns.Question(CarPlayControlDns.service, 12)))
        assertArrayEquals(body {
            writeShort(123); writeShort(0); writeShort(1); writeShort(0); writeShort(0); writeShort(0)
            name(service); writeShort(12); writeShort(1)
        }, query)
    }

    @Test fun loopbackLookupFollowsTheResponseAndCancellationClosesItsSocket() {
        val local = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, local).use { server ->
            server.soTimeout = 3000
            val latch = CountDownLatch(1)
            val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val found = java.util.concurrent.CopyOnWriteArrayList<CarPlayBonjourEndpoint>()
            var binds = 0
            GatewayCarPlayDiscovery(local, listOf(local), phoneId, { binds++ }, { endpoint, _ ->
                found.add(endpoint); latch.countDown()
            }, logs::add, server.localPort).use { discovery ->
                discovery.start()
                discovery.start() // Idempotent after repeated StartSession messages.
                val query = DatagramPacket(ByteArray(2048), 2048)
                server.receive(query)
                val id = ((query.data[0].toInt() and 255) shl 8) or (query.data[1].toInt() and 255)
                val reply = packet(records(address = local), id)
                server.send(DatagramPacket(reply, reply.size, query.address, query.port))
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals(49153, found.single().port)
                assertEquals(1, binds)
                assertTrue(logs.any { it.contains("stage=matched") })
                assertTrue(logs.none { it.contains(phoneId) || it.contains("phone.local") })
            }
            val waiting = GatewayCarPlayDiscovery(local, listOf(local), phoneId, {}, { _, _ -> fail() }, {}, server.localPort)
            waiting.start()
            server.receive(DatagramPacket(ByteArray(2048), 2048))
            val start = System.nanoTime()
            waiting.close()
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
        }
    }

    @Test fun bootstrapCarriesBothServedFamiliesWithoutChangingLegacyHotspots() {
        val ipv4 = InetAddress.getByName("192.0.2.10")
        val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 7)
        fun info(managed: Boolean) = WirelessHotspotInfo("Phone", if (managed) "" else "password",
            if (managed) com.shilapi.xcertplay.transport.Iap2WirelessSecurity.SYSTEM_MANAGED
            else com.shilapi.xcertplay.transport.Iap2WirelessSecurity.WPA_WPA2,
            36, 5180, null, "wlan0", ipv4, "5 GHz", WirelessHotspotBackend.EXISTING_WIFI,
            listOf(ipv4, ipv6), configureWifi = !managed)
        val values = wirelessBootstrapAddresses(info(true), ipv6)
        assertEquals(listOf(ipv6.hostAddress!!.substringBefore('%'), "192.0.2.10"), values)
        assertTrue(values.none { '%' in it })
        assertEquals(listOf("192.0.2.10"), wirelessBootstrapAddresses(info(false), ipv4))
    }
}
