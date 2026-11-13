package com.smartcast.live.qr

import android.net.Uri

data class SessionConnectionConfig(
    val hostIp: String,
    val port: Int,
    val channelId: Byte,
    val sessionName: String
) {
    companion object {
        fun parse(qrString: String): SessionConnectionConfig? {
            val uri = Uri.parse(qrString)
            if (uri.scheme != "smartcast" || uri.host != "join") return null

            val ip = uri.getQueryParameter("ip") ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 5004
            val channel = uri.getQueryParameter("ch")?.toByteOrNull() ?: 1
            val name = uri.getQueryParameter("name") ?: "Tour Session"

            return SessionConnectionConfig(ip, port, channel, name)
        }
    }
}
