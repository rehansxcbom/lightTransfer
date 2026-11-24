package io.github.rehansxcbom.lighttransfer.shared

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FountainCodecTest {

    @Test
    fun roundTripWithOutOfOrderAndDroppedPackets() {
        val message = "Hello from Kotlin Multiplatform txqr across Android, iOS, and Desktop!"
        val inputBytes = message.encodeToByteArray()
        val config = TxqrConfig(chunkSize = 12, redundancyFactor = 2.5)

        val encoder = TxqrEncoder(
            rawData = inputBytes,
            sessionId = 0x123456789ABCDEF0L,
            config = config
        )

        val decoder = TxqrDecoder(expectedOriginalLength = inputBytes.size)
        val packets = encoder.generateStream().toList().shuffled()

        // Drop every 3rd packet to simulate missed camera frames
        val transmitted = packets.filterIndexed { idx, _ -> idx % 3 != 0 }

        var progress = DecodeProgress()
        for (pkt in transmitted) {
            val encodedText = `PacketCodec.kt`.encodeToString(pkt)
            val decodedPkt = assertNotNull(`PacketCodec.kt`.decodeFromString(encodedText))
            progress = decoder.ingest(decodedPkt)
            if (progress.isComplete) break
        }

        assertTrue(progress.isComplete, "Decoder should complete recovery despite dropped & shuffled packets")
        assertNotNull(progress.decodedBytes)
        assertContentEquals(inputBytes, progress.decodedBytes)
        assertEquals(message, progress.decodedBytes!!.decodeToString())
    }
}