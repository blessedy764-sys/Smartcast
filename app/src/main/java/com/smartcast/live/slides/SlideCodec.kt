package com.smartcast.live.slides

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Slide packets ride on the room's audio multicast port with their own magic number, so old receivers
 * ignore them and the relay forwards them like any other packet.
 *
 * Wire format (big-endian, 20-byte header):
 * magic(4) | version(1) | flags(1) | slideId(4) | page(2) | totalPages(2) | chunkIndex(2) | chunkCount(2) | payloadLen(2) | payload
 */
object SlideCodec {
    const val MAGIC = 0x534C4944 // "SLID"
    const val FLAG_ENCRYPTED = 1
    private const val FLAG_CLEAR = 2
    private const val VERSION: Byte = 1
    private const val HEADER = 20
    private const val CHUNK = 1100
    private const val MAX_CHUNKS = 64

    class Chunk(
        val flags: Int,
        val clear: Boolean,
        val slideId: Int,
        val page: Int,
        val totalPages: Int,
        val index: Int,
        val count: Int,
        val payload: ByteArray
    )

    class Completed(
        val slideId: Int,
        val page: Int,
        val totalPages: Int,
        val encrypted: Boolean,
        val data: ByteArray
    )

    /** Splits one slide into packets. Returns an empty list if it is too big to send. */
    fun encode(slideId: Int, page: Int, totalPages: Int, flags: Int, data: ByteArray): List<ByteArray> {
        val count = maxOf(1, (data.size + CHUNK - 1) / CHUNK)
        if (count > MAX_CHUNKS) return emptyList()
        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val start = i * CHUNK
            val len = minOf(CHUNK, data.size - start).coerceAtLeast(0)
            val buf = ByteBuffer.allocate(HEADER + len).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(MAGIC)
            buf.put(VERSION)
            buf.put(flags.toByte())
            buf.putInt(slideId)
            buf.putShort(page.toShort())
            buf.putShort(totalPages.toShort())
            buf.putShort(i.toShort())
            buf.putShort(count.toShort())
            buf.putShort(len.toShort())
            buf.put(data, start, len)
            out.add(buf.array())
        }
        return out
    }

    /** A small packet telling receivers to hide the slide. */
    fun encodeClear(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MAGIC)
        buf.put(VERSION)
        buf.put(FLAG_CLEAR.toByte())
        return buf.array() // the rest stays zero
    }

    fun decode(data: ByteArray, offset: Int, length: Int): Chunk? {
        if (length < HEADER || offset < 0 || offset + length > data.size) return null
        val b = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)
        if (b.int != MAGIC) return null
        if (b.get() != VERSION) return null
        val flags = b.get().toInt() and 0xFF
        val slideId = b.int
        val page = b.short.toInt() and 0xFFFF
        val total = b.short.toInt() and 0xFFFF
        val index = b.short.toInt() and 0xFFFF
        val count = b.short.toInt() and 0xFFFF
        val len = b.short.toInt() and 0xFFFF
        if ((flags and FLAG_CLEAR) != 0) return Chunk(flags, true, 0, 0, 0, 0, 0, ByteArray(0))
        if (count < 1 || count > MAX_CHUNKS || index >= count) return null
        if (len > CHUNK || len != length - HEADER) return null
        if (slideId == 0 || total < 1 || page >= total) return null
        val payload = ByteArray(len)
        b.get(payload)
        return Chunk(flags, false, slideId, page, total, index, count, payload)
    }

    /** Collects chunks of the slide being received. Repeats of an already finished slide are ignored. */
    class Assembler {
        private var slideId = 0
        private var parts: Array<ByteArray?> = arrayOfNulls(0)
        private var received = 0
        private var page = 0
        private var totalPages = 0
        private var flags = 0
        private var completedId = 0

        @Synchronized
        fun reset() {
            slideId = 0
            parts = arrayOfNulls(0)
            received = 0
            completedId = 0
        }

        /** Returns the whole slide when its last missing chunk arrives, otherwise null. */
        @Synchronized
        fun add(c: Chunk): Completed? {
            if (c.slideId == completedId) return null
            if (c.slideId != slideId) {
                slideId = c.slideId
                parts = arrayOfNulls(c.count)
                received = 0
                page = c.page
                totalPages = c.totalPages
                flags = c.flags
            }
            if (c.count != parts.size) return null
            if (parts[c.index] == null) {
                parts[c.index] = c.payload
                received++
            }
            if (received < parts.size) return null
            var size = 0
            for (p in parts) size += p?.size ?: 0
            val all = ByteArray(size)
            var pos = 0
            for (p in parts) {
                if (p != null) {
                    System.arraycopy(p, 0, all, pos, p.size)
                    pos += p.size
                }
            }
            completedId = slideId
            val done = Completed(slideId, page, totalPages, (flags and FLAG_ENCRYPTED) != 0, all)
            parts = arrayOfNulls(0)
            received = 0
            return done
        }
    }
}
