package com.smartcast.live.qa

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class HandRaiseAction(val value: Byte) {
    REQUEST(1),  // Tourist requests to speak
    APPROVE(2),  // Guide accepts the request (carries the PTT sessionId)
    DENY(3),     // Guide denies or cancels
    RELEASE(4)   // Tourist finished speaking
}

/**
 * Wire format (big-endian): clientId(8) | action(1) | sessionId(4)
 * sessionId is only meaningful on APPROVE; it is 0 for every other action.
 * Older 9-byte packets are still accepted and parse with sessionId = 0.
 */
data class HandRaisePacket(
    val clientId: Long,
    val action: HandRaiseAction,
    val sessionId: Int = 0
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(clientId)
        buffer.put(action.value)
        buffer.putInt(sessionId)
        return buffer.array()
    }

    companion object {
        const val SIZE = 13
        private const val LEGACY_SIZE = 9

        fun fromBytes(data: ByteArray, length: Int): HandRaisePacket? {
            if (length < LEGACY_SIZE || length > data.size) return null
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val id = buffer.long
            val actionByte = buffer.get()
            val action = HandRaiseAction.values().find { it.value == actionByte } ?: return null
            val sessionId = if (buffer.remaining() >= 4) buffer.int else 0
            return HandRaisePacket(id, action, sessionId)
        }
    }
}
