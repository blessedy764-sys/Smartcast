package com.smartcast.live.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/** Sliding 64-packet replay window for one PTT session. */
class ReplayWindow {
    private var highest = -1
    private var bitmap = 0L // bit 0 = highest, bit n = highest - n

    @Synchronized
    fun accept(seq: Int): Boolean {
        if (highest < 0) {
            highest = seq
            bitmap = 1L
            return true
        }
        if (seq > highest) {
            val shift = seq - highest
            bitmap = if (shift >= 64) 1L else (bitmap shl shift) or 1L
            highest = seq
            return true
        }
        val diff = highest - seq
        if (diff >= 64) return false              // too old
        val mask = 1L shl diff
        if (bitmap and mask != 0L) return false   // duplicate
        bitmap = bitmap or mask
        return true
    }
}

/** Client side: one instance per PTT session. */
class PttSender(
    roomKey: SecretKey,
    val sessionId: Int, // assigned by the host
    private val channelId: Byte
) {
    private val key = PttCrypto.deriveKey(roomKey)
    private var seq = 0

    @Synchronized
    fun seal(opusPayload: ByteArray, timestamp: Long, flags: Byte = 0): ByteArray {
        check(seq <= PttPacket.MAX_SEQ) {
            "PTT session exhausted; request a new session ID from the host"
        }
        val packet = PttPacket(seq++, timestamp, channelId, flags, sessionId, opusPayload)
        return PttCrypto.encrypt(key, packet)
    }
}

/** Host side: drops unknown sessions, bad tags, and replays. */
class PttReceiver(roomKey: SecretKey) {
    private val key = PttCrypto.deriveKey(roomKey)
    private val sessions = ConcurrentHashMap<Int, ReplayWindow>()

    fun openSession(sessionId: Int) {
        sessions[sessionId] = ReplayWindow()
    }

    fun closeSession(sessionId: Int) {
        sessions.remove(sessionId)
    }

    fun receive(data: ByteArray, length: Int): PttPacket? {
        if (length < PttPacket.MIN_SIZE || length > data.size) return null
        // Cheap check before any crypto work.
        val sessionId = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val window = sessions[sessionId] ?: return null
        val packet = PttCrypto.decrypt(key, data, length) ?: return null
        // Replay check only after authentication, so forged packets can't poison the window.
        return if (window.accept(packet.sequenceNumber)) packet else null
    }
}
