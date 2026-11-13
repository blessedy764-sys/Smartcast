package com.smartcast.live.qa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import com.smartcast.live.audio.AudioEffectsManager
import com.smartcast.live.discovery.SmartCastAdvertiser
import com.smartcast.live.discovery.SmartCastDiscovery
import com.smartcast.live.network.PttReceiver
import com.smartcast.live.network.PttSender
import com.smartcast.live.qr.QrCodeGenerator
import com.smartcast.live.qr.SessionConnectionConfig
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.smartcast.live.room.ClientTracker
import com.smartcast.live.security.RoomSecurity
import com.smartcast.live.service.SmartCastAudioService
import com.smartcast.live.telemetry.DeviceHealthMonitor
import com.smartcast.live.telemetry.HeartbeatPacket
import com.smartcast.live.telemetry.HeartbeatSender
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** SmartCast Live v2 prototype: Opus-compressed mono voice over local UDP multicast. */
class MainActivity : Activity() {
    companion object {
        private const val GROUP = "239.10.10.10"
        private const val PORT = 50005
        private const val TELEMETRY_PORT = 50006 // guests -> host heartbeats
        private const val HAND_PORT = 50007      // hand raise requests and replies
        private const val PTT_PORT = 50008       // approved guest voice -> host
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz, mono
        private const val BITRATE = 32_000
        private const val LOW_BITRATE = 16_000 // used when the phone is warm
        private const val HEADER_SIZE = 24
        private const val MAGIC = 0x53434F50 // "SCOP"
        private const val VERSION: Byte = 1
        private const val MAX_OPUS_BYTES = 1275
        private const val REQUEST_MIC = 41
        private const val FLAG_ENCRYPTED = 1
        private const val PTT_CHANNEL: Byte = 1
        private val ROOM_SALT = "SmartCastRoomSalt-v1".toByteArray()
        private const val HAND_IDLE = "Hand raise: join as a receiver to ask to speak"
    }

    private val running = AtomicBoolean(false)
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var requestedBitrate = BITRATE
    private var healthMonitor: DeviceHealthMonitor? = null
    private var lowBatteryShown = false

    // room features
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tracker = ClientTracker()
    private val myClientId: Long = UUID.randomUUID().mostSignificantBits
    private val handRequests = ConcurrentHashMap<Long, String>() // clientId -> address
    private val auxSockets = CopyOnWriteArrayList<DatagramSocket>()
    private val lostFrames = AtomicInteger(0)
    private val totalFrames = AtomicInteger(0)
    private var heartbeatSender: HeartbeatSender? = null
    @Volatile private var isHost = false
    @Volatile private var hostAddress: String? = null
    @Volatile private var handRaised = false
    @Volatile private var handStatus = HAND_IDLE

    // push to talk
    @Volatile private var approved = false          // guest: host approved my hand raise
    @Volatile private var activeSpeaker: Long? = null // host: the one approved guest we play
    private var pttController: PushToTalkController? = null
    private val guestFrames = ConcurrentLinkedQueue<ShortArray>() // decoded guest voice, mixed into the broadcast

    // encrypted push to talk (PIN rooms only; open rooms keep the plain [clientId][opus] format)
    @Volatile private var pttReceiver: PttReceiver? = null   // host: validates and decrypts guest voice
    @Volatile private var activeSessionId = 0                // host: PTT session of the approved guest (0 = none)
    private var lastPttSessionId = SecureRandom().nextInt()  // host: random start, then increments
    @Volatile private var guestKey: SecretKeySpec? = null    // guest: room key from my PIN
    @Volatile private var pttSender: PttSender? = null       // guest: one sender per approval, reused on every press

    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var details: TextView
    private lateinit var pinInput: EditText
    private lateinit var participantsView: TextView
    private lateinit var handView: TextView
    private lateinit var handButton: Button
    private lateinit var talkButton: Button
    private lateinit var requestsBox: LinearLayout
    private lateinit var qrImage: ImageView
    private lateinit var sessionsBox: LinearLayout
    private val advertiser by lazy { SmartCastAdvertiser(applicationContext) } // host: announce the session
    private var discovery: SmartCastDiscovery? = null                         // guest: look for sessions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_MIC)
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
        banner = TextView(this).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 14, 20, 14)
        }
        page.addView(banner)

        // ---- Room security ----
        page.addView(section("ROOM SECURITY"))
        pinInput = EditText(this).apply {
            hint = "Room PIN (same on host and receivers)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        page.addView(pinInput)
        page.addView(TextView(this).apply {
            text = "Leave empty for an open room. With a PIN, audio is encrypted. Set it before you start or join."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF64748B.toInt())
        })

        page.addView(section("SMARTCAST LIVE"))
        page.addView(button("Start broadcasting") { startHost() })
        page.addView(button("Join as receiver") { startReceiver() })
        page.addView(button("Stop session") { stopSession() })
        page.addView(button("Show join QR code") { showJoinQr() })
        page.addView(button("Scan QR code to join") { scanJoinQr() })
        qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            setPadding(0, 16, 0, 16)
        }
        page.addView(qrImage)
        page.addView(button("Find sessions nearby") { findSessions() })
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(sessionsBox)

        // ---- Room: participants + hand raise ----
        page.addView(section("ROOM"))
        participantsView = TextView(this).apply {
            text = "Participants: -"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF12345A.toInt())
        }
        page.addView(participantsView)
        handView = TextView(this).apply {
            text = HAND_IDLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF536579.toInt())
            setPadding(0, 8, 0, 4)
        }
        page.addView(handView)
        handButton = button("Raise hand") { toggleHand() }
        page.addView(handButton)
        talkButton = button("Hold to talk") { }.apply {
            isEnabled = false
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startTalking()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTalking()
                }
                true
            }
        }
        page.addView(talkButton)
        requestsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(requestsBox)

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

    // ---------- device health (heat + battery) ----------

    private fun showBanner(message: String, color: Int) {
        runOnUiThread {
            banner.text = message
            banner.setBackgroundColor(color)
            banner.visibility = View.VISIBLE
        }
    }

    private fun hideBanner() {
        runOnUiThread { banner.visibility = View.GONE }
    }

    private fun showThermalWarningBanner(message: String) = showBanner(message, 0xFFB45309.toInt())

    private fun showCriticalAlert(message: String) = showBanner(message, 0xFFB91C1C.toInt())

    private fun showLowBatteryWarning() =
        showBanner("Battery low (15% or less). Plug in to keep broadcasting.", 0xFFB45309.toInt())

    private fun startHealthMonitoring() {
        stopHealthMonitoring()
        lowBatteryShown = false
        requestedBitrate = BITRATE
        val monitor = DeviceHealthMonitor(this) { state ->
            when {
                state.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                    showCriticalAlert("Device overheating. Stopping broadcast to prevent shutdown.")
                    stopSession(keepBanner = true)
                }
                state.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> {
                    requestedBitrate = LOW_BITRATE // encoder picks this up on the next frame
                    showThermalWarningBanner("Device running warm. Optimizing audio stream...")
                }
                else -> {
                    requestedBitrate = BITRATE
                    if (lowBatteryShown) Unit else hideBanner()
                }
            }
            if (running.get() && state.batteryLevelPercent <= 15 && !state.isCharging && !lowBatteryShown) {
                lowBatteryShown = true
                showLowBatteryWarning()
            }
        }
        healthMonitor = monitor
        monitor.startMonitoring()
    }

    private fun stopHealthMonitoring() {
        healthMonitor?.stopMonitoring()
        healthMonitor = null
    }

    // ---------- room: encryption helpers ----------

    /** Unique 12-byte GCM nonce per packet, derived from the session ID and sequence number. */
    private fun ivFor(sessionId: Int, sequence: Int): ByteArray =
        ByteBuffer.allocate(12).putInt(sessionId).putInt(sequence).putInt(0).array()

    private fun keyFor(pin: String): SecretKeySpec? =
        if (pin.isEmpty()) null else RoomSecurity.deriveKey(pin, ROOM_SALT)

    // ---------- room: participants + hand raise ----------

    private fun label(id: Long) = "Guest " + (id and 0xFFFFL).toString(16)

    private fun lossPercent(): Int {
        val total = totalFrames.get()
        return if (total <= 0) 0 else ((lostFrames.get() * 100) / total).coerceIn(0, 100)
    }

    private fun openUdp(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        auxSockets.add(s)
        return s
    }

    private fun sendHand(address: String, packet: HandRaisePacket) {
        thread(name = "SmartCastHandSend") {
            try {
                val bytes = packet.toBytes()
                DatagramSocket().use {
                    it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(address), HAND_PORT))
                }
            } catch (_: Exception) {}
        }
    }

    private fun startHostListeners() {
        thread(name = "SmartCastTelemetry") {
            try {
                val s = openUdp(TELEMETRY_PORT)
                val buf = ByteArray(64)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val hb = HeartbeatPacket.fromBytes(p.data, p.length) ?: continue
                    tracker.onHeartbeat(hb.clientId.toString(), p.address.hostAddress ?: "")
                }
            } catch (_: Exception) {}
        }
    }

    private fun startHandListener() {
        thread(name = "SmartCastHandRaise") {
            try {
                val s = openUdp(HAND_PORT)
                val buf = ByteArray(32)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val msg = HandRaisePacket.fromBytes(p.data, p.length) ?: continue
                    if (isHost) onHostHandPacket(msg, p.address.hostAddress ?: "") else onGuestHandPacket(msg)
                }
            } catch (_: Exception) {}
        }
    }

    /** Host: forget the current speaker and close their encrypted PTT session. */
    private fun endActiveSpeaker() {
        val sessionId = activeSessionId
        if (sessionId != 0) pttReceiver?.closeSession(sessionId)
        activeSessionId = 0
        activeSpeaker = null
    }

    /** Host: next PTT session ID (never 0, never repeated while the process runs). */
    private fun nextPttSessionId(): Int {
        do { lastPttSessionId++ } while (lastPttSessionId == 0)
        return lastPttSessionId
    }

    private fun onHostHandPacket(msg: HandRaisePacket, address: String) {
        when (msg.action) {
            HandRaiseAction.REQUEST -> handRequests[msg.clientId] = address
            HandRaiseAction.RELEASE, HandRaiseAction.DENY -> {
                handRequests.remove(msg.clientId)
                if (activeSpeaker == msg.clientId) endActiveSpeaker()
            }
            HandRaiseAction.APPROVE -> {}
        }
        refreshRoomUi()
    }

    private fun onGuestHandPacket(msg: HandRaisePacket) {
        if (msg.clientId != myClientId) return
        when (msg.action) {
            HandRaiseAction.APPROVE -> {
                // PIN room: build one sender for this whole approval (the host assigned the session ID).
                val key = guestKey
                pttSender = if (key != null && msg.sessionId != 0) PttSender(key, msg.sessionId, PTT_CHANNEL) else null
                approved = true
                handStatus = "✅ Approved. Hold the button to talk."
            }
            HandRaiseAction.DENY -> {
                approved = false
                pttSender = null
                stopTalking()
                handRaised = false
                handStatus = "Hand lowered by host"
            }
            else -> return
        }
        updateHandUi()
    }

    private fun toggleHand() {
        if (!running.get() || isHost) { toast("Join as a receiver first."); return }
        val host = hostAddress
        if (host == null) { toast("Waiting for the host's audio..."); return }
        handRaised = !handRaised
        if (!handRaised) {
            approved = false
            pttSender = null
            stopTalking()
        }
        handStatus = if (handRaised) "✋ Hand raised. Waiting for the host." else "Hand lowered"
        sendHand(host, HandRaisePacket(myClientId, if (handRaised) HandRaiseAction.REQUEST else HandRaiseAction.RELEASE))
        updateHandUi()
    }

    private fun updateHandUi() {
        runOnUiThread {
            handButton.text = if (handRaised) "Lower hand" else "Raise hand"
            handView.text = handStatus
            talkButton.isEnabled = approved
        }
    }

    private fun refreshRoomUi() {
        runOnUiThread {
            requestsBox.removeAllViews()
            if (isHost && running.get()) {
                tracker.removeStale()
                participantsView.text = "Participants: ${tracker.count()}"
                for ((id, addr) in handRequests) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(TextView(this).apply {
                        text = "✋ ${label(id)}"
                        setTextColor(0xFF12345A.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(Button(this).apply {
                        text = "Approve"
                        isAllCaps = false
                        setOnClickListener {
                            handRequests.remove(id)
                            endActiveSpeaker() // close the previous speaker's session first
                            val receiver = pttReceiver
                            val sessionId = if (receiver != null) nextPttSessionId().also { receiver.openSession(it) } else 0
                            activeSessionId = sessionId
                            activeSpeaker = id
                            sendHand(addr, HandRaisePacket(id, HandRaiseAction.APPROVE, sessionId))
                            refreshRoomUi()
                        }
                    })
                    row.addView(Button(this).apply {
                        text = "Deny"
                        isAllCaps = false
                        setOnClickListener {
                            handRequests.remove(id)
                            sendHand(addr, HandRaisePacket(id, HandRaiseAction.DENY))
                            refreshRoomUi()
                        }
                    })
                    requestsBox.addView(row)
                }
            } else if (!running.get()) {
                participantsView.text = "Participants: -"
            }
        }
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            if (!running.get() || !isHost) return
            refreshRoomUi()
            uiHandler.postDelayed(this, 2000)
        }
    }

    private fun onHostFound(address: String) {
        hostAddress = address
        heartbeatSender?.stop()
        heartbeatSender = HeartbeatSender(
            address, TELEMETRY_PORT, myClientId,
            { lossPercent().toByte() },
            { 0.toShort() } // jitter is not measured yet
        ).also { it.start() }
        runOnUiThread { participantsView.text = "Connected to host $address" }
    }

    // ---------- push to talk ----------

    /** Guest: start sending my mic to the host while the button is held (only when approved). */
    private fun startTalking() {
        if (!approved || isHost || !running.get()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return
        }
        val host = hostAddress ?: return
        val sender = pttSender
        if (guestKey != null && sender == null) {
            setState("Talk unavailable: host did not send a session ID")
            return
        }
        pttController?.stopTransmitting()
        pttController = PushToTalkController(host, PTT_PORT, myClientId, sender) { message ->
            setState("Talk error: $message")
        }.also { it.startTransmitting() }
        track?.setVolume(0f) // do not play my own voice back to me with a delay
        setState("Talking to everyone…")
    }

    private fun stopTalking() {
        if (pttController == null) return
        pttController?.stopTransmitting()
        pttController = null
        track?.setVolume(1f)
        if (running.get() && !isHost) setState("Receiving and decoding Opus audio")
    }

    /** Host: decode and play voice from the approved guest only. */
    private fun startPttListener(pin: String) {
        thread(name = "SmartCastPttReceive") {
            var sink: AudioTrack? = null
            try {
                // PIN room: only accept encrypted packets from the approved session.
                // Open room: keep the legacy [clientId][opus] format.
                val receiver = keyFor(pin)?.let { PttReceiver(it) }
                pttReceiver = receiver
                val s = openUdp(PTT_PORT)
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("AudioTrack does not support 48 kHz output on this device")
                val out = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
                    AudioTrack.MODE_STREAM
                )
                sink = out
                out.play()
                val decoder = OpusDecoder(SAMPLE_RATE, 1)
                val pcmOut = ShortArray(5760)
                val buf = ByteArray(8 + MAX_OPUS_BYTES + 64)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val opusData: ByteArray
                    val opusOffset: Int
                    val opusLength: Int
                    if (receiver != null) {
                        // Drops unknown sessions, bad tags and replays. Only the approved guest has an open session.
                        val packet = receiver.receive(p.data, p.length) ?: continue
                        opusData = packet.opusPayload
                        opusOffset = 0
                        opusLength = packet.opusPayload.size
                    } else {
                        if (p.length <= 8) continue
                        val id = ByteBuffer.wrap(p.data, p.offset, 8).long
                        if (id != activeSpeaker) continue
                        opusData = p.data
                        opusOffset = p.offset + 8
                        opusLength = p.length - 8
                    }
                    if (opusLength <= 0) continue
                    val samples = decoder.decode(opusData, opusOffset, opusLength, pcmOut, 0, pcmOut.size, false)
                    if (samples > 0) {
                        out.write(pcmOut, 0, samples) // the host hears the guest
                        guestFrames.add(pcmOut.copyOf(samples)) // and it is mixed into the broadcast
                        while (guestFrames.size > 10) guestFrames.poll() // cap delay at about 200 ms
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { sink?.pause(); sink?.flush(); sink?.stop(); sink?.release() } catch (_: Exception) {}
            }
        }
    }

    // ---------------------------------------------------------

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("SmartCastMulticast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Keeps the app alive with the screen off (foreground service + notification). */
    private fun startAudioService(hosting: Boolean) {
        try {
            startForegroundService(
                Intent(this, SmartCastAudioService::class.java)
                    .putExtra(SmartCastAudioService.EXTRA_HOST, hosting)
            )
        } catch (_: Exception) {
            // Continue without background protection rather than blocking audio.
        }
    }

    private fun startHost() {
        if (running.get()) { toast("Stop the current session first."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return
        }
        val pin = pinInput.text.toString().trim()
        isHost = true
        tracker.clear()
        handRequests.clear()
        pttReceiver = null
        activeSessionId = 0
        activeSpeaker = null
        guestFrames.clear()
        running.set(true)
        startAudioService(true)
        startHealthMonitoring()
        startHostListeners()
        startHandListener()
        startPttListener(pin)
        advertiser.start(PORT, "SmartCast Session", pin.isNotEmpty())
        uiHandler.post(refreshTick)
        setState(if (pin.isEmpty()) "Starting Opus broadcaster…" else "Starting encrypted broadcaster…")
        thread(name = "SmartCastOpusHost") {
            var input: AudioRecord? = null
            var udp: MulticastSocket? = null
            try {
                acquireMulticastLock()
                val key = keyFor(pin)
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("AudioRecord does not support 48 kHz input on this device")
                val mic = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
                )
                input = mic
                if (mic.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone could not be initialized")
                recorder = mic
                val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
                encoder.setBitrate(BITRATE)
                encoder.setComplexity(5)
                var currentBitrate = BITRATE
                val pcm = ShortArray(FRAME_SAMPLES)
                val encoded = ByteArray(MAX_OPUS_BYTES)
                val address = InetAddress.getByName(GROUP)
                val sock = MulticastSocket()
                udp = sock
                sock.timeToLive = 1
                socket = sock
                mic.startRecording()
                AudioEffectsManager.attachEffects(mic.audioSessionId)
                setState(if (key == null) "Broadcasting Opus audio over local Wi-Fi" else "Broadcasting encrypted audio (PIN room)")
                var sequence = 0
                var guestPrimed = false
                val sessionId = (System.nanoTime() xor android.os.Process.myPid().toLong()).toInt()
                while (running.get()) {
                    var filled = 0
                    while (running.get() && filled < FRAME_SAMPLES) {
                        val count = mic.read(pcm, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                        if (count < 0) throw IllegalStateException("Microphone read failed: $count")
                        if (count > 0) filled += count
                    }
                    if (!running.get()) break
                    // mix the approved guest's voice into the broadcast (jitter buffer: wait for 3 frames)
                    if (!guestPrimed && guestFrames.size >= 3) guestPrimed = true
                    if (guestPrimed) {
                        val guest = guestFrames.poll()
                        if (guest == null) {
                            guestPrimed = false
                        } else {
                            val n = minOf(guest.size, FRAME_SAMPLES)
                            for (i in 0 until n) {
                                pcm[i] = (pcm[i] + guest[i]).coerceIn(-32768, 32767).toShort()
                            }
                        }
                    }
                    // apply bitrate changes requested by the health monitor
                    val wanted = requestedBitrate
                    if (wanted != currentBitrate) {
                        encoder.setBitrate(wanted)
                        currentBitrate = wanted
                    }
                    val encodedLength = encoder.encode(pcm, 0, FRAME_SAMPLES, encoded, 0, encoded.size)
                    if (encodedLength > 0) {
                        val seq = sequence++
                        val clear = encoded.copyOfRange(0, encodedLength)
                        val body = if (key != null) RoomSecurity.encrypt(clear, key, ivFor(sessionId, seq)) else clear
                        val flags = if (key != null) FLAG_ENCRYPTED else 0
                        val packetData = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.BIG_ENDIAN)
                        packetData.putInt(MAGIC)
                        packetData.put(VERSION)
                        packetData.put(flags.toByte())
                        packetData.putInt(sessionId)
                        packetData.putInt(seq)
                        packetData.putLong(System.nanoTime())
                        packetData.putShort(body.size.toShort())
                        packetData.put(body)
                        val bytes = packetData.array()
                        sock.send(DatagramPacket(bytes, bytes.size, address, PORT))
                    }
                }
            } catch (e: Exception) {
                if (running.get()) setState("Broadcast error: ${e.message ?: "unknown"}")
                running.set(false)
            } finally {
                try { input?.stop() } catch (_: Exception) {}
                AudioEffectsManager.releaseEffects()
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
        val pin = pinInput.text.toString().trim()
        isHost = false
        hostAddress = null
        lostFrames.set(0)
        totalFrames.set(0)
        handRaised = false
        approved = false
        guestKey = null
        pttSender = null
        handStatus = "Hand raise: tap to ask to speak"
        updateHandUi()
        running.set(true)
        startAudioService(false)
        startHealthMonitoring()
        startHandListener()
        setState("Searching for Opus broadcast…")
        thread(name = "SmartCastOpusReceiver") {
            var udp: MulticastSocket? = null
            var output: AudioTrack? = null
            try {
                acquireMulticastLock()
                val key = keyFor(pin)
                guestKey = key // used later to encrypt my push-to-talk voice
                val groupAddress = InetAddress.getByName(GROUP)
                val sock = MulticastSocket(null)
                udp = sock
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(PORT))
                sock.joinGroup(groupAddress)
                socket = sock
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("AudioTrack does not support 48 kHz output on this device")
                val sink = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
                    AudioTrack.MODE_STREAM
                )
                output = sink
                track = sink
                val decoder = OpusDecoder(SAMPLE_RATE, 1)
                val pcmOut = ShortArray(5760) // Opus maximum frame: 120 ms at 48 kHz
                val receiveBuffer = ByteArray(HEADER_SIZE + MAX_OPUS_BYTES + 64)
                var activeSession = 0
                var expectedSequence: Int? = null
                var lockWarned = false
                var pinWarned = false
                var receivingShown = false
                sink.play()
                setState("Receiving and decoding Opus audio")
                while (running.get()) {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    sock.receive(packet)
                    if (packet.length < HEADER_SIZE) continue
                    val header = ByteBuffer.wrap(packet.data, packet.offset, packet.length).order(ByteOrder.BIG_ENDIAN)
                    if (header.int != MAGIC) continue
                    val version = header.get()
                    val flags = header.get().toInt()
                    val sessionId = header.int
                    val sequence = header.int
                    header.long // capture timestamp; reserved for latency diagnostics
                    val payloadLength = header.short.toInt() and 0xFFFF
                    if (version != VERSION || payloadLength <= 0 || payloadLength != packet.length - HEADER_SIZE) continue

                    val encrypted = (flags and FLAG_ENCRYPTED) != 0
                    if (encrypted != (key != null)) {
                        if (!lockWarned) {
                            lockWarned = true
                            setState(
                                if (encrypted) "Room is locked. Enter the PIN and rejoin."
                                else "Host is not using a PIN. Clear the PIN field and rejoin."
                            )
                        }
                        continue
                    }

                    if (activeSession != sessionId) {
                        activeSession = sessionId
                        expectedSequence = sequence
                    }
                    val expected = expectedSequence ?: sequence
                    val gap = sequence - expected
                    if (gap < 0) continue // late or duplicate packet

                    val raw = packet.data.copyOfRange(
                        packet.offset + HEADER_SIZE, packet.offset + HEADER_SIZE + payloadLength
                    )
                    val opus: ByteArray? = if (key != null) {
                        RoomSecurity.decrypt(raw, key, ivFor(sessionId, sequence)) ?: run {
                            if (!pinWarned) {
                                pinWarned = true
                                setState("Wrong PIN or corrupted audio.")
                            }
                            null
                        }
                    } else raw
                    if (opus == null) continue

                    lostFrames.addAndGet(gap)
                    totalFrames.addAndGet(gap + 1)
                    // Opus is stateful: conceal missing 20 ms frames before decoding the next packet.
                    if (gap in 1..5) {
                        for (i in 1..gap) {
                            val concealed = decoder.decode(null, 0, 0, pcmOut, 0, FRAME_SAMPLES, false)
                            if (concealed > 0) sink.write(pcmOut, 0, concealed)
                        }
                    }
                    val decodedSamples = decoder.decode(
                        opus, 0, opus.size,
                        pcmOut, 0, pcmOut.size, false
                    )
                    if (decodedSamples > 0) sink.write(pcmOut, 0, decodedSamples)
                    expectedSequence = sequence + 1

                    if (hostAddress == null) onHostFound(packet.address.hostAddress ?: "")
                    if (!receivingShown) {
                        receivingShown = true
                        setState(if (key == null) "Receiving and decoding Opus audio" else "Receiving encrypted audio (PIN room)")
                    }
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

    private fun stopSession(keepBanner: Boolean = false) {
        running.set(false)
        isHost = false
        stopTalking()
        stopHealthMonitoring()
        if (!keepBanner) hideBanner()
        uiHandler.removeCallbacks(refreshTick)
        heartbeatSender?.stop()
        heartbeatSender = null
        for (s in auxSockets) { try { s.close() } catch (_: Exception) {} }
        auxSockets.clear()
        try { stopService(Intent(this, SmartCastAudioService::class.java)) } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        releaseMulticastLock()
        recorder = null
        track = null
        socket = null
        handRequests.clear()
        endActiveSpeaker()
        pttReceiver = null
        pttSender = null
        guestKey = null
        guestFrames.clear()
        approved = false
        tracker.clear()
        hostAddress = null
        handRaised = false
        handStatus = HAND_IDLE
        updateHandUi()
        refreshRoomUi()
        runOnUiThread { qrImage.visibility = View.GONE }
        advertiser.stop()
        stopFindingSessions()
        setState("Stopped")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------- find sessions nearby (network discovery) ----------

    private fun findSessions() {
        if (running.get()) { toast("Stop the current session first."); return }
        stopFindingSessions()
        sessionsBox.addView(TextView(this).apply {
            tag = "searching"
            text = "Searching for sessions on this Wi-Fi…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF536579.toInt())
        })
        discovery = SmartCastDiscovery(applicationContext) { info ->
            runOnUiThread { addSessionRow(info.serviceName, info.host?.hostAddress ?: "", info.attributes["pin"]?.let { String(it) } == "1") }
        }.also { it.startDiscovery() }
    }

    private fun stopFindingSessions() {
        discovery?.stopDiscovery()
        discovery = null
        runOnUiThread { sessionsBox.removeAllViews() }
    }

    private fun addSessionRow(name: String, host: String, pinProtected: Boolean) {
        sessionsBox.findViewWithTag<View>("searching")?.let { sessionsBox.removeView(it) }
        val key = "$name@$host"
        if (sessionsBox.findViewWithTag<View>(key) != null) return // already listed
        sessionsBox.addView(button("$name · $host" + if (pinProtected) " 🔒" else "") {
            if (pinProtected && pinInput.text.toString().trim().isEmpty()) {
                toast("This room has a PIN. Enter it first.")
            } else {
                stopFindingSessions()
                toast("Joining $name…")
                startReceiver()
            }
        }.also { it.tag = key })
    }

    // ---------- join by QR code ----------

    /** This phone's IPv4 address on Wi-Fi or hotspot (hotspot interfaces are named ap/swlan on most phones). */
    private fun localIpv4(): String? {
        val found = mutableListOf<Pair<String, String>>() // interface name to IPv4 address
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                val ip = addr.hostAddress
                if (addr is Inet4Address && ip != null) found.add(Pair(nif.name, ip))
            }
        }
        val wifiLike = found.firstOrNull { (name, _) ->
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
        }
        return (wifiLike ?: found.firstOrNull())?.second
    }

    /** Host: show a QR code that guests scan to join. The PIN is never put in the QR code. */
    private fun showJoinQr() {
        if (qrImage.visibility == View.VISIBLE) {
            qrImage.visibility = View.GONE
            return
        }
        if (!running.get() || !isHost) { toast("Start broadcasting first."); return }
        val ip = localIpv4()
        if (ip == null) { toast("No Wi-Fi address found. Connect to Wi-Fi or turn on the hotspot."); return }
        try {
            val bitmap = QrCodeGenerator.generateSessionUriQr(ip, PORT, 1, "SmartCast Session", 600, 600)
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE
        } catch (e: Exception) {
            toast("Could not create the QR code.")
        }
    }

    /** Guest: scan the host's QR code with the camera, then join as a receiver. */
    private fun scanJoinQr() {
        if (running.get()) { toast("Stop the current session first."); return }
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val config = barcode.rawValue?.let { SessionConnectionConfig.parse(it) }
                if (config == null) {
                    toast("That is not a SmartCast QR code.")
                } else {
                    toast("Joining ${config.sessionName}…")
                    startReceiver() // enter the PIN first if the room has one
                }
            }
            .addOnFailureListener { e -> toast("Scanner unavailable: ${e.message ?: "unknown"}") }
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }
}
