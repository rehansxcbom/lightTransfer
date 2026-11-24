package io.github.rehansxcbom.lighttransfer.shared

data class TxqrConfig(
    val chunkSize: Int = 64,
    val redundancyFactor: Double = 2.0,
    val frameIntervalMs: Long = 120L
)

data class FountainPacket(
    val sessionId: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val packetIndex: Int,
    val seed: Long,
    val indices: IntArray,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FountainPacket) return false

        return sessionId == other.sessionId &&
                totalChunks == other.totalChunks &&
                chunkSize == other.chunkSize &&
                packetIndex == other.packetIndex &&
                seed == other.seed &&
                indices.contentEquals(other.indices) &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + totalChunks
        result = 31 * result + chunkSize
        result = 31 * result + packetIndex
        result = 31 * result + seed.hashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class DecodeProgress(
    val sessionId: Long? = null,
    val totalChunks: Int = 0,
    val recoveredChunks: Int = 0,
    val packetsReceived: Int = 0,
    val isComplete: Boolean = false,
    val decodedBytes: ByteArray? = null
) {
    val fraction: Float
        get() = if (totalChunks <= 0) 0f else (recoveredChunks.toFloat() / totalChunks).coerceIn(0f, 1f)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodeProgress) return false

        return sessionId == other.sessionId &&
                totalChunks == other.totalChunks &&
                recoveredChunks == other.recoveredChunks &&
                packetsReceived == other.packetsReceived &&
                isComplete == other.isComplete &&
                decodedBytes.contentEquals(other.decodedBytes)
    }

    override fun hashCode(): Int {
        var result = sessionId?.hashCode() ?: 0
        result = 31 * result + totalChunks
        result = 31 * result + recoveredChunks
        result = 31 * result + packetsReceived
        result = 31 * result + isComplete.hashCode()
        result = 31 * result + (decodedBytes?.contentHashCode() ?: 0)
        return result
    }
}