package com.smartcast.live.session

import android.os.CountDownTimer
import java.util.Locale
import java.util.concurrent.TimeUnit

class SessionCountdownManager(
    private val session: ScheduledSession,
    private val onTick: (String, Long) -> Unit,
    private val onStatusChanged: (SessionStatus) -> Unit,
    private val onTimeZeroReached: () -> Unit
) {
    private var timer: CountDownTimer? = null

    fun start() {
        val diff = session.startTimeMillis - System.currentTimeMillis()
        if (diff <= 0) {
            onTimeZeroReached()
            return
        }
        timer = createTimer(diff)
        timer?.start()
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    private fun createTimer(duration: Long): CountDownTimer {
        return object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                handleTick(millisUntilFinished)
            }

            override fun onFinish() {
                handleFinish()
            }
        }
    }

    private fun handleTick(remaining: Long) {
        val soonWindow = TimeUnit.MINUTES.toMillis(5)
        if (remaining <= soonWindow && session.status == SessionStatus.SCHEDULED) {
            session.status = SessionStatus.STARTING_SOON
            onStatusChanged(SessionStatus.STARTING_SOON)
        }
        onTick(formatRemaining(remaining), remaining)
    }

    private fun handleFinish() {
        session.status = SessionStatus.LIVE
        onStatusChanged(SessionStatus.LIVE)
        onTimeZeroReached()
    }

    private fun formatRemaining(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.US, "%02d : %02d : %02d", hours, minutes, seconds)
    }
}
