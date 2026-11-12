package com.smartcast.live.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HeartbeatPacket(
    val clientId: Long,
    val packetLoss: Byte,
    val jitter: Short
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putLong(clientId)
        buffer.put(packetLoss)
        buffer.putShort(jitter)

        return buffer.array()
    }

    companion object {
        private const val SIZE = 11

        fun fromBytes(data: ByteArray, length: Int = data.size): HeartbeatPacket? {
            if (length < SIZE || length > data.size) return null

            val buffer = ByteBuffer.wrap(data, 0, length)
                .order(ByteOrder.BIG_ENDIAN)

            return HeartbeatPacket(
                clientId = buffer.long,
                packetLoss = buffer.get(),
                jitter = buffer.short
            )
        }
    }
}
