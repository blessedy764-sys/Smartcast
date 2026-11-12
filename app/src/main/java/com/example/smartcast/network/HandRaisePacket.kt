package com.smartcast.live.qa

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class HandRaiseAction(val value: Byte) {
    REQUEST(1),  // Tourist requests to speak
    APPROVE(2),  // Guide accepts the request
    DENY(3),     // Guide denies or cancels
    RELEASE(4)   // Tourist finished speaking
}

data class HandRaisePacket(
    val clientId: Long,
    val action: HandRaiseAction
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(clientId)
        buffer.put(action.value)
        return buffer.array()
    }

    companion object {
        fun fromBytes(data: ByteArray, length: Int): HandRaisePacket? {
            if (length < 9) return null
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val id = buffer.long
            val actionByte = buffer.get()
            val action = HandRaiseAction.values().find { it.value == actionByte } ?: return null
            return HandRaisePacket(id, action)
        }
    }
}
