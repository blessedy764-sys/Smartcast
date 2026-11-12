package com.gitco.smartcast.sessions

import android.os.CountDownTimer
import java.util.concurrent.TimeUnit

class SessionCountdownManager(
    private val session: ScheduledSession,
    private val onTick: (remainingFormatted: String, millisRemaining: Long) -> Unit,
    private val onStatusChanged: (SessionStatus) -> Unit,
    private val onTimeZeroReached: () -> Unit
) {
    private var timer: CountDownTimer? = null

    fun start() {
        val now = System.currentTimeMillis()
        val diff = session.startTimeMillis - now
        if (diff <= 0) {
            onTimeZeroReached()
            return
        }

        timer = object : CountDownTimer(diff, 1000) {
            override fun millisUntilFinished(millisUntilFinished: Long) {
                // Check if we entered "Starting Soon" window (< 5 minutes)
                if (millisUntilFinished <= TimeUnit.MINUTES.toMillis(5) && session.status == SessionStatus.SCHEDULED) {
                    session.status = SessionStatus.STARTING_SOON
                    onStatusChanged(SessionStatus.STARTING_SOON)
                }

                // Format countdown: HH : MM : SS
                val hours = TimeUnit.MILLISECONDS.toHours(millisUntilFinished)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished) % 60
                val seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60
                val formatted = String.format("%02d : %02d : %02d", hours, minutes, seconds)

                onTick(formatted, millisUntilFinished)
            }

            override fun onFinish() {
                session.status = SessionStatus.LIVE
                onStatusChanged(SessionStatus.LIVE)
                onTimeZeroReached()
            }
        }.start()
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }
}
