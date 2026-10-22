package com.shilapi.xcertplay.network

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Supplement multicast discovery after Bluetooth StartSession; never treats a router as a phone. */
internal class GatewayCarPlayDiscovery(
    private val localAddress: InetAddress,
    private val gateways: List<InetAddress>,
    private val targetBluetoothId: String,
    private val bindSocket: (DatagramSocket) -> Unit,
    private val onEndpoint: (CarPlayBonjourEndpoint, InetAddress) -> Unit,
    private val log: (String) -> Unit,
    private val discoveryPort: Int = 5353,
) : Closeable {
    private val started = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    private val lock = Any()

    fun start() = synchronized(lock) {
        if (closed || gateways.isEmpty() || CarPlayControlDns.bluetoothId(targetBluetoothId) == null ||
            !started.compareAndSet(false, true)) return@synchronized
        worker = Thread({
            try {
                for (attempt in 1..3) {
                    if (closed) break
                    for (gateway in gateways.take(2)) {
                        if (closed) break
                        if (lookup(gateway, attempt)) return@Thread
                    }
                    if (!closed && attempt < 3) Thread.sleep(1500L)
                }
            } catch (_: InterruptedException) {
                // close interrupts only this lookup; regular Bonjour continues independently.
            }
        }, "carplay-hotspot-discovery").apply { isDaemon = true; start() }
    }

    private fun lookup(gateway: InetAddress, attempt: Int): Boolean {
        val answers = CarPlayControlDns.Answers()
        var responses = 0
        try {
            DatagramSocket(null).use { udp ->
                synchronized(lock) {
                    if (closed) return false
                    socket = udp
                }
                bindSocket(udp)
                udp.bind(InetSocketAddress(localAddress, 0))
                udp.connect(gateway, discoveryPort) // Connected UDP rejects replies from another address/port.
                val id = Random.nextInt(1, 65536)
                fun send(questions: List<CarPlayControlDns.Question>) {
                    val bytes = CarPlayControlDns.query(id, questions)
                    udp.send(DatagramPacket(bytes, bytes.size))
                }
                send(listOf(CarPlayControlDns.Question(CarPlayControlDns.service, 12)))
                log("gateway discovery stage=query attempt=$attempt")
                val deadline = System.nanoTime() + 2_500_000_000L
                var lastQuestions = emptyList<CarPlayControlDns.Question>()
                while (!closed && responses < 16) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) break
                    udp.soTimeout = remaining.coerceAtMost(500).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(ByteArray(9001), 9001)
                    try { udp.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    responses++
                    val records = CarPlayControlDns.response(packet.data.copyOf(packet.length), id)
                    answers.add(records)
                    val endpoints = answers.endpoints(gateway, targetBluetoothId)
                    if (endpoints.isNotEmpty()) {
                        log("gateway discovery stage=matched attempt=$attempt endpoints=${endpoints.size}")
                        endpoints.forEach { if (!closed) onEndpoint(it, gateway) }
                        return true
                    }
                    val followUp = answers.followUpQuestions()
                    if (followUp.isNotEmpty() && followUp != lastQuestions) {
                        send(followUp)
                        lastQuestions = followUp
                    }
                }
                log("gateway discovery stage=no_verified_service attempt=$attempt responses=$responses ${answers.diagnostic(targetBluetoothId)}")
            }
        } catch (error: IOException) {
            if (!closed) log("gateway discovery stage=io_failure attempt=$attempt failureClass=${error.javaClass.simpleName}")
        } catch (error: RuntimeException) {
            if (!closed) log("gateway discovery stage=platform_failure attempt=$attempt failureClass=${error.javaClass.simpleName}")
        } finally {
            synchronized(lock) { socket = null }
        }
        return false
    }

    /** Session callbacks must cancel I/O without waiting for the discovery worker. */
    fun cancel() = synchronized(lock) {
        closed = true
        socket?.close()
        socket = null
        worker?.interrupt()
    }

    override fun close() {
        cancel()
        val thread = synchronized(lock) { worker.also { worker = null } }
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
