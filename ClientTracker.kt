package com.gitco.smartcast.telemetry

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

data class ClientInfo(
    val clientId: Long,
    var lastSeenTimestamp: Long,
    var packetLossPercent: Int,
    var jitterMs: Int
)

class ClientTracker(private val onClientListUpdated: (List<ClientInfo>) -> Unit) {
    private val activeClients = ConcurrentHashMap<Long, ClientInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // Remove clients who haven't sent a heartbeat in 10 seconds
            let removed = activeClients.entries.removeIf { (_, client) ->
                now - client.lastSeenTimestamp > 10000
            }
            if (removed) {
                onClientListUpdated(activeClients.values.toList())
            }
            handler.postDelayed(this, 3000) // Check every 3 seconds
        }
    }

    fun startTracking() {
        handler.post(cleanupRunnable)
    }

    fun stopTracking() {
        handler.removeCallbacks(cleanupRunnable)
        activeClients.clear()
    }

    fun recordHeartbeat(packet: HeartbeatPacket) {
        val now = System.currentTimeMillis()
        val client = activeClients.getOrPut(packet.clientId) {
            ClientInfo(packet.clientId, now, packet.packetLossPercent.toInt(), packet.jitterMs.toInt())
        }
        client.lastSeenTimestamp = now
        client.packetLossPercent = packet.packetLossPercent.toInt()
        client.jitterMs = packet.jitterMs.toInt()

        onClientListUpdated(activeClients.values.toList())
    }

    fun getConnectedCount(): Int = activeClients.size
}
