package com.smartcast.live.telemetry

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class HeartbeatSender(
    private val hostIp: String,
    private val telemetryPort: Int,
    private val clientId: Long,
    private val getPacketLoss: () -> Byte,
    private val getJitter: () -> Short
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (job != null) return
        job = scope.launch {
            val socket = DatagramSocket()
            try {
                val address = InetAddress.getByName(hostIp)
                while (isActive) {
                    try {
                        val data = HeartbeatPacket(clientId, getPacketLoss(), getJitter()).toBytes()
                        socket.send(DatagramPacket(data, data.size, address, telemetryPort))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    delay(2000) // faster than the host's 5 s timeout
                }
            } finally {
                socket.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
