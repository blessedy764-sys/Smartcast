package com.smartcast.live.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format (big-endian):
 *   seq(2) | timestamp(4) | channelId(1) | flags(1) | sessionId(4) | ciphertext | GCM tag(16)
 * The 12-byte header is sent in the clear and authenticated as GCM AAD.
 */
data class PttPacket(
    val sequenceNumber: Int, // 0..65535 (unsigned 16-bit)
    val timestamp: Long,     // unsigned 32-bit
    val channelId: Byte,
    val flags: Byte,
    val sessionId: Int,      // assigned by the host, unique per PTT session
    val opusPayload: ByteArray
) {
    companion object {
        const val HEADER_SIZE = 12
        const val TAG_BYTES = 16
        const val MIN_SIZE = HEADER_SIZE + TAG_BYTES
        const val MAX_SEQ = 0xFFFF
    }
}

object PttCrypto {
    private const val TAG_BITS = 128
    private val HKDF_INFO = "smartcast-ptt-v1".toByteArray(Charsets.UTF_8)

    /**
     * Derives a PTT-only subkey from the room key (HKDF-SHA256) so PTT nonces
     * can never collide with nonces used by the broadcast stream.
     */
    fun deriveKey(roomKey: SecretKey): SecretKey {
        val ikm = requireNotNull(roomKey.encoded) { "Room key must be exportable" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256")) // extract (zero salt)
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))           // expand, T(1)
        mac.update(HKDF_INFO)
        mac.update(1.toByte())
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    private fun buildHeader(p: PttPacket): ByteArray =
        ByteBuffer.allocate(PttPacket.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putShort((p.sequenceNumber and 0xFFFF).toShort())
            .putInt((p.timestamp and 0xFFFFFFFFL).toInt())
            .put(p.channelId)
            .put(p.flags)
            .putInt(p.sessionId)
            .array()

    // nonce = sessionId(4) | timestamp(4) | seq(2) | 0x0000
    // Unique as long as the host never hands out the same sessionId twice per key.
    private fun buildNonce(header: ByteArray): ByteArray {
        val n = ByteBuffer.allocate(12)
        n.put(header, 8, 4) // sessionId
        n.put(header, 2, 4) // timestamp
        n.put(header, 0, 2) // seq
        return n.array()
    }

    fun encrypt(key: SecretKey, packet: PttPacket): ByteArray {
        val header = buildHeader(packet)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, buildNonce(header)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(packet.opusPayload)
    }

    /** Returns null for anything malformed or that fails authentication. */
    fun decrypt(key: SecretKey, data: ByteArray, length: Int): PttPacket? {
        if (length < PttPacket.MIN_SIZE || length > data.size) return null
        val header = data.copyOfRange(0, PttPacket.HEADER_SIZE)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, buildNonce(header)))
            cipher.updateAAD(header)
            cipher.doFinal(data, PttPacket.HEADER_SIZE, length - PttPacket.HEADER_SIZE)
        } catch (e: GeneralSecurityException) {
            return null
        }
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val seq = b.short.toInt() and 0xFFFF
        val ts = b.int.toLong() and 0xFFFFFFFFL
        val channel = b.get()
        val flags = b.get()
        val sessionId = b.int
        return PttPacket(seq, ts, channel, flags, sessionId, plaintext)
    }
}
