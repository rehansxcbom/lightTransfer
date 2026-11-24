package io.github.rehansxcbom.lighttransfer.shared

/**
 * Deterministic 64-bit PRNG (SplitMix64) so chunk selection is identical
 * across Android, iOS, and Desktop targets.
 */
class SeededRandom(initialSeed: Long) {
    private var state: Long = initialSeed

    fun nextLong(): Long {
        state += -0x1c8b431b5102d307L // 0x9E3779B97F4A7C15L as signed Long
        var z = state
        z = (z xor (z ushr 30)) * -0x4650e865f1548fe5L // 0xBF58476D1CE4E5B9L
        z = (z xor (z ushr 27)) * -0x77c74598298a2245L // 0x94D049BB133111EBL
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "Bound must be positive, got $bound" }
        val r = nextLong() ushr 1
        return (r % bound.toLong()).toInt()
    }
}

class TxqrEncoder(
    private val rawData: ByteArray,
    private val sessionId: Long,
    private val config: TxqrConfig = TxqrConfig()
) {
    val totalChunks: Int = if (rawData.isEmpty()) 1 else ((rawData.size + config.chunkSize - 1) / config.chunkSize)
    val chunkSize: Int = config.chunkSize

    private val chunks: Array<ByteArray> = Array(totalChunks) { chunkIdx ->
        val out = ByteArray(chunkSize)
        val start = chunkIdx * chunkSize
        if (start < rawData.size) {
            val end = minOf(start + chunkSize, rawData.size)
            rawData.copyInto(destination = out, destinationOffset = 0, startIndex = start, endIndex = end)
        }
        out
    }

    fun totalPacketsToEmit(): Int {
        val count = kotlin.math.ceil(totalChunks * config.redundancyFactor).toInt()
        return maxOf(totalChunks, count)
    }

    fun createPacket(packetIndex: Int, seed: Long = sessionId xor (packetIndex.toLong() * 0x517cc1b727220a95L)): FountainPacket {
        val rng = SeededRandom(seed)
        val degree = chooseDegree(rng, totalChunks)
        val indices = pickDistinctIndices(rng, totalChunks, degree)

        val xored = ByteArray(chunkSize)
        for (idx in indices) {
            val chunk = chunks[idx]
            for (b in 0 until chunkSize) {
                val a = xored[b].toInt() and 0xFF
                val bVal = chunk[b].toInt() and 0xFF
                xored[b] = (a xor bVal).toByte()
            }
        }

        return FountainPacket(
            sessionId = sessionId,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            packetIndex = packetIndex,
            seed = seed,
            indices = indices,
            payload = xored
        )
    }

    fun generateStream(): Sequence<FountainPacket> = sequence {
        val total = totalPacketsToEmit()
        for (i in 0 until total) {
            yield(createPacket(i))
        }
    }

    private fun chooseDegree(rng: SeededRandom, maxChunks: Int): Int {
        if (maxChunks <= 1) return 1
        // Lightweight fountain-like degree distribution favoring small degrees (1..3)
        // for fast peeling decoding while adding multi-chunk redundancy.
        val roll = rng.nextInt(100)
        val rawDegree = when {
            roll < 45 -> 1
            roll < 75 -> 2
            roll < 90 -> 3
            else -> 1 + rng.nextInt(minOf(maxChunks, 5))
        }
        return rawDegree.coerceIn(1, maxChunks)
    }

    private fun pickDistinctIndices(rng: SeededRandom, maxChunks: Int, degree: Int): IntArray {
        val selected = LinkedHashSet<Int>(degree)
        while (selected.size < degree) {
            selected.add(rng.nextInt(maxChunks))
        }
        return selected.toIntArray()
    }
}

class TxqrDecoder(
    private val expectedOriginalLength: Int? = null
) {
    private data class PendingPacket(
        val remainingIndices: MutableSet<Int>,
        val payload: ByteArray
    )

    private var activeSessionId: Long? = null
    private var totalChunks: Int = 0
    private var chunkSize: Int = 0
    private var packetsReceived: Int = 0

    private var solvedChunks: Array<ByteArray?> = emptyArray()
    private var solvedCount: Int = 0
    private val pendingPackets = mutableListOf<PendingPacket>()

    fun reset() {
        activeSessionId = null
        totalChunks = 0
        chunkSize = 0
        packetsReceived = 0
        solvedChunks = emptyArray()
        solvedCount = 0
        pendingPackets.clear()
    }

    fun ingest(packet: FountainPacket): DecodeProgress {
        if (activeSessionId == null) {
            activeSessionId = packet.sessionId
            totalChunks = packet.totalChunks
            chunkSize = packet.chunkSize
            solvedChunks = arrayOfNulls(totalChunks)
        } else if (activeSessionId != packet.sessionId || totalChunks != packet.totalChunks || chunkSize != packet.chunkSize) {
            // Ignore packets from a mismatched session/layout
            return currentProgress()
        }

        packetsReceived++

        if (solvedCount == totalChunks) {
            return currentProgress()
        }

        val unresolvedIndices = LinkedHashSet<Int>()
        val workingPayload = packet.payload.copyOf()

        for (idx in packet.indices) {
            if (idx !in 0 until totalChunks) continue
            val known = solvedChunks[idx]
            if (known != null) {
                xorInto(workingPayload, known)
            } else {
                unresolvedIndices.add(idx)
            }
        }

        when (unresolvedIndices.size) {
            0 -> Unit // Redundant packet already covered by solved chunks
            1 -> {
                val targetIdx = unresolvedIndices.first()
                solveChunk(targetIdx, workingPayload)
                propagateSolvedChunks()
            }
            else -> {
                pendingPackets.add(PendingPacket(unresolvedIndices, workingPayload))
            }
        }

        return currentProgress()
    }

    private fun solveChunk(index: Int, data: ByteArray) {
        if (solvedChunks[index] != null) return
        solvedChunks[index] = data.copyOf()
        solvedCount++
    }

    private fun propagateSolvedChunks() {
        var progressMade = true
        while (progressMade && solvedCount < totalChunks) {
            progressMade = false
            val iterator = pendingPackets.iterator()
            val newlySolved = mutableListOf<Pair<Int, ByteArray>>()

            while (iterator.hasNext()) {
                val pending = iterator.next()
                val solvedInThisPacket = pending.remainingIndices.filter { solvedChunks[it] != null }
                for (knownIdx in solvedInThisPacket) {
                    val knownBytes = solvedChunks[knownIdx]!!
                    xorInto(pending.payload, knownBytes)
                    pending.remainingIndices.remove(knownIdx)
                    progressMade = true
                }

                if (pending.remainingIndices.isEmpty()) {
                    iterator.remove()
                } else if (pending.remainingIndices.size == 1) {
                    val onlyIdx = pending.remainingIndices.first()
                    newlySolved.add(onlyIdx to pending.payload.copyOf())
                    iterator.remove()
                    progressMade = true
                }
            }

            for ((idx, bytes) in newlySolved) {
                solveChunk(idx, bytes)
            }
        }
    }

    private fun xorInto(target: ByteArray, source: ByteArray) {
        val len = minOf(target.size, source.size)
        for (i in 0 until len) {
            val a = target[i].toInt() and 0xFF
            val b = source[i].toInt() and 0xFF
            target[i] = (a xor b).toByte()
        }
    }

    fun currentProgress(): DecodeProgress {
        val complete = totalChunks > 0 && solvedCount == totalChunks
        val assembled = if (complete) assembleBytes() else null
        return DecodeProgress(
            sessionId = activeSessionId,
            totalChunks = totalChunks,
            recoveredChunks = solvedCount,
            packetsReceived = packetsReceived,
            isComplete = complete,
            decodedBytes = assembled
        )
    }

    private fun assembleBytes(): ByteArray {
        val full = ByteArray(totalChunks * chunkSize)
        for (i in 0 until totalChunks) {
            val chunk = solvedChunks[i] ?: ByteArray(chunkSize)
            chunk.copyInto(destination = full, destinationOffset = i * chunkSize)
        }
        val targetLen = expectedOriginalLength?.coerceIn(0, full.size) ?: full.size
        return full.copyOf(targetLen)
    }
}