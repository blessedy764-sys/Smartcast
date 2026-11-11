package com.gitco.smartcast.audio

import com.gitco.smartcast.network.SmartCastPacket
import java.util.concurrent.ConcurrentHashMap

class AudioJitterBuffer(
    private val targetBufferFrames: Int = 3, // 3 * 20ms = 60ms latency
    private val maxBufferFrames: Int = 12    // 240ms cap to prevent runaway delay
) {
    private val packetMap = ConcurrentHashMap<Int, SmartCastPacket>()
    private var nextPlaySeq: Int = -1
    private var isBuffering: Boolean = true

    // Telemetry counters for your UI diagnostics
    var packetsReceived: Long = 0L
        private set
    var packetsLost: Long = 0L
        private set
    var fecRecovered: Long = 0L
        private set

    fun push(packet: SmartCastPacket) {
        packetsReceived++
        val seq = packet.sequenceNumber

        if (nextPlaySeq == -1) {
            nextPlaySeq = seq
        }

        // Drop packets that arrived too late (accounting for 16-bit wrap-around)
        if (seqDiff(seq, nextPlaySeq) < 0) return

        // Prevent buffer bloat if Wi-Fi stalls and dumps a burst
        if (packetMap.size >= maxBufferFrames) {
            packetMap.clear()
            nextPlaySeq = seq
            isBuffering = true
        }

        packetMap[seq] = packet

        if (isBuffering && packetMap.size >= targetBufferFrames) {
            isBuffering = false
        }
    }

    /**
     * Called every 20ms by the AudioTrack playback loop.
     * Returns decoded 16-bit PCM samples (or null while initial buffer fills).
     */
    fun popAndDecode(
        decoderPtr: Long,
        frameSize: Int,
        decodeFn: (Long, ByteArray?, Int, Boolean) -> ShortArray?
    ): ShortArray? {
        if (isBuffering) return null

        val currentPacket = packetMap.remove(nextPlaySeq)
        if (currentPacket != null) {
            nextPlaySeq = (nextPlaySeq + 1) and 0xFFFF
            return decodeFn(decoderPtr, currentPacket.opusPayload, frameSize, false)
        }

        // Current packet is missing! Check if next packet (seq + 1) arrived for In-Band FEC
        val lookaheadSeq = (nextPlaySeq + 1) and 0xFFFF
        val nextPacket = packetMap[lookaheadSeq]
        nextPlaySeq = lookaheadSeq

        return if (nextPacket != null) {
            fecRecovered++
            // Recover the missing frame using FEC data embedded inside nextPacket
            decodeFn(decoderPtr, nextPacket.opusPayload, frameSize, true)
        } else {
            packetsLost++
            // Both missing -> Run Opus PLC (pass null payload)
            decodeFn(decoderPtr, null, frameSize, false)
        }
    }

    fun reset() {
        packetMap.clear()
        nextPlaySeq = -1
        isBuffering = true
    }

    /** Signed circular difference for 16-bit sequence numbers (0..65535). */
    private fun seqDiff(a: Int, b: Int): Int {
        val diff = (a - b) and 0xFFFF
        return if (diff > 32767) diff - 65536 else diff
    }
}
