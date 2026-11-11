package com.gitco.smartcast.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SmartCastPacket(
    val sequenceNumber: Int, // 0..65535 (unsigned 16-bit)
    val timestamp: Long,     // unsigned 32-bit
    val channelId: Byte,
    val flags: Byte,
    val opusPayload: ByteArray
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + opusPayload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort((sequenceNumber and 0xFFFF).toShort())
        buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())
        buffer.put(channelId)
        buffer.put(flags)
        buffer.put(opusPayload)
        return buffer.array()
    }

    companion object {
        const val HEADER_SIZE = 8

        fun fromBytes(data: ByteArray, length: Int): SmartCastPacket? {
            if (length < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val seq = buffer.short.toInt() and 0xFFFF
            val ts = buffer.int.toLong() and 0xFFFFFFFFL
            val channel = buffer.get()
            val flags = buffer.get()
            val payload = ByteArray(length - HEADER_SIZE)
            buffer.get(payload)
            return SmartCastPacket(seq, ts, channel, flags, payload)
        }
    }
}
