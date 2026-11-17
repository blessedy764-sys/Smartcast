package com.smartcast.live.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.smartcast.live.network.PollCodec
import com.smartcast.live.qa.HandRaisePacket
import com.smartcast.live.telemetry.HeartbeatPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Bridge node: stays on the host's Wi-Fi, opens a local-only hotspot, and forwards the session between the two.
 *  - Down (host -> guests): audio, polls and chat are re-sent on the hotspot's own multicast group, untouched
 *    (so PIN rooms keep working: the relay never decrypts anything).
 *  - Up (guests -> host): heartbeats, poll answers, hand raises and push-to-talk are passed to the host.
 *    Host replies to hand raises are routed back to the right guest by client ID.
 *  - Chat is bridged both ways.
 */
class SmartCastRelay(
    context: Context,
    private val hostIp: String,
    hostSessionId: Int,
    private val onStatus: (String) -> Unit,
    private val onInfo: (Info) -> Unit,
    private val onFailed: (String) -> Unit
) {
    data class Info(val ssid: String, val password: String, val relayIp: String, val sessionId: Int)

    companion object {
        private const val PORT = 50005
        private const val TELEMETRY_PORT = 50006
        private const val HAND_PORT = 50007
        private const val PTT_PORT = 50008
        private const val CHAT_PORT = 50009
        private const val AUDIO_MAGIC = 0x53434F50
        private const val BUF = 2048
    }

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<DatagramSocket>()
    private val handClients = ConcurrentHashMap<Long, InetAddress>() // guest clientId -> its hotspot address
    private val guestSeen = ConcurrentHashMap<Long, Long>()          // guest clientId -> last heartbeat time
    @Volatile private var sessionId = hostSessionId
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var ssid = ""
    @Volatile private var password = ""
    @Volatile private var relayIp = ""

    @Suppress("DEPRECATION")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            multicastLock = wifi.createMulticastLock("SmartCastRelayMulticast").apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartCastRelayWifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    thread(name = "SmartCastRelaySetup") { begin(res) }
                }

                override fun onStopped() {
                    if (running.get()) fail("The relay hotspot was stopped by the system.")
                }

                override fun onFailed(reason: Int) {
                    fail(failText(reason))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            fail("Permission missing for the relay hotspot. Allow it and try again.")
        } catch (e: Exception) {
            fail("Could not start the relay hotspot: ${e.message ?: "unknown"}")
        }
    }

    fun stop() {
        running.set(false)
        for (s in sockets) { try { s.close() } catch (_: Exception) {} }
        sockets.clear()
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wifiLock = null
        handClients.clear()
        guestSeen.clear()
    }

    private fun fail(message: String) {
        if (!running.get()) return
        stop()
        onFailed(message)
    }

    private fun failText(reason: Int): String = when (reason) {
        1 -> "No free Wi-Fi channel for the relay hotspot."
        3 -> "The hotspot can't start right now. Turn off the normal hotspot and Wi-Fi Direct, then try again."
        4 -> "Hotspots are turned off on this phone."
        else -> "Could not start the relay hotspot (code $reason)."
    }

    private fun begin(res: WifiManager.LocalOnlyHotspotReservation) {
        try {
            val creds = credentials(res) ?: throw IllegalStateException("Could not read the hotspot name and password")
            ssid = creds.first
            password = creds.second

            // The hotspot interface can take a moment to get its address.
            var found: Pair<Pair<NetworkInterface, Inet4Address>, Pair<NetworkInterface, Inet4Address>>? = null
            for (attempt in 0 until 12) {
                if (!running.get()) return
                found = findInterfaces()
                if (found != null) break
                Thread.sleep(500)
            }
            val (station, ap) = found ?: throw IllegalStateException(
                "Could not find both networks. This phone may not support Wi-Fi and hotspot at the same time."
            )
            val stationIf = station.first
            val stationIp = station.second.hostAddress ?: ""
            val apIf = ap.first
            val apIp = ap.second.hostAddress ?: ""
            if (stationIp.substringBeforeLast('.') == apIp.substringBeforeLast('.')) {
                throw IllegalStateException("The relay hotspot landed on the same network range as the host. Stop and start the relay again.")
            }
            val hostGroup = groupForIp(hostIp) ?: throw IllegalStateException("Bad host address")
            val guestGroup = groupForIp(apIp) ?: throw IllegalStateException("Bad relay address")
            if (hostGroup == guestGroup) {
                throw IllegalStateException("Network clash. Stop and start the relay again.")
            }
            relayIp = apIp

            val wifiNet = wifiNetwork()
            val hostAddr = InetAddress.getByName(hostIp)
            val hostGroupAddr = InetAddress.getByName(hostGroup)
            val guestGroupAddr = InetAddress.getByName(guestGroup)

            // senders (loopback off so the relay never hears its own forwarded packets)
            val apSender = MulticastSocket().also {
                it.timeToLive = 1
                it.setNetworkInterface(apIf)
                it.setLoopbackMode(true)
                sockets.add(it)
            }
            val stationSender = MulticastSocket().also {
                it.timeToLive = 1
                it.setNetworkInterface(stationIf)
                it.setLoopbackMode(true)
                try { wifiNet?.bindSocket(it) } catch (_: Exception) {}
                sockets.add(it)
            }
            val toHost = DatagramSocket().also {
                try { wifiNet?.bindSocket(it) } catch (_: Exception) {}
                sockets.add(it)
            }
            val toGuests = DatagramSocket().also { sockets.add(it) }

            // host -> guests: audio + polls (the session ID is read from the audio header for the join QR)
            pump("SmartCastRelayAudio", multicastReceiver(hostGroupAddr, PORT, stationIf)) { p ->
                if (p.length >= 10 && ByteBuffer.wrap(p.data, p.offset, 4).int == AUDIO_MAGIC) {
                    val sid = ByteBuffer.wrap(p.data, p.offset + 6, 4).int
                    if (sid != sessionId) {
                        sessionId = sid
                        publishInfo()
                    }
                }
                forward(apSender, p, guestGroupAddr, PORT)
            }

            // chat both ways
            pump("SmartCastRelayChatDown", multicastReceiver(hostGroupAddr, CHAT_PORT, stationIf)) { p ->
                forward(apSender, p, guestGroupAddr, CHAT_PORT)
            }
            pump("SmartCastRelayChatUp", multicastReceiver(guestGroupAddr, CHAT_PORT, apIf)) { p ->
                forward(stationSender, p, hostGroupAddr, CHAT_PORT)
            }

            // guests -> host: heartbeats and poll answers
            pump("SmartCastRelayTelemetry", udpReceiver(TELEMETRY_PORT)) { p ->
                val isVote = p.length >= 4 && ByteBuffer.wrap(p.data, p.offset, 4).int == PollCodec.VOTE_MAGIC
                if (!isVote) {
                    HeartbeatPacket.fromBytes(p.data, p.length)?.let {
                        guestSeen[it.clientId] = System.currentTimeMillis()
                    }
                }
                forward(toHost, p, hostAddr, TELEMETRY_PORT)
            }

            // hand raises: guest requests go up, host approve/deny replies go back to that guest
            pump("SmartCastRelayHand", udpReceiver(HAND_PORT)) { p ->
                val msg = HandRaisePacket.fromBytes(p.data, p.length) ?: return@pump
                if (p.address.hostAddress == hostIp) {
                    val guest = handClients[msg.clientId] ?: return@pump
                    forward(toGuests, p, guest, HAND_PORT)
                } else {
                    handClients[msg.clientId] = p.address
                    forward(toHost, p, hostAddr, HAND_PORT)
                }
            }

            // approved guest's voice
            pump("SmartCastRelayPtt", udpReceiver(PTT_PORT)) { p ->
                forward(toHost, p, hostAddr, PTT_PORT)
            }

            publishInfo()
            thread(name = "SmartCastRelayStatus", isDaemon = true) {
                while (running.get()) {
                    val now = System.currentTimeMillis()
                    val n = guestSeen.values.count { now - it < 6000 }
                    onStatus("Relay active · $n guest${if (n == 1) "" else "s"} connected")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                }
            }
            if (!running.get()) stop()
        } catch (e: Exception) {
            fail(e.message ?: "Relay setup failed")
        }
    }

    private fun publishInfo() {
        onInfo(Info(ssid, password, relayIp, sessionId))
    }

    private fun pump(name: String, sock: DatagramSocket, onPacket: (DatagramPacket) -> Unit) {
        thread(name = name, isDaemon = true) {
            val buf = ByteArray(BUF)
            try {
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    try { onPacket(p) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun forward(sock: DatagramSocket, p: DatagramPacket, to: InetAddress, port: Int) {
        sock.send(DatagramPacket(p.data, p.offset, p.length, to, port))
    }

    /** Bound to the group address itself, so it only ever sees that one group's traffic. */
    private fun multicastReceiver(group: InetAddress, port: Int, nif: NetworkInterface): MulticastSocket {
        val s = MulticastSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(group, port))
        s.setNetworkInterface(nif)
        s.joinGroup(InetSocketAddress(group, 0), nif)
        sockets.add(s)
        return s
    }

    private fun udpReceiver(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        sockets.add(s)
        return s
    }

    /** station = the interface on the host's Wi-Fi range; ap = the other Wi-Fi-type interface (the hotspot). */
    private fun findInterfaces(): Pair<Pair<NetworkInterface, Inet4Address>, Pair<NetworkInterface, Inet4Address>>? {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<Pair<NetworkInterface, Inet4Address>>()
        for (nif in all) {
            if (!nif.isUp || nif.isLoopback) continue
            val n = nif.name
            if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap"))) continue
            val addr = nif.inetAddresses.toList().firstOrNull { it is Inet4Address } as? Inet4Address ?: continue
            candidates.add(Pair(nif, addr))
        }
        val hostPrefix = hostIp.substringBeforeLast('.')
        val sameNet = candidates.filter { (it.second.hostAddress ?: "").substringBeforeLast('.') == hostPrefix }
        val station = sameNet.firstOrNull { it.first.name == "wlan0" } ?: sameNet.firstOrNull() ?: return null
        val ap = candidates.firstOrNull { it.first.name != station.first.name } ?: return null
        return Pair(station, ap)
    }

    /** Same rule as the app: 192.168.1.23 -> 239.10.1.23. */
    private fun groupForIp(ip: String?): String? {
        val parts = ip?.split(".") ?: return null
        if (parts.size != 4) return null
        val third = parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val fourth = parts[3].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        return "239.10.$third.$fourth"
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = res.softApConfiguration
            val name = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid
            val pass = c.passphrase
            if (name == null || pass == null) return null
            return Pair(name, pass)
        }
        val c = res.wifiConfiguration ?: return null
        val name = c.SSID?.trim('"') ?: return null
        val pass = c.preSharedKey?.trim('"') ?: return null
        return Pair(name, pass)
    }
}
