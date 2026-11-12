package com.smartcast.live.qr

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import javax.crypto.SecretKey

object QrCodeGenerator {
    fun generateSessionUriQr(
        ip: String,
        port: Int,
        channel: Int,
        sessionName: String,
        roomKey: SecretKey,
        width: Int = 512,
        height: Int = 512
    ): Bitmap {
        val keyBytes = requireNotNull(roomKey.encoded) { "Room key must be exportable" }
        val encodedKey = Base64.encodeToString(
            keyBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        // Custom URI schema payload
        val uriPayload = "smartcast://join?ip=$ip&port=$port&ch=$channel" +
            "&name=${java.net.URLEncoder.encode(sessionName, "UTF-8")}&k=$encodedKey"

        val bitMatrix = MultiFormatWriter().encode(uriPayload, BarcodeFormat.QR_CODE, width, height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
