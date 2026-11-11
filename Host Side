package com.gitco.smartcast.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

object QrCodeGenerator {
    fun generateSessionUriQr(ip: String, port: Int, channel: Int, sessionName: String, width: Int = 512, height: Int = 512): Bitmap {
        // Custom URI schema payload
        val uriPayload = "smartcast://join?ip=$ip&port=$port&ch=$channel&name=${java.net.URLEncoder.encode(sessionName, "UTF-8")}"
        
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
