package io.github.rehansxcbom.lighttransfer.shared

/**
 * Serializes and parses FountainPacket instances for QR code encoding/decoding
 * using pure multiplatform Kotlin (no platform-specific Base64 needed).
 *
 * Format:
 * TXQR1|<sessionIdHex>|<totalChunks>|<chunkSize>|<packetIndex>|<seedHex>|<idx0,idx1,...>|<payloadHex>
 */
object PacketCodec {
    private const val PREFIX = "TXQR1"
    private const val HEX_CHARS = "0123456789ABCDEF"

    fun encodeToString(packet: FountainPacket): String {
        val indicesPart = packet.indices.joinToString(",")
        val payloadHex = bytesToHex(packet.payload)
        return buildString {
            append(PREFIX).append('|')
            append(packet.sessionId.toULong().toString(16).uppercase()).append('|')
            append(packet.totalChunks).append('|')
            append(packet.chunkSize).append('|')
            append(packet.packetIndex).append('|')
            append(packet.seed.toULong().toString(16).uppercase()).append('|')
            append(indicesPart).append('|')
            append(payloadHex)
        }
    }

    fun decodeFromString(raw: String): FountainPacket? {
        val trimmed = raw.trim()
        val parts = trimmed.split('|')
        if (parts.size != 8 || parts[0] != PREFIX) return null

        val sessionId = parts[1].toULongOrNull(16)?.toLong() ?: return null
        val totalChunks = parts[2].toIntOrNull() ?: return null
        val chunkSize = parts[3].toIntOrNull() ?: return null
        val packetIndex = parts[4].toIntOrNull() ?: return null
        val seed = parts[5].toULongOrNull(16)?.toLong() ?: return null

        if (totalChunks <= 0 || chunkSize <= 0) return null

        val indices = if (parts[6].isEmpty()) {
            intArrayOf()
        } else {
            val parsed = parts[6].split(',').map { it.toIntOrNull() ?: return null }
            parsed.toIntArray()
        }

        val payload = hexToBytes(parts[7]) ?: return null
        if (payload.size != chunkSize) return null

        return FountainPacket(
            sessionId = sessionId,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            packetIndex = packetIndex,
            seed = seed,
            indices = indices,
            payload = payload
        )
    }

    fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return out.concatToString()
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val len = hex.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = hexDigitValue(hex[i * 2]) ?: return null
            val lo = hexDigitValue(hex[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigitValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> 10 + (c - 'A')
        in 'a'..'f' -> 10 + (c - 'a')
        else -> null
    }
}