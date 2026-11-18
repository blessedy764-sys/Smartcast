package com.smartcast.live

import com.smartcast.live.qa.HandRaiseAction
import com.smartcast.live.qa.HandRaisePacket
import com.smartcast.live.qa.PushToTalkController
import com.smartcast.live.relay.SmartCastRelay
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import com.smartcast.live.audio.AudioJitterBuffer
import com.smartcast.live.audio.AudioRouteManager
import com.smartcast.live.audio.Soundboard
import com.smartcast.live.chat.ChatCodec
import com.smartcast.live.chat.ChatMessage
import com.smartcast.live.chat.LiveChatView
import com.smartcast.live.discovery.SmartCastAdvertiser
import com.smartcast.live.eco.CongestionMonitor
import com.smartcast.live.discovery.SmartCastDiscovery
import com.smartcast.live.network.PollCodec
import com.smartcast.live.network.PttReceiver
import com.smartcast.live.network.PttSender
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import com.smartcast.live.room.ClientTracker
import com.smartcast.live.security.RoomSecurity
import com.smartcast.live.service.SmartCastAudioService
import com.smartcast.live.slides.SlideController
import com.smartcast.live.telemetry.DeviceHealthMonitor
import com.smartcast.live.telemetry.HeartbeatPacket
import com.smartcast.live.telemetry.HeartbeatSender
import cn.enaium.rnnoise.createRnnoise
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** SmartCast Live v2 prototype: Opus-compressed mono voice over local UDP multicast. */
class MainActivity : Activity() {
    companion object {
        private const val GROUP = "239.10.10.10"
        private const val PORT = 50005
        private const val TELEMETRY_PORT = 50006 // guests -> host heartbeats and poll answers
        private const val HAND_PORT = 50007      // hand raise requests and replies
        private const val PTT_PORT = 50008       // approved guest voice -> host
        private const val CHAT_PORT = 50009      // live chat + reactions (multicast, everyone <-> everyone)
        private const val SLIDE_PORT = 50010     // live slides, lyrics, PDF pages (multicast)
        private const val POLL_PORT = 50011      // live poll announcements (multicast)
        @Suppress("unused")
        private const val INTERPRETER_PORT = 50012 // reserved for the future interpreter
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz, mono
        private const val BITRATE = 32_000
        private const val LOW_BITRATE = 16_000 // used when the phone is warm
        private const val HEADER_SIZE = 24
        private const val MAGIC = 0x53434F50 // "SCOP"
        private const val VERSION: Byte = 1
        private const val MAX_OPUS_BYTES = 1275
        private const val REQUEST_MIC = 41
        private const val REQUEST_RELAY = 42
        private const val REQUEST_PAD_BASE = 200 // 200..203 = soundboard pad file pickers
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
    private lateinit var chatView: LiveChatView
    @Volatile private var chatSocket: MulticastSocket? = null
    @Volatile private var chatKey: SecretKeySpec? = null

    // live polls (host sends on the room's multicast group; answers come back on the telemetry port)
    private data class VoteRecord(val choice: Int, val seq: Int)
    private val netExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var hostPoll: PollCodec.Poll? = null        // host: the current poll
    private val pollVotes = ConcurrentHashMap<Long, VoteRecord>() // host: clientId -> latest answer
    private var guestPoll: PollCodec.Poll? = null                 // guest: poll shown on the card (UI thread)
    private var guestChoice = -1
    private var guestVoteSeq = 0
    private var lastClosedPollId: Int? = null
    private lateinit var pollQuestionInput: EditText
    private lateinit var pollTally: TextView
    private lateinit var pollCard: LinearLayout

    // soundboard
    private val soundboard by lazy { Soundboard(applicationContext) }

    // live slides, lyrics and PDF push (own multicast port, forwarded by the relay)
    private val slides by lazy {
        SlideController(
            this,
            send = { bytes ->
                socket?.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(roomGroup), SLIDE_PORT))
            },
            isHost = { isHost },
            isRunning = { running.get() },
            hostHasPin = { hostHasPin },
            crypto = {
                val key = if (isHost) chatKey else guestKey
                key?.let { k ->
                    SlideController.Crypto(
                        encrypt = { d, id -> RoomSecurity.encrypt(d, k, ivFor(id, SlideController.IV_SEQ)) },
                        decrypt = { d, id -> RoomSecurity.decrypt(d, k, ivFor(id, SlideController.IV_SEQ)) }
                    )
                }
            }
        )
    }

    // eco mode: the host steps the stream down when its hotspot struggles (send failures / slow sends)
    private val congestion = CongestionMonitor { level -> onEcoLevelChanged(level) }
    private lateinit var ecoButton: Button

    // multi-room: every host broadcasts on its own multicast group, so rooms never mix
    @Volatile private var roomGroup = GROUP
    private lateinit var roomNameInput: EditText
    private lateinit var roomView: TextView

    // audio routing (wired / Bluetooth / LE Audio / hearing aid / speaker)
    private lateinit var routes: AudioRouteManager
    private lateinit var routeView: TextView

    // relay / range extender (bridge node)
    private var relay: SmartCastRelay? = null
    @Volatile private var relayScanPending = false
    private lateinit var relayInfoView: TextView
    private lateinit var relayWifiQr: ImageView
    private lateinit var relayJoinQr: ImageView

    // unique join QR: each broadcast gets a fresh random session ID that goes into the QR code
    @Volatile private var hostSessionId = 0
    @Volatile private var hostRoomName = "SmartCast Session"
    @Volatile private var hostHasPin = false
    private val advertiser by lazy { SmartCastAdvertiser(applicationContext) } // host: announce the session
    private var discovery: SmartCastDiscovery? = null                         // guest: look for sessions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        routes = AudioRouteManager(this) { label -> routeView.text = label }
        routes.startMonitoring()
        SmartCastAudioService.onStopRequested = { runOnUiThread { stopSession() } }
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
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
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
        roomView = TextView(this).apply {
            text = "Room: not connected"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF12345A.toInt())
            setPadding(0, 0, 0, 12)
        }
        page.addView(roomView)
        routeView = TextView(this).apply {
            text = "Audio: -"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF536579.toInt())
            setPadding(0, 0, 0, 12)
        }
        page.addView(routeView)
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
        roomNameInput = EditText(this).apply {
            hint = "Room name for hosts (e.g. Main Hall)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        page.addView(roomNameInput)
        page.addView(button("Start broadcasting") { startHost() })
        page.addView(button("Stop session") { stopSession() })
        ecoButton = button("Eco mode: Auto") { cycleEcoMode() }
        page.addView(ecoButton)
        page.addView(button("Show join QR code") { showJoinQr() })
        page.addView(button("Scan QR code to join") { scanJoinQr() })
        qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            setPadding(0, 16, 0, 16)
        }
        page.addView(qrImage)
        page.addView(button("Find rooms / switch room") { findSessions() })
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(sessionsBox)

        // ---- Relay / range extender ----
        page.addView(section("RANGE EXTENDER (RELAY)"))
        page.addView(TextView(this).apply {
            text = "Place this phone halfway between the host and far-away guests. It stays on the host's Wi-Fi and opens its own hotspot that passes the session on."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF64748B.toInt())
        })
        page.addView(button("Start relay (scan host's QR)") { startRelayFlow() })
        relayInfoView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF12345A.toInt())
            setPadding(0, 8, 0, 4)
        }
        page.addView(relayInfoView)
        relayWifiQr = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            setPadding(0, 8, 0, 8)
        }
        page.addView(relayWifiQr)
        relayJoinQr = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            setPadding(0, 8, 0, 8)
        }
        page.addView(relayJoinQr)

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

        // ---- Live chat (the poll card and the slide pop up above the chat stream) ----
        page.addView(section("LIVE CHAT"))
        pollCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = 24f
                setStroke(3, 0xFF12345A.toInt())
            }
        }
        page.addView(pollCard)
        page.addView(slides.slideBox) // live slide / lyrics / PDF page, right above the chat feed
        chatView = LiveChatView(this).apply { onSend = { text, reaction -> sendChat(text, reaction) } }
        page.addView(chatView)

        // ---- Host: live poll ----
        page.addView(section("LIVE POLL (HOST)"))
        pollQuestionInput = EditText(this).apply {
            hint = "Question, e.g. Did everyone hear the deadline?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        page.addView(pollQuestionInput)
        page.addView(button("Send Yes / No") { startPoll(listOf("Yes", "No")) })
        page.addView(button("Send A / B / C / D") { startPoll(listOf("A", "B", "C", "D")) })
        page.addView(button("Close poll") { closePoll() })
        pollTally = TextView(this).apply {
            text = "No poll yet"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF12345A.toInt())
            setPadding(0, 12, 0, 4)
        }
        page.addView(pollTally)

        // ---- Host: soundboard ----
        page.addView(section("SOUNDBOARD (HOST)"))
        val padRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until Soundboard.PAD_COUNT) {
            padRow.addView(Button(this).apply {
                text = Soundboard.PAD_NAMES[i]
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { triggerPad(i) }
                setOnLongClickListener { pickPadFile(i); true }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        page.addView(padRow)
        page.addView(TextView(this).apply {
            text = "Tap a pad to play it to everyone. Long-press a pad to load your own 16-bit .wav file."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF64748B.toInt())
        })

        // ---- Host: live slides, lyrics and PDF ----
        page.addView(slides.hostPanel)

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
            text = "LAN multicast: one group per room (239.10.x.y). Ports: audio $PORT, chat $CHAT_PORT, slides $SLIDE_PORT, polls $POLL_PORT\nAudio: Opus, 48 kHz, mono, 32 kbps, 20 ms frames, RNNoise noise suppression on the host mic"
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

    // ---------- eco mode (network self-healing) ----------

    private fun cycleEcoMode() {
        congestion.setMode(
            when (congestion.mode()) {
                CongestionMonitor.Mode.AUTO -> CongestionMonitor.Mode.ECO
                CongestionMonitor.Mode.ECO -> CongestionMonitor.Mode.FULL
                CongestionMonitor.Mode.FULL -> CongestionMonitor.Mode.AUTO
            }
        )
        updateEcoButton()
    }

    private fun updateEcoButton() {
        runOnUiThread {
            ecoButton.text = when (congestion.mode()) {
                CongestionMonitor.Mode.AUTO -> "Eco mode: Auto (${congestion.levelName()})"
                CongestionMonitor.Mode.ECO -> "Eco mode: Always on (${congestion.levelName()})"
                CongestionMonitor.Mode.FULL -> "Eco mode: Off (full quality)"
            }
        }
    }

    /** Called (from the audio thread or the UI) whenever the stream steps down or recovers. */
    private fun onEcoLevelChanged(level: Int) {
        slides.setRepeatInterval(congestion.slideRepeatMs())
        updateEcoButton()
        if (!isHost || !running.get()) return
        val message = when (level) {
            CongestionMonitor.LEVEL_NORMAL -> "Network recovered. Full audio quality restored."
            CongestionMonitor.LEVEL_ECO -> "Network is busy. Eco mode on to keep everyone connected."
            else -> "Network is very congested. Survival mode: lowest bitrate."
        }
        runOnUiThread { toast(message) }
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
                    // poll answers share this port with heartbeats; their first 4 bytes tell them apart
                    if (p.length >= 4 && ByteBuffer.wrap(p.data, p.offset, 4).int == PollCodec.VOTE_MAGIC) {
                        PollCodec.decodeVote(p.data, p.length, chatKey)?.let { onVote(it) }
                        continue
                    }
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

    @Synchronized
    private fun onHostFound(address: String) {
        if (hostAddress != null) return // slides, polls and audio can all find the host; only the first counts
        hostAddress = address
        heartbeatSender?.stop()
        heartbeatSender = HeartbeatSender(
            address, TELEMETRY_PORT, myClientId,
            { lossPercent().toByte() },
            { 0.toShort() } // jitter is not measured yet
        ).also { it.start() }
        runOnUiThread { participantsView.text = "Connected to host $address" }
    }

    // ---------- live chat ----------

    /** Everyone (host and guests) joins the chat multicast group and listens for messages. */
    private fun startChat(pin: String) {
        val group = roomGroup // chat lives inside the room's own multicast group
        chatView.clear()
        thread(name = "SmartCastChat") {
            var sock: MulticastSocket? = null
            try {
                acquireMulticastLock()
                val key = keyFor(pin)
                chatKey = key
                val s = MulticastSocket(null)
                sock = s
                s.reuseAddress = true
                s.bind(InetSocketAddress(CHAT_PORT))
                s.joinGroup(InetAddress.getByName(group))
                auxSockets.add(s) // closed by stopSession
                chatSocket = s
                val buf = ByteArray(1024)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val msg = ChatCodec.decode(p.data, p.length, key) ?: continue
                    if (msg.senderId == myClientId) continue // my own message is already on screen
                    runOnUiThread {
                        chatView.addMessage(
                            if (msg.isHost) "HOST" else label(msg.senderId),
                            msg.isHost, msg.text, msg.isReaction
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                chatSocket = null
                try { sock?.leaveGroup(InetAddress.getByName(group)) } catch (_: Exception) {}
                try { sock?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Shows the message on this phone immediately, then broadcasts it to everyone else. */
    private fun sendChat(text: String, reaction: Boolean) {
        val clean = text.trim().take(ChatCodec.MAX_CHARS)
        if (clean.isEmpty()) return
        if (!running.get()) { toast("Start or join a session first."); return }
        chatView.addMessage(if (isHost) "HOST" else label(myClientId), isHost, clean, reaction)
        val s = chatSocket ?: return
        val bytes = ChatCodec.encode(ChatMessage(myClientId, isHost, reaction, clean), chatKey)
        thread(name = "SmartCastChatSend") {
            try {
                s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(roomGroup), CHAT_PORT))
            } catch (_: Exception) {}
        }
    }

    /** Guest: listen on one multicast port of the room's group (slides, polls) and pass each packet to [handler]. */
    private fun startRoomListener(name: String, port: Int, group: String, handler: (DatagramPacket) -> Unit) {
        thread(name = name) {
            var sock: MulticastSocket? = null
            try {
                acquireMulticastLock()
                val s = MulticastSocket(null)
                sock = s
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                s.joinGroup(InetAddress.getByName(group))
                auxSockets.add(s) // closed by stopSession
                val buf = ByteArray(2048)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    handler(p)
                }
            } catch (_: Exception) {
            } finally {
                try { sock?.leaveGroup(InetAddress.getByName(group)) } catch (_: Exception) {}
                try { sock?.close() } catch (_: Exception) {}
            }
        }
    }

    // ---------- live polls ----------

    /** Host: announce a poll to everyone on the room's multicast group (own port, so audio is never touched). */
    private fun sendPoll(poll: PollCodec.Poll) {
        val key = chatKey
        if (hostHasPin && key == null) return // the room key is still being derived
        val bytes = PollCodec.encodePoll(poll, key)
        val group = roomGroup
        netExecutor.execute {
            try {
                socket?.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(group), POLL_PORT))
            } catch (_: Exception) {}
        }
    }

    private val pollTick = object : Runnable {
        override fun run() {
            val poll = hostPoll
            if (!running.get() || !isHost || poll == null || !poll.open) return
            sendPoll(poll) // multicast is not guaranteed, so repeat so late joiners and lost packets catch up
            uiHandler.postDelayed(this, 2000)
        }
    }

    private fun startPoll(options: List<String>) {
        if (!running.get() || !isHost) { toast("Start broadcasting first."); return }
        val question = pollQuestionInput.text.toString().trim().take(80)
        if (question.isEmpty()) { toast("Type a question first."); return }
        if (hostHasPin && chatKey == null) { toast("Room is still starting. Try again in a second."); return }
        uiHandler.removeCallbacks(pollTick)
        pollVotes.clear()
        val poll = PollCodec.Poll(SecureRandom().nextInt(), true, question, options)
        hostPoll = poll
        sendPoll(poll)
        uiHandler.postDelayed(pollTick, 2000)
        refreshPollTally()
    }

    private fun closePoll() {
        val poll = hostPoll
        if (poll == null || !poll.open) { toast("No poll is open."); return }
        uiHandler.removeCallbacks(pollTick)
        val closed = poll.copy(open = false)
        hostPoll = closed
        sendPoll(closed) // the close notice is repeated because multicast can drop packets
        uiHandler.postDelayed({ sendPoll(closed) }, 500)
        uiHandler.postDelayed({ sendPoll(closed) }, 1200)
        refreshPollTally()
    }

    /** Host: one answer arrived (the latest answer from each listener counts). */
    private fun onVote(vote: PollCodec.Vote) {
        val poll = hostPoll ?: return
        if (!poll.open || vote.pollId != poll.id || vote.choice !in poll.options.indices) return
        pollVotes.compute(vote.clientId) { _, old ->
            if (old == null || vote.seq > old.seq) VoteRecord(vote.choice, vote.seq) else old
        }
        refreshPollTally()
    }

    private fun refreshPollTally() {
        runOnUiThread {
            val poll = hostPoll
            if (poll == null) {
                pollTally.text = "No poll yet"
                return@runOnUiThread
            }
            val counts = IntArray(poll.options.size)
            for (record in pollVotes.values) if (record.choice in counts.indices) counts[record.choice]++
            val total = counts.sum()
            val sb = StringBuilder()
            sb.append(if (poll.open) "● LIVE: " else "Closed: ").append(poll.question).append('\n')
            poll.options.forEachIndexed { i, label ->
                val pct = if (total == 0) 0 else counts[i] * 100 / total
                val filled = pct / 10
                sb.append(label.padEnd(4)).append(' ')
                    .append("█".repeat(filled)).append("░".repeat(10 - filled))
                    .append(" ").append(pct).append("% (").append(counts[i]).append(")\n")
            }
            sb.append("Answered: ").append(total).append(" of ").append(tracker.count()).append(" listeners")
            pollTally.text = sb.toString()
        }
    }

    private fun resetHostPoll() {
        uiHandler.removeCallbacks(pollTick)
        hostPoll = null
        pollVotes.clear()
        refreshPollTally()
    }

    /** Guest: a poll packet arrived on the poll port. */
    private fun onPollPacket(data: ByteArray, length: Int, key: SecretKeySpec?) {
        val poll = PollCodec.decodePoll(data, length, key) ?: return
        runOnUiThread { showPoll(poll) }
    }

    private fun showPoll(poll: PollCodec.Poll) {
        if (isHost || !running.get()) return
        if (!poll.open) {
            val wasShowing = guestPoll?.id == poll.id
            lastClosedPollId = poll.id
            if (wasShowing) {
                guestPoll = null
                pollCard.removeAllViews()
                pollCard.addView(TextView(this).apply {
                    text = "Poll closed. Thanks!"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(0xFF12345A.toInt())
                })
                uiHandler.postDelayed({ if (guestPoll == null) pollCard.visibility = View.GONE }, 3000)
            }
            return
        }
        if (poll.id == lastClosedPollId) return // a late repeat of a poll that already ended
        if (guestPoll?.id == poll.id) return    // the same poll announced again
        guestPoll = poll
        guestChoice = -1
        renderPollCard()
    }

    private fun renderPollCard() {
        val poll = guestPoll ?: return
        pollCard.removeAllViews()
        pollCard.addView(TextView(this).apply {
            text = "📊 ${poll.question}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF12345A.toInt())
            setPadding(0, 0, 0, 8)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        poll.options.forEachIndexed { i, label ->
            row.addView(Button(this).apply {
                text = if (i == guestChoice) "✓ $label" else label
                isAllCaps = false
                setOnClickListener { castVote(poll, i) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        pollCard.addView(row)
        if (guestChoice >= 0) {
            pollCard.addView(TextView(this).apply {
                text = "Answer sent. Tap another option to change it."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF64748B.toInt())
            })
        }
        pollCard.visibility = View.VISIBLE
    }

    private fun castVote(poll: PollCodec.Poll, choice: Int) {
        val host = hostAddress
        if (host == null) { toast("Waiting for the host..."); return }
        guestChoice = choice
        renderPollCard()
        val bytes = PollCodec.encodeVote(PollCodec.Vote(poll.id, myClientId, choice, ++guestVoteSeq), guestKey)
        thread(name = "SmartCastVote") {
            // sent three times in case a UDP packet is lost; the host keeps only the newest answer
            repeat(3) {
                try {
                    DatagramSocket().use {
                        it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), TELEMETRY_PORT))
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun resetGuestPoll() {
        guestPoll = null
        guestChoice = -1
        lastClosedPollId = null
        runOnUiThread {
            pollCard.removeAllViews()
            pollCard.visibility = View.GONE
        }
    }

    // ---------- soundboard ----------

    private fun triggerPad(index: Int) {
        if (!running.get() || !isHost) { toast("Start broadcasting first."); return }
        soundboard.trigger(index)
    }

    private fun pickPadFile(index: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        try {
            startActivityForResult(intent, REQUEST_PAD_BASE + index)
        } catch (e: Exception) {
            toast("No file picker available.")
        }
    }

    private fun loadPadFile(pad: Int, uri: Uri) {
        thread(name = "SmartCastPadLoad") {
            val error: String? = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not open that file")
                if (bytes.size > 25_000_000) throw IllegalStateException("File too large (25 MB maximum)")
                soundboard.install(pad, bytes)
            } catch (e: Exception) {
                e.message ?: "Could not load that file"
            }
            runOnUiThread { toast(error ?: "${Soundboard.PAD_NAMES[pad]} pad updated") }
        }
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
        routes.enterCommunication() // headset mic while talking
        track?.setVolume(0f) // do not play my own voice back to me with a delay
        setState("Talking to everyone…")
    }

    private fun stopTalking() {
        if (pttController == null) return
        pttController?.stopTransmitting()
        pttController = null
        routes.leaveCommunication() // back to the high-quality media route
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
                // Voice-communication usage so the guest's voice follows the host's communication device.
                val out = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, FRAME_SAMPLES * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
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

    @Synchronized
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("SmartCastMulticast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Keeps the app audio-eligible with the screen off (typed foreground service + media notification).
     * Always call this from a visible screen (a button tap) so the service gets while-in-use capabilities.
     */
    private fun startAudioService(hosting: Boolean, room: String) {
        try {
            startForegroundService(
                Intent(this, SmartCastAudioService::class.java)
                    .putExtra(SmartCastAudioService.EXTRA_HOST, hosting)
                    .putExtra(SmartCastAudioService.EXTRA_ROOM, room)
            )
        } catch (e: Exception) {
            setState("Background audio unavailable: ${e.message ?: "unknown"}")
        }
    }

    private fun startHost() {
        if (running.get()) { toast("Stop the current session first."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return
        }
        val pin = pinInput.text.toString().trim()
        val group = groupForIp(localIpv4()) ?: run {
            toast("No Wi-Fi address found. Connect to Wi-Fi or turn on the hotspot.")
            return
        }
        val roomName = roomNameInput.text.toString().trim().ifEmpty { "SmartCast Session" }
        roomGroup = group
        hostSessionId = SecureRandom().nextInt().let { if (it == 0) 1 else it } // new QR for every broadcast
        hostRoomName = roomName
        hostHasPin = pin.isNotEmpty()
        setRoomLabel("Room: $roomName ($group)")
        isHost = true
        tracker.clear()
        handRequests.clear()
        pttReceiver = null
        activeSessionId = 0
        activeSpeaker = null
        guestFrames.clear()
        resetHostPoll()
        congestion.reset()
        soundboard.stopAll()
        thread(name = "SmartCastSoundboardLoad") { soundboard.preloadAll() }
        running.set(true)
        startAudioService(true, roomName)
        startHealthMonitoring()
        startHostListeners()
        startHandListener()
        startPttListener(pin)
        startChat(pin)
        advertiser.start(PORT, roomName, pin.isNotEmpty())
        uiHandler.post(refreshTick)
        setState(if (pin.isEmpty()) "Starting Opus broadcaster…" else "Starting encrypted broadcaster…")
        thread(name = "SmartCastOpusHost") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var input: AudioRecord? = null
            var udp: MulticastSocket? = null
            var denoiser: AutoCloseable? = null
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
                // In-band FEC: each packet carries a low-bitrate copy of the previous frame,
                // so the receiver's jitter buffer can recover a single lost packet.
                encoder.setUseInbandFEC(true)
                encoder.setPacketLossPercent(10)
                // RNNoise works on 10 ms frames (480 samples at 48 kHz), so each 20 ms frame is processed in two halves.
                val dn = createRnnoise()
                denoiser = dn
                val rnIn = FloatArray(dn.frameSize)
                val rnOut = FloatArray(dn.frameSize)
                var currentBitrate = BITRATE
                var currentComplexity = 5
                val pcm = ShortArray(FRAME_SAMPLES)
                val encoded = ByteArray(MAX_OPUS_BYTES)
                val address = InetAddress.getByName(group)
                val sock = MulticastSocket()
                udp = sock
                sock.timeToLive = 1
                socket = sock
                routes.enterCommunication() // pick headset / speaker route before the mic opens
                mic.startRecording()
                AudioEffectsManager.attachEffects(mic.audioSessionId)
                setState(if (key == null) "Broadcasting Opus audio over local Wi-Fi" else "Broadcasting encrypted audio (PIN room)")
                var sequence = 0
                var guestPrimed = false
                val sessionId = hostSessionId // same value the join QR code carries
                while (running.get()) {
                    var filled = 0
                    while (running.get() && filled < FRAME_SAMPLES) {
                        val count = mic.read(pcm, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                        if (count < 0) throw IllegalStateException("Microphone read failed: $count")
                        if (count > 0) filled += count
                    }
                    if (!running.get()) break
                    // RNNoise: clean the host microphone before guest voice and soundboard are mixed in
                    if (congestion.denoiseEnabled()) for (h in 0 until FRAME_SAMPLES / dn.frameSize) {
                        val base = h * dn.frameSize
                        for (i in 0 until dn.frameSize) rnIn[i] = pcm[base + i] / 32768f
                        dn.processFrame(rnIn, rnOut)
                        for (i in 0 until dn.frameSize) {
                            pcm[base + i] = (rnOut[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                        }
                    }
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
                    // mix any playing soundboard pads into the broadcast
                    soundboard.mixInto(pcm)
                    // apply bitrate changes requested by the health monitor
                    val wanted = minOf(requestedBitrate, congestion.bitrateCap()) // thermal and congestion limits both apply
                    if (wanted != currentBitrate) {
                        encoder.setBitrate(wanted)
                        currentBitrate = wanted
                    }
                    val wantedComplexity = congestion.complexity()
                    if (wantedComplexity != currentComplexity) {
                        encoder.setComplexity(wantedComplexity)
                        currentComplexity = wantedComplexity
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
                        // A failed or slow send is a congestion signal, not a reason to end the broadcast.
                        val sentAt = System.nanoTime()
                        var sendFailed = false
                        try {
                            sock.send(DatagramPacket(bytes, bytes.size, address, PORT))
                        } catch (_: java.io.IOException) {
                            sendFailed = true
                        }
                        congestion.onSend(System.nanoTime() - sentAt, sendFailed)
                        // ~5 s of nothing getting through means the socket or Wi-Fi is gone, not just busy
                        if (congestion.consecutiveFailures() > 250) throw IllegalStateException("Network unavailable")
                    }
                }
            } catch (e: Exception) {
                if (running.get()) setState("Broadcast error: ${e.message ?: "unknown"}")
                running.set(false)
            } finally {
                try { input?.stop() } catch (_: Exception) {}
                try { denoiser?.close() } catch (_: Exception) {}
                AudioEffectsManager.releaseEffects()
                try { input?.release() } catch (_: Exception) {}
                try { udp?.close() } catch (_: Exception) {}
                recorder = null
                socket = null
                routes.leaveCommunication()
                releaseMulticastLock()
            }
        }
    }

    /** [requiredSession] is set when joining by QR: only audio from that exact broadcast is accepted. */
    private fun startReceiver(group: String, roomName: String, requiredSession: Int? = null) {
        if (running.get()) { toast("Stop the current session first."); return }
        val pin = pinInput.text.toString().trim()
        roomGroup = group
        setRoomLabel("Room: $roomName ($group)")
        isHost = false
        hostAddress = null
        lostFrames.set(0)
        totalFrames.set(0)
        handRaised = false
        approved = false
        guestKey = null
        pttSender = null
        resetGuestPoll()
        slides.resetReceiver()
        handStatus = "Hand raise: tap to ask to speak"
        updateHandUi()
        running.set(true)
        startAudioService(false, roomName)
        startHealthMonitoring()
        startHandListener()
        startChat(pin)
        // slides and polls have their own ports, so they never compete with the audio loop below
        val roomKey = lazy { keyFor(pin) }
        startRoomListener("SmartCastSlideListener", SLIDE_PORT, group) { p ->
            if (slides.onPacket(p.data, p.offset, p.length) && hostAddress == null) {
                onHostFound(p.address.hostAddress ?: "")
            }
        }
        startRoomListener("SmartCastPollListener", POLL_PORT, group) { p ->
            onPollPacket(p.data, p.length, roomKey.value)
            if (hostAddress == null) onHostFound(p.address.hostAddress ?: "")
        }
        setState("Searching for Opus broadcast…")
        thread(name = "SmartCastOpusReceiver") {
            var udp: MulticastSocket? = null
            var output: AudioTrack? = null
            var playout: Thread? = null
            try {
                acquireMulticastLock()
                val key = keyFor(pin)
                guestKey = key // used later to encrypt my push-to-talk voice, poll answers and to decrypt slides
                val groupAddress = InetAddress.getByName(group)
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
                val jitter = AudioJitterBuffer() // 60 ms target, 240 ms cap, FEC + concealment
                val receiveBuffer = ByteArray(HEADER_SIZE + MAX_OPUS_BYTES + 64)
                var activeSession = 0
                var expectedSequence: Int? = null
                var lockWarned = false
                var qrWarned = false
                var pinWarned = false
                var receivingShown = false
                sink.play()
                // Playout thread: decodes one 20 ms frame at a time from the jitter buffer.
                // sink.write blocks, which paces this loop to real time.
                playout = thread(name = "SmartCastPlayout") {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        while (running.get()) {
                            val n = jitter.popAndDecode(decoder, pcmOut, FRAME_SAMPLES)
                            if (n > 0) sink.write(pcmOut, 0, n) else Thread.sleep(5)
                        }
                    } catch (_: Exception) {}
                }
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

                    // QR join: ignore any broadcast other than the one the scanned QR code belongs to
                    if (requiredSession != null && sessionId != requiredSession) {
                        if (!qrWarned) {
                            qrWarned = true
                            setState("This QR code is old or for another broadcast. Scan the host's current QR code.")
                        }
                        continue
                    }

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
                        jitter.reset() // a new host session restarts sequence numbers at 0
                    }
                    val expected = expectedSequence ?: sequence
                    val gap = sequence - expected // negative = reordered; the jitter buffer decides

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

                    if (gap >= 0) {
                        lostFrames.addAndGet(gap)
                        totalFrames.addAndGet(gap + 1)
                        expectedSequence = sequence + 1
                    }
                    jitter.push(sequence, opus)

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
                try { udp?.leaveGroup(InetAddress.getByName(group)) } catch (_: Exception) {}
                try { playout?.join(500) } catch (_: Exception) {}
                try { output?.pause(); output?.flush(); output?.stop(); output?.release() } catch (_: Exception) {}
                try { udp?.close() } catch (_: Exception) {}
                track = null
                socket = null
                releaseMulticastLock()
            }
        }
    }

    @Synchronized
    private fun releaseMulticastLock() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
    }

    private fun stopSession(keepBanner: Boolean = false) {
        slides.stop() // first, so receivers get a "clear" before the sockets close
        running.set(false)
        isHost = false
        stopTalking()
        routes.leaveCommunication()
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
        chatSocket = null
        chatKey = null
        runOnUiThread { chatView.clear() }
        approved = false
        tracker.clear()
        hostAddress = null
        handRaised = false
        handStatus = HAND_IDLE
        updateHandUi()
        refreshRoomUi()
        runOnUiThread { qrImage.visibility = View.GONE }
        advertiser.stop()
        stopRelay()
        stopFindingSessions()
        resetHostPoll()
        resetGuestPoll()
        congestion.reset()
        soundboard.stopAll()
        setRoomLabel("Room: not connected")
        setState("Stopped")
    }

    private fun setRoomLabel(text: String) {
        runOnUiThread { roomView.text = text }
    }

    /**
     * Each host gets its own multicast group derived from its Wi-Fi IP (192.168.1.23 -> 239.10.1.23).
     * Two hosts can never pick the same group, and a guest can work it out from the host's address.
     */
    private fun groupForIp(ip: String?): String? {
        val parts = ip?.split(".") ?: return null
        if (parts.size != 4) return null
        val third = parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val fourth = parts[3].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        return "239.10.$third.$fourth"
    }

    /** Join a room, or switch to another one if already listening. */
    private fun joinRoom(name: String, host: String, pinProtected: Boolean, requiredSession: Int? = null) {
        if (isHost && running.get()) { toast("Stop broadcasting before joining another room."); return }
        if (pinProtected && pinInput.text.toString().trim().isEmpty()) {
            toast("This room has a PIN. Enter it first.")
            return
        }
        val group = groupForIp(host)
        if (group == null) { toast("Could not work out this room's address."); return }
        if (running.get() && group == roomGroup && requiredSession == null) { toast("You are already in $name."); return }
        toast("Joining $name…")
        if (running.get()) {
            // switching channels: close the old room completely, then open the new one
            stopSession()
            uiHandler.postDelayed({ if (!running.get()) startReceiver(group, name, requiredSession) }, 700)
        } else {
            stopFindingSessions()
            startReceiver(group, name, requiredSession)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------- find sessions nearby (network discovery) ----------

    private fun findSessions() {
        if (isHost && running.get()) { toast("Stop broadcasting first."); return }
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
        val current = running.get() && !isHost && groupForIp(host) == roomGroup
        val label = (if (current) "▶ " else "") + "$name · $host" + if (pinProtected) " 🔒" else ""
        sessionsBox.addView(button(label) { joinRoom(name, host, pinProtected) }.also { it.tag = key })
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
            // The PIN is never in the QR. "sid" is this broadcast's random ID, so every session has its own QR.
            val sid = Integer.toHexString(hostSessionId)
            val uri = "smartcast://join?v=2&host=$ip&port=$PORT&room=${Uri.encode(hostRoomName)}" +
                "&sid=$sid&pin=${if (hostHasPin) 1 else 0}"
            val bitmap = qrBitmap(uri, 700)
            qrImage.setImageBitmap(bitmap)
            qrImage.visibility = View.VISIBLE
        } catch (e: Exception) {
            toast("Could not create the QR code.")
        }
    }

    /** Draws a QR code bitmap with ZXing (works fully offline). */
    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Guest: open the camera (ZXing scanner, bundled in the app, no downloads) and scan the host's QR code. */
    private fun scanJoinQr() {
        if (isHost && running.get()) { toast("Stop broadcasting first."); return }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Point the camera at the host's QR code")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // live slides: an image, PDF or deck was picked
        if (slides.onActivityResult(requestCode, resultCode, data)) return
        // soundboard: a .wav file was picked for a pad
        val pad = requestCode - REQUEST_PAD_BASE
        if (pad in 0 until Soundboard.PAD_COUNT) {
            if (resultCode == RESULT_OK) data?.data?.let { loadPadFile(pad, it) }
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            val text = result.contents
            if (text == null) {
                relayScanPending = false
                toast("Scan cancelled.")
            } else if (relayScanPending) {
                relayScanPending = false
                startRelayFromQr(text)
            } else {
                handleScannedQr(text)
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** Reads smartcast://join?host=..&sid=..&room=..&pin=.. and joins that exact broadcast. */
    private fun handleScannedQr(text: String) {
        val uri = try { Uri.parse(text) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "smartcast" || uri.host != "join") {
            toast("That is not a SmartCast QR code.")
            return
        }
        val host = uri.getQueryParameter("host")
        val sid = uri.getQueryParameter("sid")?.takeIf { it.length in 1..8 }?.let {
            try { it.toLong(16).toInt() } catch (_: Exception) { null }
        }
        if (host == null || sid == null) {
            toast("This QR code is incomplete or from an older version.")
            return
        }
        val name = (uri.getQueryParameter("room") ?: "SmartCast Session").take(40)
        val pinProtected = uri.getQueryParameter("pin") == "1"
        joinRoom(name, host, pinProtected, sid) // enter the PIN first if the room has one
    }

    // ---------- relay / range extender ----------

    private fun relayPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Relay: ask for the hotspot permission, then scan the host's QR code. */
    private fun startRelayFlow() {
        if (running.get()) { toast("Stop the current session first."); return }
        val missing = relayPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_RELAY)
            toast("Allow the permission, then tap Start relay again.")
            return
        }
        relayScanPending = true
        scanJoinQr()
    }

    private fun startRelayFromQr(text: String) {
        val uri = try { Uri.parse(text) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "smartcast" || uri.host != "join") {
            toast("That is not a SmartCast QR code.")
            return
        }
        val host = uri.getQueryParameter("host")
        val sid = uri.getQueryParameter("sid")?.takeIf { it.length in 1..8 }?.let {
            try { it.toLong(16).toInt() } catch (_: Exception) { null }
        }
        if (host == null || sid == null) {
            toast("This QR code is incomplete or from an older version.")
            return
        }
        if (running.get()) { toast("Stop the current session first."); return }
        val name = (uri.getQueryParameter("room") ?: "SmartCast Session").take(40)
        val pinProtected = uri.getQueryParameter("pin") == "1"
        isHost = false
        running.set(true)
        setRoomLabel("Relay for: $name")
        startAudioService(false, "Relay: $name")
        startHealthMonitoring()
        val r = SmartCastRelay(
            applicationContext, host, sid,
            onStatus = { setState(it) },
            onInfo = { info -> runOnUiThread { showRelayInfo(info, name, pinProtected) } },
            onFailed = { message ->
                runOnUiThread {
                    stopSession()
                    setState(message)
                }
            }
        )
        relay = r
        setState("Starting relay…")
        r.start()
    }

    private fun qrEscape(s: String) = s
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace(":", "\\:").replace("\"", "\\\"")

    /** Relay: show the hotspot Wi-Fi QR and the SmartCast join QR for guests near this phone. */
    private fun showRelayInfo(info: SmartCastRelay.Info, room: String, pinProtected: Boolean) {
        try {
            val wifi = "WIFI:T:WPA;S:${qrEscape(info.ssid)};P:${qrEscape(info.password)};;"
            relayWifiQr.setImageBitmap(qrBitmap(wifi, 600))
            relayWifiQr.visibility = View.VISIBLE
            val join = "smartcast://join?v=2&host=${info.relayIp}&port=$PORT&room=${Uri.encode(room)}" +
                "&sid=${Integer.toHexString(info.sessionId)}&pin=${if (pinProtected) 1 else 0}"
            relayJoinQr.setImageBitmap(qrBitmap(join, 600))
            relayJoinQr.visibility = View.VISIBLE
            relayInfoView.text = "Step 1: guests join the Wi-Fi \"${info.ssid}\" (first QR, or type the password: ${info.password}).\n" +
                "Step 2: guests open SmartCast and tap Scan QR code to join, then scan the second QR."
        } catch (e: Exception) {
            toast("Could not create the relay QR codes.")
        }
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
        relayScanPending = false
        runOnUiThread {
            relayWifiQr.visibility = View.GONE
            relayJoinQr.visibility = View.GONE
            relayInfoView.text = ""
        }
    }

    override fun onDestroy() {
        stopSession()
        routes.stopMonitoring()
        SmartCastAudioService.onStopRequested = null
        super.onDestroy()
    }
}
