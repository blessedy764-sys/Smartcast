package com.gitco.smartcast.telemetry

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class HeartbeatSender(
    private val hostIp: String,
    private val telemetryPort: Int,
    private val getPacketLoss: () -> Byte,
    private val getJitter: () -> Short
) {
    private val clientId: Long = UUID.randomUUID().mostSignificantBits
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        job = scope.launch {
            val socket = DatagramSocket()
            val address = InetAddress.getByName(hostIp)
            while (isActive) {
                try {
                    val heartbeat = HeartbeatPacket(clientId, getPacketLoss(), getJitter())
                    val data = heartbeat.toBytes()
                    val packet = DatagramPacket(data, data.size, address, telemetryPort)
                    socket.send(packet)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(4000) // Send every 4 seconds
            }
            socket.close()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
