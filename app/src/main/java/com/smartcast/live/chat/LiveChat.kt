package com.smartcast.live.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.smartcast.live.security.RoomSecurity
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/** One chat line or reaction. */
data class ChatMessage(
    val senderId: Long,
    val isHost: Boolean,
    val isReaction: Boolean,
    val text: String
)

/**
 * Wire format: [magic 4][version 1][flags 1][senderId 8]([iv 12] if encrypted)[body].
 * PIN rooms encrypt the body with the room key, open rooms send plain UTF-8.
 */
object ChatCodec {
    private const val MAGIC = 0x53434348 // "SCCH"
    private const val VERSION: Byte = 1
    private const val FLAG_ENCRYPTED = 1
    private const val FLAG_HOST = 2
    private const val FLAG_REACTION = 4
    private const val HEADER = 14
    private const val IV_SIZE = 12
    const val MAX_CHARS = 200
    private val random = SecureRandom()

    fun encode(msg: ChatMessage, key: SecretKeySpec?): ByteArray {
        val clear = msg.text.take(MAX_CHARS).toByteArray(Charsets.UTF_8)
        var flags = 0
        if (key != null) flags = flags or FLAG_ENCRYPTED
        if (msg.isHost) flags = flags or FLAG_HOST
        if (msg.isReaction) flags = flags or FLAG_REACTION
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val body = if (key != null) RoomSecurity.encrypt(clear, key, iv) else clear
        val buf = ByteBuffer.allocate(HEADER + (if (key != null) IV_SIZE else 0) + body.size)
        buf.putInt(MAGIC)
        buf.put(VERSION)
        buf.put(flags.toByte())
        buf.putLong(msg.senderId)
        if (key != null) buf.put(iv)
        buf.put(body)
        return buf.array()
    }

    fun decode(data: ByteArray, length: Int, key: SecretKeySpec?): ChatMessage? {
        if (length < HEADER + 1) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        if (buf.int != MAGIC) return null
        if (buf.get() != VERSION) return null
        val flags = buf.get().toInt()
        val senderId = buf.long
        val encrypted = (flags and FLAG_ENCRYPTED) != 0
        if (encrypted != (key != null)) return null // PIN mismatch: ignore
        val body: ByteArray = if (key != null) {
            if (length < HEADER + IV_SIZE + 1) return null
            val iv = ByteArray(IV_SIZE)
            buf.get(iv)
            val raw = ByteArray(length - HEADER - IV_SIZE)
            buf.get(raw)
            RoomSecurity.decrypt(raw, key, iv) ?: return null
        } else {
            val raw = ByteArray(length - HEADER)
            buf.get(raw)
            raw
        }
        val text = String(body, Charsets.UTF_8).take(MAX_CHARS)
        if (text.isBlank()) return null
        return ChatMessage(
            senderId = senderId,
            isHost = (flags and FLAG_HOST) != 0,
            isReaction = (flags and FLAG_REACTION) != 0,
            text = text
        )
    }
}

/**
 * Live-stream style chat: messages rise from the bottom, the list jumps to the newest
 * message instantly, older messages fade (alpha 1.0 down to 0.35), and each message sits
 * in a dark translucent card with a coloured sender name.
 */
class LiveChatView(context: Context) : LinearLayout(context) {
    companion object {
        private const val EMERALD = 0xFF10B981.toInt() // listeners
        private const val AMBER = 0xFFF59E0B.toInt()   // HOST
        private const val MAX_MESSAGES = 60
        private const val MIN_ALPHA = 0.35f
        private const val FADE_STEP = 0.09f            // alpha lost per message of age
        private val REACTIONS = listOf("❤️", "👏", "🔥", "😂", "🙏")
    }

    /** Called with (text, isReaction) when the user sends something. */
    var onSend: ((String, Boolean) -> Unit)? = null

    private val list = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM // new messages stack up from the bottom
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        background = GradientDrawable().apply {
            setColor(0xFF273449.toInt())
            cornerRadius = dp(16f).toFloat()
        }
        // let the chat scroll inside the page's own ScrollView
        setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val input = EditText(context).apply {
        hint = "Say something…"
        isSingleLine = true
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
    }

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260f)))

        // jump to the newest message instantly whenever the list changes size
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scroll.scrollTo(0, list.height) }

        // reaction buttons
        val reactionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
        }
        for (emoji in REACTIONS) {
            reactionRow.addView(Button(context).apply {
                text = emoji
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { onSend?.invoke(emoji, true) }
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        addView(reactionRow)

        // text input + send
        val inputRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        input.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        inputRow.addView(input)
        inputRow.addView(Button(context).apply {
            text = "Send"
            isAllCaps = false
            setOnClickListener { submit() }
        })
        addView(inputRow)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
    }

    private fun submit() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.setText("")
        onSend?.invoke(message, false)
    }

    /** Must be called on the main thread. */
    fun addMessage(name: String, isHost: Boolean, message: String, isReaction: Boolean) {
        val nameColor = if (isHost) AMBER else EMERALD
        val sb = SpannableStringBuilder()
        sb.append(name)
        sb.setSpan(ForegroundColorSpan(nameColor), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ")
        val start = sb.length
        sb.append(message)
        if (isReaction) sb.setSpan(RelativeSizeSpan(1.7f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val bubble = TextView(context).apply {
            text = sb
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            background = GradientDrawable().apply {
                setColor(0xCC0B1220.toInt()) // dark translucent card
                cornerRadius = dp(14f).toFloat()
            }
        }
        list.addView(bubble, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
            gravity = Gravity.START
        })
        while (list.childCount > MAX_MESSAGES) list.removeViewAt(0)
        applyFade()
    }

    fun clear() {
        list.removeAllViews()
    }

    /** Newest message is fully opaque; each older one is a bit fainter, down to 0.35. */
    private fun applyFade() {
        val count = list.childCount
        for (i in 0 until count) {
            val age = count - 1 - i
            list.getChildAt(i).alpha = (1f - age * FADE_STEP).coerceIn(MIN_ALPHA, 1f)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
