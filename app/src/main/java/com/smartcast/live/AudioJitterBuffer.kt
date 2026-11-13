package com.smartcast.live.audio

import io.github.jaredmdobson.concentus.OpusDecoder
import java.util.concurrent.atomic.AtomicLong

class AudioJitterBuffer(
    private val targetBufferFrames: Int = 3,  // 3 x 20 ms = 60 ms
    private val maxBufferFrames: Int = 12,    // 240 ms cap
    private val maxConcealFrames: Int = 5     // then wait for fresh audio
) {
    private val lock = Any()
    private val packets = HashMap<Int, ByteArray>()
    private var hasSeq = false
    private var nextSeq = 0
    private var buffering = true
    private var concealed = 0

    private val received = AtomicLong()
    private val lost = AtomicLong()
    private val recovered = AtomicLong()
    val packetsReceived: Long get() = received.get()
    val packetsLost: Long get() = lost.get()
    val fecRecovered: Long get() = recovered.get()

    /** Network thread: add one decrypted Opus payload. */
    fun push(sequence: Int, opus: ByteArray) {
        received.incrementAndGet()
        synchronized(lock) {
            if (!hasSeq) { nextSeq = sequence; hasSeq = true }
            val ahead = sequence - nextSeq // signed difference
            if (ahead < 0) return          // too late, already played
            if (ahead > maxBufferFrames || packets.size >= maxBufferFrames) {
                // sender jumped or a burst arrived: restart around this packet
                packets.clear()
                nextSeq = sequence
                buffering = true
                concealed = 0
            }
            packets[sequence] = opus
            if (buffering && packets.size >= targetBufferFrames) buffering = false
        }
    }

    /**
     * Playback thread: decode the next 20 ms frame into [out].
     * Returns the sample count, or 0 while buffering.
     */
    fun popAndDecode(decoder: OpusDecoder, out: ShortArray, frameSize: Int): Int {
        var payload: ByteArray? = null
        var useFec = false
        synchronized(lock) {
            if (buffering || !hasSeq) return 0
            val current = packets.remove(nextSeq)
            if (current != null) {
                payload = current
                concealed = 0
            } else if (packets.isEmpty()) {
                if (++concealed > maxConcealFrames) {
                    // sender paused or link dropped: re-sync instead of concealing forever
                    hasSeq = false
                    buffering = true
                    concealed = 0
                    return 0
                }
                lost.incrementAndGet() // plain concealment
            } else {
                val next = packets[nextSeq + 1]
                if (next != null) {
                    payload = next      // recover the lost frame from FEC data
                    useFec = true
                    recovered.incrementAndGet()
                } else {
                    lost.incrementAndGet()
                }
            }
            nextSeq++
        }
        return try {
            decoder.decode(payload, 0, payload?.size ?: 0, out, 0, frameSize, useFec)
        } catch (e: Exception) { 0 }
    }

    fun reset() {
        synchronized(lock) {
            packets.clear()
            hasSeq = false
            buffering = true
            concealed = 0
        }
    }
} 
