package com.smartcast.live.slides

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
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
 * Live slides, lyrics and PDF/image push.
 *
 * - [hostPanel]: host controls (pick image/PDF, paste lyrics, Prev / Next, hide).
 * - [slideBox]: the live slide card. Receivers see it update by itself right above the chat feed.
 *   The host sees the same card as an "on air" preview and can swipe it to turn pages.
 *
 * Every page turn compresses the page to WebP (about 40 KB) and pushes it through [SlideHost].
 */
class SlideController(
    private val activity: Activity,
    send: (ByteArray) -> Unit,
    private val isHost: () -> Boolean,
    private val isRunning: () -> Boolean,
    private val hostHasPin: () -> Boolean,
    private val crypto: () -> Crypto?
) {
    /** PIN rooms: [encrypt] / [decrypt] take the slide bytes and the slide ID (used for the nonce). */
    class Crypto(
        val encrypt: (ByteArray, Int) -> ByteArray,
        val decrypt: (ByteArray, Int) -> ByteArray?
    )

    companion object {
        const val IV_SEQ = 0x534C4944 // fixed "sequence" half of the GCM nonce; the slide ID is the other half
        private const val REQUEST_PICK = 300
        private const val MAX_PAGES = 300
    }

    private sealed class Source(val unit: String, val count: Int) {
        class Pdf(val uri: Uri, count: Int) : Source("Page", count)
        class Images(val uris: List<Uri>) : Source("Slide", uris.size)
        class Lyrics(val verses: List<String>) : Source("Verse", verses.size)
    }

    // ---------- state ----------
    private val host = SlideHost(send)
    private val receiver = SlideReceiver(
        onSlide = { onSlideReceived(it) },
        onClear = { onClearReceived() }
    )
    private val renderExec = Executors.newSingleThreadExecutor { r -> Thread(r, "SmartCastSlideRender").apply { isDaemon = true } }
    private val decodeExec = Executors.newSingleThreadExecutor { r -> Thread(r, "SmartCastSlideDecode").apply { isDaemon = true } }
    private val renderTicket = AtomicInteger(0)
    private val decodeTicket = AtomicInteger(0)
    @Volatile private var source: Source? = null
    private var pageIndex = 0
    private var currentBitmap: Bitmap? = null
    private var fullDialog: Dialog? = null
    private var fullImage: ImageView? = null

    // ---------- views ----------
    private val slideCaption = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFF536579.toInt())
        setPadding(0, 0, 0, 8)
    }

    private val slideImage = ImageView(activity).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        maxHeight = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()
        setOnTouchListenerCompat(this)
    }

    /** Live slide card. Add it right above the chat feed. */
    val slideBox: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(24, 20, 24, 20)
        background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = 24f
            setStroke(3, 0xFF12345A.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 }
        addView(slideCaption)
        addView(
            slideImage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private val pageLabel = TextView(activity).apply {
        text = "No slides loaded"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(0xFF12345A.toInt())
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
    }

    private val lyricsInput = EditText(activity).apply {
        hint = "Paste lyrics here. Leave a blank line between verses."
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 4
        gravity = Gravity.TOP or Gravity.START
    }

    /** Host controls. Add it to the page (it is only useful while broadcasting). */
    val hostPanel: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            text = "LIVE SLIDES, LYRICS & PDF (HOST)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(0xFF12345A.toInt())
            setPadding(0, 24, 0, 8)
        })
        addView(TextView(activity).apply {
            text = "Pick images or a PDF (export PowerPoint decks to PDF), or paste lyrics. Every page turn is pushed to all receivers, above the chat. No internet needed."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF64748B.toInt())
        })
        addView(btn("Pick image / PDF / deck") { pickFile() })
        addView(lyricsInput)
        addView(btn("Send lyrics") { sendLyrics() })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(activity).apply {
                text = "◀ Prev"
                isAllCaps = false
                setOnClickListener { step(-1) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pageLabel)
            addView(Button(activity).apply {
                text = "Next ▶"
                isAllCaps = false
                setOnClickListener { step(1) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        addView(btn("Hide slide from receivers") { hideSlide() })
    }

    // ---------- public API used by MainActivity ----------

    /** Feed every datagram from the audio port here. Returns true if it was a slide packet. */
    fun onPacket(data: ByteArray, offset: Int, length: Int): Boolean = receiver.onPacket(data, offset, length)

    /** Returns true if the result belonged to the slide picker. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK) return false
        if (resultCode == Activity.RESULT_OK && data != null) handlePicked(data)
        return true
    }

    /** Receiver: forget any slide when joining a room. */
    fun resetReceiver() {
        receiver.reset()
        decodeTicket.incrementAndGet()
        ui { hideBox() }
    }

    /** Eco mode stretches the repeat interval so slides use less bandwidth. */
    fun setRepeatInterval(ms: Number) {
        host.repeatMs = ms.toLong().coerceIn(1000L, 60_000L)
    }

    /** Session ended: tell receivers to hide the slide (if hosting) and reset everything. */
    fun stop() {
        renderTicket.incrementAndGet()
        decodeTicket.incrementAndGet()
        if (isHost() && isRunning()) host.clear() else host.stop()
        source = null
        pageIndex = 0
        receiver.reset()
        ui {
            hideBox()
            pageLabel.text = "No slides loaded"
        }
    }

    // ---------- host: picking and sending ----------

    private fun canBroadcast(): Boolean {
        if (!isRunning() || !isHost()) {
            toast("Start broadcasting first.")
            return false
        }
        return true
    }

    private fun pickFile() {
        if (!canBroadcast()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            activity.startActivityForResult(intent, REQUEST_PICK)
        } catch (_: Exception) {
            toast("No file picker available.")
        }
    }

    private fun handlePicked(data: Intent) {
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        toast("Opening…")
        renderExec.execute {
            val resolver = activity.contentResolver
            val picked: Source? = try {
                val pdf = uris.firstOrNull { resolver.getType(it) == "application/pdf" }
                if (pdf != null) {
                    val n = SlideCompressor.pdfPageCount(resolver, pdf).coerceAtMost(MAX_PAGES)
                    if (n > 0) Source.Pdf(pdf, n) else null
                } else {
                    Source.Images(uris.take(MAX_PAGES))
                }
            } catch (_: Throwable) {
                null
            }
            ui {
                if (picked == null) {
                    toast("Could not open that file.")
                } else {
                    source = picked
                    pageIndex = 0
                    pushPage(0)
                }
            }
        }
    }

    private fun sendLyrics() {
        if (!canBroadcast()) return
        val verses = lyricsInput.text.toString().trim()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_PAGES)
        if (verses.isEmpty()) {
            toast("Paste some lyrics first.")
            return
        }
        source = Source.Lyrics(verses)
        pageIndex = 0
        pushPage(0)
    }

    private fun step(delta: Int) {
        if (!canBroadcast()) return
        val src = source
        if (src == null) {
            toast("Pick slides or send lyrics first.")
            return
        }
        val next = (pageIndex + delta).coerceIn(0, src.count - 1)
        if (next == pageIndex && currentBitmap != null) {
            toast(if (delta > 0) "That is the last one." else "That is the first one.")
            return
        }
        pageIndex = next
        pushPage(next)
    }

    private fun hideSlide() {
        if (!canBroadcast()) return
        renderTicket.incrementAndGet()
        host.clear()
        hideBox()
        pageLabel.text = if (source == null) "No slides loaded" else "Hidden. Tap Next or Prev to show."
    }

    private fun render(src: Source, index: Int): Bitmap? {
        val resolver = activity.contentResolver
        return when (src) {
            is Source.Pdf -> SlideCompressor.fromPdfPage(resolver, src.uri, index)
            is Source.Images -> SlideCompressor.fromImage(resolver, src.uris[index])
            is Source.Lyrics -> SlideCompressor.fromText(src.verses[index])
        }
    }

    /** Renders one page off the UI thread, compresses it and pushes it to every receiver. */
    private fun pushPage(index: Int) {
        val src = source ?: return
        if (!canBroadcast()) return
        if (hostHasPin() && crypto() == null) {
            toast("Room is still starting. Try again in a second.")
            return
        }
        val ticket = renderTicket.incrementAndGet() // a newer tap cancels older, still-queued pages
        pageLabel.text = "${src.unit} ${index + 1} / ${src.count} …"
        renderExec.execute {
            if (ticket != renderTicket.get()) return@execute
            val bmp = try { render(src, index) } catch (_: Throwable) { null }
            if (ticket != renderTicket.get()) return@execute
            if (bmp == null) {
                ui { toast("Could not open that page.") }
                return@execute
            }
            val sent = try { host.show(bmp, index, src.count, crypto()?.encrypt) } catch (_: Exception) { false }
            ui {
                if (ticket != renderTicket.get()) return@ui
                if (sent) showHostPreview(bmp, index, src) else toast("That page is too detailed to send. Try a simpler one.")
            }
        }
    }

    private fun showHostPreview(bmp: Bitmap, index: Int, src: Source) {
        setBitmap(bmp)
        slideCaption.text = "On air: ${src.unit.lowercase()} ${index + 1} of ${src.count}. Swipe the slide or use Prev / Next."
        pageLabel.text = "${src.unit} ${index + 1} / ${src.count}"
        slideBox.visibility = View.VISIBLE
    }

    // ---------- receiver ----------

    private fun onSlideReceived(c: SlideCodec.Completed) {
        if (isHost()) return
        val ticket = decodeTicket.incrementAndGet()
        decodeExec.execute {
            val bytes: ByteArray? = if (c.encrypted) {
                val cr = crypto()
                if (cr == null) {
                    ui { showNotice("🔒 The host's slides are locked. Enter the room PIN and rejoin.") }
                    return@execute
                }
                cr.decrypt(c.data, c.slideId)
            } else {
                c.data
            }
            if (bytes == null) {
                ui { showNotice("Wrong PIN, so the slide cannot be opened.") }
                return@execute
            }
            val bmp = (try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null }) ?: return@execute
            if (ticket != decodeTicket.get()) return@execute
            ui {
                if (ticket != decodeTicket.get() || isHost()) return@ui
                setBitmap(bmp)
                slideCaption.text = "📑 Page ${c.page + 1} of ${c.totalPages}. Tap to enlarge."
                slideBox.visibility = View.VISIBLE
            }
        }
    }

    private fun onClearReceived() {
        if (isHost()) return
        decodeTicket.incrementAndGet()
        ui { hideBox() }
    }

    // ---------- shared view helpers ----------

    private fun setBitmap(bmp: Bitmap) {
        currentBitmap = bmp // not recycled on purpose: the GC frees it, so a dialog can never draw a recycled bitmap
        slideImage.visibility = View.VISIBLE
        slideImage.setImageBitmap(bmp)
        fullImage?.setImageBitmap(bmp) // full-screen view follows the page turns too
    }

    private fun showNotice(text: String) {
        slideImage.visibility = View.GONE
        slideCaption.text = text
        slideBox.visibility = View.VISIBLE
    }

    private fun hideBox() {
        fullDialog?.dismiss()
        currentBitmap = null
        slideBox.visibility = View.GONE
    }

    private fun openFullscreen() {
        val bmp = currentBitmap ?: return
        if (fullDialog?.isShowing == true) return
        val iv = ImageView(activity).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { fullDialog?.dismiss() }
        }
        val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(iv)
        d.setOnDismissListener {
            fullDialog = null
            fullImage = null
        }
        fullImage = iv
        fullDialog = d
        d.show()
    }

    /** Tap = full screen. On the host, a horizontal swipe turns the page. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setOnTouchListenerCompat(view: ImageView) {
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                openFullscreen()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!isHost() || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 120 && abs(velocityX) > 400 && abs(dx) > abs(dy)) {
                    step(if (dx < 0) 1 else -1) // swipe left = next
                    return true
                }
                return false
            }
        })
        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun btn(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread { block() }

    private fun toast(message: String) = ui { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
}
