package com.smartcast.live.telemetry 

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HeartbeatPacket(
    val clientId: Long,          // Unique random ID generated per receiver app instance
    val packetLossPercent: Byte, // e.g. 0 to 100%
    val jitterMs: Short          // Current jitter in milliseconds
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(clientId)
        buffer.put(packetLossPercent)
        buffer.putShort(jitterMs)
        return buffer.array()
    }

    companion object {
        fun fromBytes(data: ByteArray, length: Int): HeartbeatPacket? {
            if (length < 11) return null
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val id = buffer.long
            val loss = buffer.get()
            val jitter = buffer.short
            return HeartbeatPacket(id, loss, jitter)
        }
    }
}
