package com.smartcast.live.eco

/**
 * Watches the host's own multicast sends and steps the stream down when the hotspot struggles.
 *
 * Signals (all measured on the host, nothing extra goes over the network):
 *  - send failures (ENOBUFS, "network unreachable" and similar)
 *  - slow sends (the socket blocks because the Wi-Fi queue is full)
 *
 * Levels: 0 = full quality, 1 = eco, 2 = survival (lowest bitrate, no noise suppression, slides stop repeating).
 * It steps down after ~4 s of trouble and back up one level after ~16 s of calm. If the trouble keeps
 * coming back, the calm period needed doubles (up to ~2 min) so the stream does not flap.
 */
class CongestionMonitor(private val onLevelChanged: (Int) -> Unit) {
    enum class Mode { AUTO, ECO, FULL }

    companion object {
        const val LEVEL_NORMAL = 0
        const val LEVEL_ECO = 1
        const val LEVEL_SURVIVAL = 2

        private const val WINDOW_NS = 2_000_000_000L // evaluate every 2 s (~100 audio packets)
        private const val SLOW_SEND_NS = 8_000_000L  // a multicast send over 8 ms means the queue is backing up
        private const val MIN_SENDS = 20
        private const val ESCALATE_WINDOWS = 2
        private const val RECOVER_WINDOWS_START = 8
        private const val RECOVER_WINDOWS_MAX = 64
    }

    private var mode = Mode.AUTO
    private var autoLevel = 0
    private var shownLevel = 0
    private var sends = 0
    private var failures = 0
    private var slow = 0
    private var windowStart = 0L
    private var badStreak = 0
    private var goodStreak = 0
    private var recoverNeeded = RECOVER_WINDOWS_START
    private var consecutiveFailures = 0

    /** Call after every host packet send. Cheap; runs on the audio thread. */
    @Synchronized
    fun onSend(durationNanos: Long, failed: Boolean) {
        val now = System.nanoTime()
        if (windowStart == 0L) windowStart = now
        sends++
        if (failed) {
            failures++
            consecutiveFailures++
        } else {
            consecutiveFailures = 0
            if (durationNanos > SLOW_SEND_NS) slow++
        }
        if (now - windowStart >= WINDOW_NS) {
            evaluate()
            sends = 0
            failures = 0
            slow = 0
            windowStart = now
        }
    }

    private fun evaluate() {
        if (sends < MIN_SENDS) return
        val failRate = failures.toFloat() / sends
        val slowRate = slow.toFloat() / sends
        val target = when {
            failRate >= 0.20f || slowRate >= 0.50f -> LEVEL_SURVIVAL
            failRate >= 0.05f || slowRate >= 0.15f -> LEVEL_ECO
            else -> LEVEL_NORMAL
        }
        if (target > autoLevel) {
            goodStreak = 0
            badStreak++
            if (badStreak >= ESCALATE_WINDOWS || failRate >= 0.5f) {
                if (autoLevel == LEVEL_NORMAL && recoverNeeded > 0 && shownBefore) {
                    recoverNeeded = minOf(recoverNeeded * 2, RECOVER_WINDOWS_MAX) // it came back: be more patient next time
                }
                autoLevel = target
                badStreak = 0
                shownBefore = true
            }
        } else if (target < autoLevel) {
            badStreak = 0
            goodStreak++
            if (goodStreak >= recoverNeeded) {
                autoLevel--
                goodStreak = 0
            }
        } else {
            badStreak = 0
            goodStreak = 0
        }
        publish()
    }

    private var shownBefore = false

    private fun effectiveLevel(): Int = when (mode) {
        Mode.AUTO -> autoLevel
        Mode.ECO -> maxOf(LEVEL_ECO, autoLevel)
        Mode.FULL -> LEVEL_NORMAL
    }

    private fun publish() {
        val level = effectiveLevel()
        if (level != shownLevel) {
            shownLevel = level
            onLevelChanged(level)
        }
    }

    @Synchronized
    fun setMode(newMode: Mode) {
        mode = newMode
        publish()
    }

    @Synchronized fun mode(): Mode = mode

    /** Call when a session starts or stops. Keeps the chosen mode, forgets the measurements. */
    @Synchronized
    fun reset() {
        autoLevel = 0
        sends = 0
        failures = 0
        slow = 0
        windowStart = 0L
        badStreak = 0
        goodStreak = 0
        recoverNeeded = RECOVER_WINDOWS_START
        consecutiveFailures = 0
        shownBefore = false
        shownLevel = 0
        publish()
    }

    /** Sends failed in a row (a dead socket rather than congestion): the caller should give up after a while. */
    @Synchronized fun consecutiveFailures(): Int = consecutiveFailures

    @Synchronized fun level(): Int = effectiveLevel()

    /** Highest Opus bitrate allowed right now. */
    @Synchronized
    fun bitrateCap(): Int = when (effectiveLevel()) {
        LEVEL_NORMAL -> Int.MAX_VALUE
        LEVEL_ECO -> 20_000
        else -> 12_000
    }

    /** Opus encoder complexity: lower uses less CPU. */
    @Synchronized
    fun complexity(): Int = when (effectiveLevel()) {
        LEVEL_NORMAL -> 5
        LEVEL_ECO -> 3
        else -> 2
    }

    /** RNNoise is the biggest CPU cost, so survival mode switches it off. */
    @Synchronized fun denoiseEnabled(): Boolean = effectiveLevel() < LEVEL_SURVIVAL

    /** How often a shown slide is re-sent for late joiners. */
    @Synchronized
    fun slideRepeatMs(): Long = when (effectiveLevel()) {
        LEVEL_NORMAL -> 5_000L
        LEVEL_ECO -> 15_000L
        else -> 60_000L
    }

    @Synchronized
    fun levelName(): String = when (effectiveLevel()) {
        LEVEL_NORMAL -> "full quality"
        LEVEL_ECO -> "eco"
        else -> "survival"
    }
}
