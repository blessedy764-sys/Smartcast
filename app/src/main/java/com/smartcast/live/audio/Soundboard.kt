package com.smartcast.live.audio

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Four quick-trigger pads. Each pad plays a short 48 kHz mono sound that is mixed into the host's
 * broadcast. Pads start with built-in sounds; a pad can be replaced with a 16-bit PCM .wav file
 * (mono or stereo, any sample rate, up to 30 seconds).
 */
class Soundboard(private val context: Context) {
    companion object {
        const val PAD_COUNT = 4
        const val SAMPLE_RATE = 48_000
        private const val MAX_SECONDS = 30
        val PAD_NAMES = listOf("Bell Chime", "Applause", "Alert Tone", "Intro Beat")
    }

    private class Voice(val samples: ShortArray) {
        var pos = 0
    }

    private val voices = CopyOnWriteArrayList<Voice>()
    private val cache = arrayOfNulls<ShortArray>(PAD_COUNT)

    private fun padFile(index: Int) = File(context.filesDir, "pad$index.wav")

    @Synchronized
    private fun samplesFor(index: Int): ShortArray {
        cache[index]?.let { return it }
        val file = padFile(index)
        val custom = if (file.exists()) {
            try { parseWav(file.readBytes()) } catch (_: Exception) { null }
        } else null
        val samples = custom ?: defaultSample(index)
        cache[index] = samples
        return samples
    }

    /** Loads every pad's sound now so the first tap plays instantly. Call from a background thread. */
    fun preloadAll() {
        for (i in 0 until PAD_COUNT) samplesFor(i)
    }

    fun trigger(index: Int) {
        if (index !in 0 until PAD_COUNT) return
        voices.add(Voice(samplesFor(index)))
    }

    fun stopAll() {
        voices.clear()
    }

    /** Adds the next 20 ms of every playing pad into [pcm]. Called from the broadcast loop. */
    fun mixInto(pcm: ShortArray) {
        if (voices.isEmpty()) return
        val finished = ArrayList<Voice>()
        for (voice in voices) {
            val n = minOf(pcm.size, voice.samples.size - voice.pos)
            for (i in 0 until n) {
                pcm[i] = (pcm[i] + voice.samples[voice.pos + i]).coerceIn(-32768, 32767).toShort()
            }
            voice.pos += n
            if (voice.pos >= voice.samples.size) finished.add(voice)
        }
        if (finished.isNotEmpty()) voices.removeAll(finished.toSet())
    }

    /** Validates and stores a custom WAV for a pad. Returns an error message, or null on success. */
    fun install(index: Int, wavBytes: ByteArray): String? {
        if (index !in 0 until PAD_COUNT) return "Unknown pad"
        val samples = try {
            parseWav(wavBytes)
        } catch (e: Exception) {
            return e.message ?: "Could not read that WAV file"
        }
        padFile(index).writeBytes(wavBytes)
        synchronized(this) { cache[index] = samples }
        return null
    }

    // ---------- WAV reading ----------

    private fun parseWav(bytes: ByteArray): ShortArray {
        require(bytes.size > 44 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "Not a WAV file" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (size < 0) break
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = buf.getShort(body).toInt() and 0xFFFF
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                dataStart = body
                dataLength = minOf(size, bytes.size - body)
                break
            }
            pos = body + size + (size and 1)
        }
        require(format == 1 || format == 0xFFFE) { "Only PCM WAV files are supported" }
        require(bits == 16) { "Only 16-bit WAV files are supported" }
        require(channels in 1..2) { "Only mono or stereo WAV files are supported" }
        require(rate > 0) { "Invalid sample rate" }
        require(dataStart >= 0 && dataLength > 0) { "The WAV file has no audio" }

        val frames = dataLength / (2 * channels)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += buf.getShort(dataStart + (f * channels + c) * 2).toInt()
            mono[f] = (sum / channels).toShort()
        }
        if (rate == SAMPLE_RATE) return mono.copyOf(minOf(frames, SAMPLE_RATE * MAX_SECONDS))

        // linear-interpolation resample to 48 kHz
        val outLength = minOf((frames.toLong() * SAMPLE_RATE / rate).toInt(), SAMPLE_RATE * MAX_SECONDS)
        val out = ShortArray(outLength)
        val step = rate.toDouble() / SAMPLE_RATE
        for (i in 0 until outLength) {
            val src = i * step
            val idx = src.toInt()
            val frac = src - idx
            val a = mono[minOf(idx, frames - 1)].toInt()
            val b = mono[minOf(idx + 1, frames - 1)].toInt()
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    private fun tag(bytes: ByteArray, offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)

    // ---------- built-in sounds (generated, so the app needs no audio files) ----------

    private fun defaultSample(index: Int): ShortArray = when (index) {
        0 -> bell()
        1 -> applause()
        2 -> alert()
        else -> introBeat()
    }

    private fun bell(): ShortArray {
        val n = SAMPLE_RATE * 18 / 10
        return ShortArray(n) { i ->
            val t = i / SAMPLE_RATE.toDouble()
            val env = minOf(1.0, t / 0.005) * Math.exp(-3.0 * t)
            val s = Math.sin(2 * Math.PI * 880 * t) +
                0.5 * Math.sin(2 * Math.PI * 1760 * t) +
                0.25 * Math.sin(2 * Math.PI * 2640 * t)
            (s / 1.75 * env * 0.6 * 32767).toInt().toShort()
        }
    }

    private fun applause(): ShortArray {
        val n = SAMPLE_RATE * 5 / 2
        val rnd = java.util.Random(7)
        var env = 0.0
        return ShortArray(n) { i ->
            if (rnd.nextDouble() < 0.002) env = 1.0
            env *= 0.9992
            val t = i / SAMPLE_RATE.toDouble()
            val fade = minOf(1.0, t / 0.1, (2.5 - t) / 0.4)
            ((rnd.nextDouble() * 2 - 1) * minOf(1.0, env + 0.08) * fade * 0.6 * 32767).toInt().toShort()
        }
    }

    private fun alert(): ShortArray {
        val n = SAMPLE_RATE * 12 / 10
        return ShortArray(n) { i ->
            val t = i / SAMPLE_RATE.toDouble()
            val local = t % 0.35
            if (t >= 1.05 || local >= 0.25) {
                0.toShort()
            } else {
                val ramp = minOf(1.0, local / 0.01, (0.25 - local) / 0.01)
                (Math.sin(2 * Math.PI * 1000 * t) * ramp * 0.5 * 32767).toInt().toShort()
            }
        }
    }

    private fun introBeat(): ShortArray {
        val n = SAMPLE_RATE * 2
        val rnd = java.util.Random(3)
        return ShortArray(n) { i ->
            val t = i / SAMPLE_RATE.toDouble()
            val beat = t % 0.5 // a kick every half second
            val phase = 2 * Math.PI * (50 * beat + 3.5 * (1 - Math.exp(-20 * beat)))
            val kick = Math.sin(phase) * Math.exp(-8 * beat)
            val offBeat = (t + 0.25) % 0.5 // a hi-hat between the kicks
            val hat = (rnd.nextDouble() * 2 - 1) * Math.exp(-60 * offBeat) * 0.25
            ((kick + hat) * 0.6 * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
