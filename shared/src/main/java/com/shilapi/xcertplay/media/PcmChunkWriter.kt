package com.shilapi.xcertplay.media

/**
 * Writes PCM in small non-blocking chunks so a full AudioTrack buffer is retried instead of
 * stalling the renderer thread. Preparation always starts from raw PCM, so partial writes never
 * compound gain. Callers can refresh preparation on retries to follow live mute/gain changes.
 */
internal class PcmChunkWriter(
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val stallTimeoutNs: Long = DEFAULT_STALL_TIMEOUT_NS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: () -> Unit = ::pauseOneMillisecond,
) {
    data class Result(
        val bytesWritten: Int,
        val zeroWrites: Int,
        val partialWrites: Int,
        val writeError: Int?,
        val fellBackToBlocking: Boolean,
    )

    private var blocking = false
    private var scratch = ByteArray(chunkBytes)

    val usesNonBlocking: Boolean get() = !blocking

    fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
        prepareChunk: (ByteArray, Int) -> Unit,
        writeChunk: (ByteArray, Int, Int, Boolean) -> Int,
        afterWrite: (ByteArray, Int, Int) -> Unit,
        shouldContinue: () -> Boolean,
        refreshChunkOnRetry: Boolean = false,
    ): Result {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        if (length == 0) return Result(0, 0, 0, null, false)
        val requiredScratch = minOf(chunkBytes, length)
        if (scratch.size < requiredScratch) scratch = ByteArray(requiredScratch)

        var completed = 0
        var zeroWrites = 0
        var partialWrites = 0
        var writeError: Int? = null
        var fellBackToBlocking = false

        while (completed < length && shouldContinue()) {
            val sourceOffset = offset + completed
            val chunkLength = minOf(chunkBytes, length - completed)
            System.arraycopy(data, sourceOffset, scratch, 0, chunkLength)
            prepareChunk(scratch, chunkLength)

            var chunkWritten = 0
            var stalledSinceNs: Long? = null
            var retry = false
            while (chunkWritten < chunkLength && shouldContinue()) {
                if (retry && refreshChunkOnRetry) {
                    // Live mute/gain can change while a non-blocking write waits. Rebuild from
                    // raw PCM so the unwritten tail adopts it without compounding attenuation.
                    System.arraycopy(data, sourceOffset, scratch, 0, chunkLength)
                    prepareChunk(scratch, chunkLength)
                }
                retry = true
                val remaining = chunkLength - chunkWritten
                val count = writeChunk(scratch, chunkWritten, remaining, blocking)
                if (count < 0) {
                    writeError = count
                    return Result(completed, zeroWrites, partialWrites, writeError,
                        fellBackToBlocking)
                }
                if (count == 0) {
                    zeroWrites++
                    if (blocking) {
                        // A blocking write should wait for space itself. A vendor track that
                        // still returns zero cannot be drained by spinning here indefinitely.
                        return Result(completed, zeroWrites, partialWrites, null,
                            fellBackToBlocking)
                    }
                    if (!blocking) {
                        val now = nanoTime()
                        val stalledSince = stalledSinceNs
                        if (stalledSince == null) {
                            stalledSinceNs = now
                        } else if (now - stalledSince >= stallTimeoutNs) {
                            blocking = true
                            fellBackToBlocking = true
                        }
                    }
                    pause()
                    continue
                }

                val accepted = minOf(count, remaining)
                if (accepted < remaining) partialWrites++
                afterWrite(scratch, chunkWritten, accepted)
                chunkWritten += accepted
                completed += accepted
                stalledSinceNs = null
            }
        }

        return Result(completed, zeroWrites, partialWrites, writeError, fellBackToBlocking)
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 2 * 1024
        const val DEFAULT_STALL_TIMEOUT_NS = 100_000_000L

        fun pauseOneMillisecond() {
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
