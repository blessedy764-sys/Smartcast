package com.smartcast.live.qa

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.smartcast.live.network.PttSender
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import kotlin.concurrent.thread

/**
 * Guest-side push to talk. Records the mic, encodes 20 ms Opus frames, encrypts each one
 * with AES-GCM (PttSender) and sends it to the host. Wire format per datagram:
 * [seq(2) ts(4) ch(1) flags(1) sessionId(4)][ciphertext][tag(16)].
 * The sessionId is assigned by the host when it approves this guest's hand raise.
 */
class PushToTalkController(
    private val hostIp: String,
    private val pttPort: Int,
    private val sessionId: Int,
    private val roomKey: SecretKey,
    private val channelId: Byte,
    private val onError: (String) -> Unit = {}
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz, mono
        private const val BITRATE = 24_000
        private const val MAX_OPUS_BYTES = 1275
    }

    private val active = AtomicBoolean(false)

    val isTransmitting: Boolean get() = active.get()

    @SuppressLint("MissingPermission")
    fun startTransmitting() {
        if (!active.compareAndSet(false, true)) return
        thread(name = "SmartCastPushToTalk") {
            var recorder: AudioRecord? = null
            var socket: DatagramSocket? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("Mic does not support 48 kHz on this device")
                val mic = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
                )
                recorder = mic
                if (mic.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone could not be initialized")
                }
                val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
                encoder.setBitrate(BITRATE)
                encoder.setComplexity(5)
                val sock = DatagramSocket()
                socket = sock
                val address = InetAddress.getByName(hostIp)
                val pcm = ShortArray(FRAME_SAMPLES)
                val opus = ByteArray(MAX_OPUS_BYTES)

                // One sender per transmission: sequence numbers restart at 0 with the session ID.
                val sender = PttSender(roomKey, sessionId, channelId)
                var timestamp = 0L

                mic.startRecording()
                while (active.get()) {
                    var filled = 0
                    while (active.get() && filled < FRAME_SAMPLES) {
                        val count = mic.read(pcm, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                        if (count < 0) throw IllegalStateException("Microphone read failed: $count")
                        if (count > 0) filled += count
                    }
                    if (!active.get()) break
                    val length = encoder.encode(pcm, 0, FRAME_SAMPLES, opus, 0, opus.size)
                    if (length > 0) {
                        val bytes = sender.seal(opus.copyOf(length), timestamp)
                        sock.send(DatagramPacket(bytes, bytes.size, address, pttPort))
                    }
                    timestamp = (timestamp + FRAME_SAMPLES) and 0xFFFFFFFFL
                }
            } catch (e: Exception) {
                if (active.get()) onError(e.message ?: "unknown")
                active.set(false)
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stopTransmitting() {
        active.set(false)
    }
}
