package com.smartcast.live.qr

import android.net.Uri
import android.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * QR format: smartcast://join?ip=<host>&port=<port>&ch=<channel>&name=<name>&k=<room key>
 * k is the room key, base64url without padding (16 bytes or more).
 */
data class SessionConnectionConfig(
    val hostIp: String,
    val port: Int,
    val channelId: Byte,
    val sessionName: String,
    val roomKey: SecretKey
) {
    companion object {
        private const val MIN_KEY_BYTES = 16

        fun parse(qrString: String): SessionConnectionConfig? {
            val uri = Uri.parse(qrString)
            if (uri.scheme != "smartcast" || uri.host != "join") return null

            val ip = uri.getQueryParameter("ip") ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 5004
            val channel = uri.getQueryParameter("ch")?.toByteOrNull() ?: 1
            val name = uri.getQueryParameter("name") ?: "Tour Session"

            val encodedKey = uri.getQueryParameter("k") ?: return null
            val keyBytes = try {
                Base64.decode(encodedKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (keyBytes.size < MIN_KEY_BYTES) return null

            return SessionConnectionConfig(ip, port, channel, name, SecretKeySpec(keyBytes, "AES"))
        }
    }
}
