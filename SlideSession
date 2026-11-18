package com.smartcast.live.slides

import android.graphics.Bitmap
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Host side. [send] writes one packet to the room's multicast group on the audio port.
 * No coroutines needed: one daemon thread paces the sends.
 */
class SlideHost(private val send: (ByteArray) -> Unit) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SmartCastSlideSend").apply { isDaemon = true }
    }
    private val rnd = SecureRandom()
    private var lastId = 0
    @Volatile private var generation = 0
    @Volatile private var current: List<ByteArray> = emptyList()

    /**
     * Compresses and sends a slide: three quick sends (UDP loss), then a repeat every 5 s so late
     * joiners catch up. Returns false if the slide is too big to send.
     * [encrypt] (PIN rooms) gets the WebP bytes and the new slide ID, and returns the ciphertext.
     * The slide ID is random and fresh on every call, which also keeps encryption nonces unique.
     */
    fun show(bitmap: Bitmap, page: Int, totalPages: Int, encrypt: ((ByteArray, Int) -> ByteArray)?): Boolean {
        var id: Int
        do { id = rnd.nextInt() } while (id == 0 || id == lastId)
        val webp = SlideCompressor.compress(bitmap)
        val body = encrypt?.invoke(webp, id) ?: webp
        val flags = if (encrypt != null) SlideCodec.FLAG_ENCRYPTED else 0
        val packets = SlideCodec.encode(id, page, totalPages, flags, body)
        if (packets.isEmpty()) return false
        val gen: Int
        synchronized(this) { // compression above stays outside the lock so stop() never waits on it
            lastId = id
            gen = ++generation
            current = packets
        }
        exec.execute {
            if (gen == generation) {
                sendAll()
                schedule(gen, 1)
            }
        }
        return true
    }

    private fun schedule(gen: Int, sent: Int) {
        val delay = if (sent < 3) 250L else 5000L
        exec.schedule({
            if (gen == generation) {
                sendAll()
                schedule(gen, sent + 1)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** Hides the slide on receivers (sent three times) and stops the repeats. */
    @Synchronized
    fun clear() {
        generation++
        current = emptyList()
        exec.execute {
            repeat(3) {
                try { send(SlideCodec.encodeClear()) } catch (_: Exception) {}
                try { Thread.sleep(80) } catch (_: InterruptedException) {}
            }
        }
    }

    /** Stops repeating without telling receivers. */
    @Synchronized
    fun stop() {
        generation++
        current = emptyList()
    }

    private fun sendAll() {
        val packets = current
        for ((i, p) in packets.withIndex()) {
            try { send(p) } catch (_: Exception) {}
            if (i % 8 == 7) try { Thread.sleep(2) } catch (_: InterruptedException) {}
        }
    }
}

/** Receiver side: feed every datagram from the audio port into [onPacket]. */
class SlideReceiver(
    private val onSlide: (SlideCodec.Completed) -> Unit,
    private val onClear: () -> Unit
) {
    private val assembler = SlideCodec.Assembler()

    fun reset() = assembler.reset()

    /** Returns true if it was a slide packet, so the caller skips it. Cheap: no image decoding here. */
    fun onPacket(data: ByteArray, offset: Int, length: Int): Boolean {
        val c = SlideCodec.decode(data, offset, length) ?: return false
        if (c.clear) {
            assembler.reset()
            onClear()
            return true
        }
        assembler.add(c)?.let { onSlide(it) }
        return true
    }
}
