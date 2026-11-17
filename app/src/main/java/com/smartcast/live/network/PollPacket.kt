package com.smartcast.live.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format for live polls.
 *
 * Poll (host -> everyone, sent on the multicast group):
 *   "SCPL" | version(1) | flags(1) | body
 * Vote (guest -> host, unicast to the telemetry port):
 *   "SCPV" | version(1) | flags(1) | body
 *
 * In a PIN room (flags bit 0 set) the body is AES-GCM encrypted with a poll-only subkey derived
 * from the room key, using a fresh random 12-byte nonce: prefix(6) | nonce(12) | ciphertext | tag(16).
 * The 6-byte prefix is authenticated as AAD. Open rooms send the body in the clear.
 */
object PollCodec {
    const val POLL_MAGIC = 0x5343504C // "SCPL"
    const val VOTE_MAGIC = 0x53435056 // "SCPV"

    private const val VERSION: Byte = 1
    private const val FLAG_ENCRYPTED = 1
    private const val PREFIX = 6
    private const val NONCE = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = 128
    private const val MAX_OPTIONS = 8
    private val BE = ByteOrder.BIG_ENDIAN
    private val HKDF_INFO = "smartcast-poll-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    data class Poll(val id: Int, val open: Boolean, val question: String, val options: List<String>)

    /** seq increases with every tap, so a delayed repeat of an older tap never overrides a newer answer. */
    data class Vote(val pollId: Int, val clientId: Long, val choice: Int, val seq: Int)

    fun encodePoll(poll: Poll, key: SecretKey?): ByteArray {
        val q = poll.question.toByteArray(Charsets.UTF_8)
        val opts = poll.options.take(MAX_OPTIONS).map { it.toByteArray(Charsets.UTF_8) }
        var size = 4 + 1 + 1 + 2 + q.size
        for (o in opts) size += 1 + o.size
        val body = ByteBuffer.allocate(size).order(BE)
        body.putInt(poll.id)
        body.put(if (poll.open) 1 else 0)
        body.put(opts.size.toByte())
        body.putShort(q.size.toShort())
        body.put(q)
        for (o in opts) {
            body.put(o.size.toByte())
            body.put(o)
        }
        return wrap(POLL_MAGIC, body.array(), key)
    }

    /** Returns null for anything malformed, forged, or from a room with a different PIN setting. */
    fun decodePoll(data: ByteArray, length: Int, key: SecretKey?): Poll? {
        val body = unwrap(POLL_MAGIC, data, length, key) ?: return null
        return try {
            val b = ByteBuffer.wrap(body).order(BE)
            val id = b.int
            val open = b.get().toInt() == 1
            val count = b.get().toInt() and 0xFF
            if (count > MAX_OPTIONS) return null
            val qLen = b.short.toInt() and 0xFFFF
            if (qLen > b.remaining()) return null
            val q = ByteArray(qLen)
            b.get(q)
            val options = ArrayList<String>(count)
            repeat(count) {
                val len = b.get().toInt() and 0xFF
                if (len > b.remaining()) return null
                val o = ByteArray(len)
                b.get(o)
                options.add(String(o, Charsets.UTF_8))
            }
            if (open && options.size < 2) return null
            Poll(id, open, String(q, Charsets.UTF_8), options)
        } catch (e: Exception) {
            null
        }
    }

    fun encodeVote(vote: Vote, key: SecretKey?): ByteArray {
        val body = ByteBuffer.allocate(17).order(BE)
            .putInt(vote.pollId)
            .putLong(vote.clientId)
            .put(vote.choice.toByte())
            .putInt(vote.seq)
            .array()
        return wrap(VOTE_MAGIC, body, key)
    }

    fun decodeVote(data: ByteArray, length: Int, key: SecretKey?): Vote? {
        val body = unwrap(VOTE_MAGIC, data, length, key) ?: return null
        if (body.size != 17) return null
        val b = ByteBuffer.wrap(body).order(BE)
        val pollId = b.int
        val clientId = b.long
        val choice = b.get().toInt() and 0xFF
        val seq = b.int
        return Vote(pollId, clientId, choice, seq)
    }

    private fun wrap(magic: Int, body: ByteArray, key: SecretKey?): ByteArray {
        val flags = if (key != null) FLAG_ENCRYPTED else 0
        val prefix = ByteBuffer.allocate(PREFIX).order(BE)
            .putInt(magic).put(VERSION).put(flags.toByte()).array()
        if (key == null) return prefix + body
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(key), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(prefix)
        return prefix + nonce + cipher.doFinal(body)
    }

    private fun unwrap(magic: Int, data: ByteArray, length: Int, key: SecretKey?): ByteArray? {
        if (length < PREFIX || length > data.size) return null
        val h = ByteBuffer.wrap(data, 0, PREFIX).order(BE)
        if (h.int != magic || h.get() != VERSION) return null
        val encrypted = (h.get().toInt() and FLAG_ENCRYPTED) != 0
        if (encrypted != (key != null)) return null
        if (!encrypted) return data.copyOfRange(PREFIX, length)
        if (key == null || length < PREFIX + NONCE + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(key), GCMParameterSpec(TAG_BITS, data, PREFIX, NONCE))
            cipher.updateAAD(data, 0, PREFIX)
            cipher.doFinal(data, PREFIX + NONCE, length - PREFIX - NONCE)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** Poll-only subkey (HKDF-SHA256) so polls never share a key with the audio stream. */
    private fun deriveKey(roomKey: SecretKey): SecretKey {
        val ikm = requireNotNull(roomKey.encoded) { "Room key must be exportable" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256")) // extract (zero salt)
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))           // expand, T(1)
        mac.update(HKDF_INFO)
        mac.update(1.toByte())
        return SecretKeySpec(mac.doFinal(), "AES")
    }
}
