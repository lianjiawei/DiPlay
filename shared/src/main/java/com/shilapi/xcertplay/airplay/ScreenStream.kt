package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Ownership transfers to the listener, which must close
         * [accessUnit] after copying it or handing it to another owner.
         */
        fun onFrame(accessUnit: VideoAccessUnit, senderNanos: Long, arrivalNanos: Long) {
            accessUnit.close()
        }
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val buffers = VideoBufferPool()
    private val decryptor = AirPlayCrypto.VideoFrameOpener(key)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
        buffers.close()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", { line -> onDiagnostic("$line ${bufferSummary()}") })
        try {
            val input = sock.getInputStream()
            val header = ByteArray(HEADER_LEN)
            while (!closed.get()) {
                stats.reading()
                if (!readFully(input, header, HEADER_LEN)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) break
                val body = buffers.acquire(bodySize)
                if (!readFully(input, body.bytes, bodySize)) {
                    body.close()
                    break
                }
                val arrivalNanos = System.nanoTime()
                stats.received(HEADER_LEN + bodySize)
                try {
                    onMessage(header, body, stats, arrivalNanos)
                } finally {
                    body.close()
                }
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(
        header: ByteArray,
        body: VideoBufferPool.Lease,
        stats: StreamReceiveStats,
        arrivalNanos: Long,
    ) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val payload = if (body.length >= ScreenCodec.TAG_SIZE) {
                    val start = System.nanoTime()
                    val plain = buffers.acquire(body.length - ScreenCodec.TAG_SIZE)
                    val plainLength = try {
                        decryptor.open(
                            frameCounter.get(),
                            body.bytes,
                            body.length,
                            header,
                            plain.bytes,
                        )
                    } catch (error: Throwable) {
                        plain.close()
                        throw error
                    }
                    stats.decrypted(System.nanoTime() - start, body.length)
                    frameCounter.incrementAndGet()
                    plain to plainLength
                } else {
                    val plain = buffers.acquire(body.length)
                    body.bytes.copyInto(plain.bytes, endIndex = body.length)
                    plain to body.length
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=${body.length} plain=${payload.second} " +
                            "head=${payload.first.bytes.hexPrefix(16, payload.second)} " +
                            "chacha=${decryptor.implementation}",
                    )
                }
                val valid = ScreenCodec.lengthPrefixedToAnnexBInPlace(payload.first.bytes, payload.second)
                val accessUnit = VideoAccessUnit(payload.first, if (valid) payload.second else 0)
                try {
                    listener.onFrame(accessUnit, ScreenCodec.senderNanos(header), arrivalNanos)
                } catch (error: Throwable) {
                    accessUnit.close()
                    throw error
                }
            }
            OP_VIDEO_CONFIG -> {
                val config = body.bytes.copyOf(body.length)
                val (codec, codecData) = ScreenCodec.detectConfig(config)
                Log.i(TAG, "video codec config codec=$codec body=${body.length} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, output: ByteArray, length: Int): Boolean {
        if (length < 0 || length > output.size) return false
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    /** Cumulative allocation counters only; never includes video bytes or network endpoints. */
    private fun bufferSummary(): String {
        val snapshot = buffers.snapshot()
        return "videoBuffers=" +
            "acquire:${snapshot.acquisitions},allocate:${snapshot.allocations},reuse:${snapshot.reuses}," +
            "discard:${snapshot.discardedBuffers},inFlight:${snapshot.activeBuffers}/" +
            "${snapshot.activeBytes},peak:${snapshot.peakActiveBytes},retained:" +
            "${snapshot.retainedBuffers}/${snapshot.retainedBytes},closed:${snapshot.closed} " +
            "videoDecrypt=${decryptor.implementation},fallbacks:${decryptor.platformFallbacks}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int, limit: Int = size): String =
    take(minOf(length, limit)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
     * On a Tang with iOS 27 consecutive main-screen frames are exactly 1/60 s apart (1/30 s on the
     * cluster stream), and skipped frames leave whole multiples.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload
        lengthPrefixedToAnnexBInPlace(payload, payload.size)
        return payload
    }

    /**
     * Validates a complete four-byte-length-prefixed picture and changes only those prefixes.
     * [length] is separate from array capacity because pooled arrays can contain an unused tail.
     */
    internal fun lengthPrefixedToAnnexBInPlace(payload: ByteArray, length: Int): Boolean {
        if (length !in 4..payload.size) return false

        var offset = 0
        while (offset + 4 <= length) {
            val unitLength = readU32Be(payload, offset)
            offset += 4
            if (unitLength <= 0 || unitLength > length - offset) return false
            offset += unitLength
        }
        if (offset != length) return false

        offset = 0
        while (offset + 4 <= length) {
            val unitLength = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + unitLength
        }
        return true
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(limit: Int = size): Boolean =
    limit >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
