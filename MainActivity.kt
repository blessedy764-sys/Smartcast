package com.smartcast.live

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** SmartCast Live v2 prototype: Opus-compressed mono voice over local UDP multicast. */
class MainActivity : Activity() {
    companion object {
        private const val GROUP = "239.10.10.10"
        private const val PORT = 50005
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz, mono
        private const val BITRATE = 32_000
        private const val HEADER_SIZE = 24
        private const val MAGIC = 0x53434F50 // "SCOP"
        private const val VERSION: Byte = 1
        private const val MAX_OPUS_BYTES = 1275
        private const val REQUEST_MIC = 41
    }

    private val running = AtomicBoolean(false)
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var status: TextView
    private lateinit var details: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 24)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        page.addView(TextView(this).apply {
            text = "SMARTCAST"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(0xFF12345A.toInt())
        })
        page.addView(TextView(this).apply {
            text = "Offline group audio platform"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF536579.toInt())
        })
        status = TextView(this).apply {
            text = "Status: Ready"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF176B45.toInt())
            setPadding(0, 24, 0, 12)
        }
        page.addView(status)
        page.addView(section("SMARTCAST LIVE"))
        page.addView(button("Start broadcasting") { startHost() })
        page.addView(button("Join as receiver") { startReceiver() })
        page.addView(button("Stop session") { stopSession() })
        page.addView(section("SMARTCAST INTERPRETER"))
        page.addView(TextView(this).apply {
            text = "Planned module: speech recognition → translation → speech synthesis → local language audio channels. Offline AI models and translated audio playback are not implemented yet."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF536579.toInt())
            setPadding(0, 4, 0, 14)
        })
        page.addView(TextView(this).apply {
            text = "Future language channels: English • Shona • Ndebele • French • German"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF536579.toInt())
        })
        details = TextView(this).apply {
            text = "LAN multicast: $GROUP:$PORT\nAudio: Opus, 48 kHz, mono, 32 kbps, 20 ms frames"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 20, 0, 0)
        }
        page.addView(details)
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun section(label: String) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(0xFF12345A.toInt())
        setPadding(0, 24, 0, 8)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setState(message: String) {
        runOnUiThread { status.text = "Status: $message" }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("SmartCastMulticast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startHost() {
        if (running.get()) { toast("Stop the current session first."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        running.set(true)
        setState("Starting Opus broadcaster…")
        thread(name = "SmartCastOpusHost") {
            var input: AudioRecord? = null
            var udp: MulticastSocket? = null
            try {
                acquireMulticastLock()
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("AudioRecord does not support 48 kHz input on this device")
                input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
                )
                if (input.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone could not be initialized")
                recorder = input
                val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
                encoder.setBitrate(BITRATE)
                encoder.setComplexity(5)
                val pcm = ShortArray(FRAME_SAMPLES)
                val encoded = ByteArray(MAX_OPUS_BYTES)
                val address = InetAddress.getByName(GROUP)
                udp = MulticastSocket()
                udp.timeToLive = 1
                socket = udp
                input.startRecording()
                setState("Broadcasting Opus audio over local Wi-Fi")
                var sequence = 0
                val sessionId = (System.nanoTime() xor android.os.Process.myPid().toLong()).toInt()
                while (running.get()) {
                    var filled = 0
                    while (running.get() && filled < FRAME_SAMPLES) {
                        val count = input.read(pcm, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                        if (count < 0) throw IllegalStateException("Microphone read failed: $count")
                        if (count > 0) filled += count
                    }
                    if (!running.get()) break
                    val encodedLength = encoder.encode(pcm, 0, FRAME_SAMPLES, encoded, 0, encoded.size)
                    if (encodedLength > 0) {
                        val packetData = ByteBuffer.allocate(HEADER_SIZE + encodedLength).order(ByteOrder.BIG_ENDIAN)
                        packetData.putInt(MAGIC)
                        packetData.put(VERSION)
                        packetData.put(0) // flags reserved
                        packetData.putInt(sessionId)
                        packetData.putInt(sequence++)
                        packetData.putLong(System.nanoTime())
                        packetData.putShort(encodedLength.toShort())
                        packetData.put(encoded, 0, encodedLength)
                        val bytes = packetData.array()
                        udp.send(DatagramPacket(bytes, bytes.size, address, PORT))
                    }
                }
            } catch (e: Exception) {
                if (running.get()) setState("Broadcast error: ${e.message ?: "unknown"}")
                running.set(false)
            } finally {
                try { input?.stop() } catch (_: Exception) {}
                try { input?.release() } catch (_: Exception) {}
                try { udp?.close() } catch (_: Exception) {}
                recorder = null
                socket = null
                releaseMulticastLock()
            }
        }
    }

    private fun startReceiver() {
        if (running.get()) { toast("Stop the current session first."); return }
        running.set(true)
        setState("Searching for Opus broadcast…")
        thread(name = "SmartCastOpusReceiver") {
            var udp: MulticastSocket? = null
            var output: AudioTrack? = null
            try {
                acquireMulticastLock()
                val groupAddress = InetAddress.getByName(GROUP)
                udp = MulticastSocket(null)
                udp.reuseAddress = true
                udp.bind(InetSocketAddress(PORT))
                udp.joinGroup(groupAddress)
                socket = udp
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("AudioTrack does not support 48 kHz output on this device")
                output = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
                    AudioTrack.MODE_STREAM
                )
                track = output
                val decoder = OpusDecoder(SAMPLE_RATE, 1)
                val pcmOut = ShortArray(5760) // Opus maximum frame: 120 ms at 48 kHz
                val receiveBuffer = ByteArray(HEADER_SIZE + MAX_OPUS_BYTES + 64)
                var activeSession = 0
                var expectedSequence: Int? = null
                output.play()
                setState("Receiving and decoding Opus audio")
                while (running.get()) {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    udp.receive(packet)
                    if (packet.length < HEADER_SIZE) continue
                    val header = ByteBuffer.wrap(packet.data, packet.offset, packet.length).order(ByteOrder.BIG_ENDIAN)
                    if (header.int != MAGIC) continue
                    val version = header.get()
                    header.get() // flags
                    val sessionId = header.int
                    val sequence = header.int
                    header.long // capture timestamp; reserved for latency diagnostics
                    val payloadLength = header.short.toInt() and 0xFFFF
                    if (version != VERSION || payloadLength <= 0 || payloadLength != packet.length - HEADER_SIZE) continue
                    if (activeSession != sessionId) {
                        activeSession = sessionId
                        expectedSequence = sequence
                    }
                    val expected = expectedSequence ?: sequence
                    val gap = sequence - expected
                    // Opus is stateful: conceal missing 20 ms frames before decoding the next packet.
                    if (gap in 1..5) {
                        repeat(gap) {
                            val concealed = decoder.decode(null, 0, 0, pcmOut, 0, FRAME_SAMPLES, false)
                            if (concealed > 0) output.write(pcmOut, 0, concealed)
                        }
                    } else if (gap < 0 || gap > 5) {
                        // Drop late/out-of-window packets; reset sequence expectation to current packet.
                        if (gap < 0) continue
                    }
                    val decodedSamples = decoder.decode(
                        packet.data, packet.offset + HEADER_SIZE, payloadLength,
                        pcmOut, 0, pcmOut.size, false
                    )
                    if (decodedSamples > 0) output.write(pcmOut, 0, decodedSamples)
                    expectedSequence = sequence + 1
                }
            } catch (e: Exception) {
                if (running.get()) setState("Receiver error: ${e.message ?: "unknown"}")
                running.set(false)
            } finally {
                try { udp?.leaveGroup(InetAddress.getByName(GROUP)) } catch (_: Exception) {}
                try { output?.pause(); output?.flush(); output?.stop(); output?.release() } catch (_: Exception) {}
                try { udp?.close() } catch (_: Exception) {}
                track = null
                socket = null
                releaseMulticastLock()
            }
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
    }

    private fun stopSession() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        releaseMulticastLock()
        recorder = null
        track = null
        socket = null
        setState("Stopped")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }
}
