package com.smartcast.live.slides

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Turns images, PDF pages and lyric text into small WebP pictures (about 40 KB) that fit in a few UDP packets. */
object SlideCompressor {
    const val TARGET_BYTES = 40_000

    /** Tries bigger and sharper first, then shrinks until the picture fits. */
    fun compress(src: Bitmap): ByteArray {
        val plans = listOf(
            1024 to 70, 1024 to 55, 1024 to 40,
            800 to 55, 800 to 40,
            640 to 45, 640 to 30,
            480 to 30
        )
        var best: ByteArray? = null
        for ((maxSide, quality) in plans) {
            val scaled = fit(src, maxSide)
            val out = ByteArrayOutputStream()
            scaled.compress(webpFormat(), quality, out)
            scaled.recycle()
            val bytes = out.toByteArray()
            if (best == null || bytes.size < best.size) best = bytes
            if (bytes.size <= TARGET_BYTES) return bytes
        }
        return best ?: ByteArray(0)
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** Scales so the longest side is at most [maxSide], on a white background (transparent PNGs stay readable). */
    private fun fit(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        val ratio = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val w = maxOf(1, (src.width * ratio).toInt())
        val h = maxOf(1, (src.height * ratio).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    @Suppress("DEPRECATION")
    fun fromImage(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded
        val m = Matrix()
        m.postRotate(degrees)
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    fun pdfPageCount(resolver: ContentResolver, uri: Uri): Int {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: return 0
        try {
            val renderer = PdfRenderer(pfd)
            try {
                return renderer.pageCount
            } finally {
                renderer.close()
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    fun fromPdfPage(resolver: ContentResolver, uri: Uri, index: Int): Bitmap? {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
        try {
            val renderer = PdfRenderer(pfd)
            try {
                if (index < 0 || index >= renderer.pageCount) return null
                val page = renderer.openPage(index)
                try {
                    val w = 1280
                    val h = (page.height * (w.toFloat() / page.width)).toInt().coerceIn(1, 2400)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    /** Lyrics or verses as a clean 16:9 slide, with the text sized to fit. */
    fun fromText(text: String): Bitmap {
        val w = 1280
        val h = 720
        val pad = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF12345A.toInt()
        var size = 76f
        fun build(): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, w - 2 * pad)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        }
        var layout = build()
        while (layout.height > h - 2 * pad && size > 24f) {
            size -= 4f
            layout = build()
        }
        canvas.save()
        canvas.translate(pad.toFloat(), maxOf(pad.toFloat(), (h - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
        return bmp
    }
}
