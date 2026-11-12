package com.smartcast.live.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HeartbeatPacket(
    val clientId: Long,
    val packetLoss: Byte,
    val jitter: Short
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(11)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putLong(clientId)
        buffer.put(packetLoss)
        buffer.putShort(jitter)

        return buffer.array()
    }

    companion object {
        fun fromBytes(data: ByteArray): HeartbeatPacket? {
            if (data.size < 11) return null

            val buffer = ByteBuffer.wrap(data)
                .order(ByteOrder.BIG_ENDIAN)

            return HeartbeatPacket(
                clientId = buffer.long,
                packetLoss = buffer.get(),
                jitter = buffer.short
            )
        }
    }
}
