package com.smartcast.live.slides

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Live slides for SmartCast: the host picks an image, PDF or lyrics, every page turn is compressed to
 * a small WebP and pushed to all receivers, which show it right above the chat feed.
 *
 * MainActivity only supplies the room's send function, its state, and the PIN-room crypto.
 */
class SlideController(
    private val activity: Activity,
    private val send: (ByteArray) -> Unit,
    private val isHost: () -> Boolean,
    private val isRunning: () -> Boolean,
    private val hostHasPin: () -> Boolean,
    private val crypto: () -> Crypto?
) {
    /** PIN-room helpers. The Int is the slide ID (see IV_SEQ). Null crypto means an open room. */
    class Crypto(
        val encrypt: (ByteArray, Int) -> ByteArray,
        val decrypt: (ByteArray, Int) -> ByteArray?
    )

    companion object {
        const val REQUEST_PICK = 210

        /** Use as the second argument of MainActivity.ivFor(slideId, IV_SEQ). Audio sequences never reach -1. */
        const val IV_SEQ = -1
    }

    private class Deck(val uris: List<Uri>, val pdf: Boolean, val verses: List<String>, val total: Int)

    @Volatile private var deck: Deck? = null
    @Volatile private var page = 0
    private val token = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SmartCastSlides").apply { isDaemon = true }
    }
    private val hostSender = SlideHost(send)
    private val receiver = SlideReceiver(
        onSlide = { done -> worker.execute { showDecoded(done) } },
        onClear = { activity.runOnUiThread { hideSlide() } }
    )

    // ---- views ----

    /** Shown to everyone, add it right above the chat view. */
    val slideBox = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, 8, 0, 8)
    }
    private val slideLabel = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFF536579.toInt())
    }
    private val slideImage = ImageView(activity).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFFFFFFFF.toInt())
    }
    private val lyricsInput = EditText(activity).apply {
        hint = "Lyrics or verses (blank line between verses)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        maxLines = 8
    }

    /** Host controls, add it to the page (for example after the soundboard). */
    val hostPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        slideBox.addView(slideLabel)
        slideBox.addView(slideImage)

        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!isHost() || deck == null || abs(vx) < abs(vy)) return false
                if (vx < 0) next() else prev()
                return true
            }
        })
        slideImage.setOnTouchListener { v, e ->
            val handled = detector.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            handled
        }

        hostPanel.addView(TextView(activity).apply {
            text = "LIVE SLIDES (HOST)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(0xFF12345A.toInt())
            setPadding(0, 24, 0, 8)
        })
        hostPanel.addView(button("Choose image / PDF / slides") { pick() })
        hostPanel.addView(lyricsInput)
        hostPanel.addView(button("Send lyrics") { sendLyrics() })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(weighted("◀ Previous") { prev() })
        row.addView(weighted("Next ▶") { next() })
        row.addView(weighted("Clear") { clearSlide() })
        hostPanel.addView(row)
        hostPanel.addView(TextView(activity).apply {
            text = "Receivers see each slide above the chat. Swipe the slide left or right, or use the buttons, to turn pages. Slides are shrunk to about 40 KB so they send fast."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF64748B.toInt())
        })
    }

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun weighted(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun toast(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    }

    // ---- host: choosing and turning pages ----

    private fun pick() {
        if (!isRunning() || !isHost()) { toast("Start broadcasting first."); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_PICK)
        } catch (_: Exception) {
            toast("No file picker available.")
        }
    }

    /** Call first thing in MainActivity.onActivityResult. Returns true if it handled the result. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK) return false
        if (resultCode == Activity.RESULT_OK && data != null) loadPicked(data)
        return true
    }

    private fun loadPicked(data: Intent) {
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        worker.execute {
            val resolver = activity.contentResolver
            val first = uris[0]
            val isPdf = uris.size == 1 &&
                (resolver.getType(first) == "application/pdf" || first.toString().endsWith(".pdf", true))
            val newDeck = try {
                if (isPdf) {
                    val n = SlideCompressor.pdfPageCount(resolver, first)
                    if (n > 0) Deck(uris, true, emptyList(), n) else null
                } else {
                    Deck(uris, false, emptyList(), uris.size)
                }
            } catch (_: Exception) {
                null
            }
            if (newDeck == null) { toast("Could not open that file."); return@execute }
            deck = newDeck
            show(0)
        }
    }

    private fun sendLyrics() {
        if (!isRunning() || !isHost()) { toast("Start broadcasting first."); return }
        val verses = lyricsInput.text.toString().trim()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(200)
        if (verses.isEmpty()) { toast("Type or paste some lyrics first."); return }
        deck = Deck(emptyList(), false, verses, verses.size)
        show(0)
    }

    private fun next() = show(page + 1)
    private fun prev() = show(page - 1)

    private fun render(d: Deck, index: Int): Bitmap? = try {
        val resolver = activity.contentResolver
        when {
            d.pdf -> SlideCompressor.fromPdfPage(resolver, d.uris[0], index)
            d.verses.isNotEmpty() -> SlideCompressor.fromText(d.verses[index])
            else -> SlideCompressor.fromImage(resolver, d.uris[index])
        }
    } catch (_: Exception) {
        null
    }

    /** Renders, compresses and broadcasts one page. Fast swipes skip the pages in between. */
    private fun show(index: Int) {
        val d = deck ?: return
        if (!isRunning() || !isHost()) { toast("Start broadcasting first."); return }
        val i = index.coerceIn(0, d.total - 1)
        page = i
        val mine = token.incrementAndGet()
        worker.execute {
            if (mine != token.get()) return@execute
            val bmp = render(d, i)
            if (bmp == null) { toast("Could not read that page."); return@execute }
            if (mine != token.get()) return@execute
            val c = crypto()
            if (hostHasPin() && c == null) { toast("Room is still starting. Try again in a second."); return@execute }
            val ok = hostSender.show(bmp, i, d.total, c?.encrypt)
            activity.runOnUiThread {
                if (ok && mine == token.get()) showImage(bmp, "Slide ${i + 1} / ${d.total} (you are showing this)")
                else if (!ok) toast("That page is too detailed to send.")
            }
        }
    }

    private fun clearSlide() {
        token.incrementAndGet()
        deck = null
        hostSender.clear()
        hideSlide()
    }

    // ---- receiver ----

    /** Call from the receive loop with every datagram. Returns true if it was a slide packet. */
    fun onPacket(data: ByteArray, offset: Int, length: Int): Boolean = receiver.onPacket(data, offset, length)

    /** Call when a new receiver session starts. */
    fun resetReceiver() = receiver.reset()

    private fun showDecoded(done: SlideCodec.Completed) {
        if (isHost()) return
        val c = crypto()
        if (done.encrypted != (c != null)) return // PIN mismatch: not for us
        val bytes = if (done.encrypted) (c!!.decrypt(done.data, done.slideId) ?: return) else done.data
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        activity.runOnUiThread {
            if (isRunning() && !isHost()) showImage(bmp, "Slide ${done.page + 1} / ${done.totalPages}")
        }
    }

    // ---- shared ----

    private fun showImage(bmp: Bitmap, label: String) {
        slideImage.setImageBitmap(bmp)
        slideLabel.text = label
        slideBox.visibility = View.VISIBLE
    }

    private fun hideSlide() {
        slideImage.setImageDrawable(null)
        slideBox.visibility = View.GONE
    }

    /** Call first thing in MainActivity.stopSession (before sockets close, so receivers get a clear). */
    fun stop() {
        token.incrementAndGet()
        if (isHost() && deck != null) hostSender.clear() else hostSender.stop()
        deck = null
        receiver.reset()
        activity.runOnUiThread { hideSlide() }
    }
}
