package com.gitco.smartcast.reminders

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gitco.smartcast.R

class SessionReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val title = inputData.getString("session_title") ?: "Scheduled Session"
        val minutesBefore = inputData.getInt("minutes_before", 0)

        val message = if (minutesBefore == 0) {
            "'$title' is starting right now! Tap to join."
        } else {
            "'$title' starts in $minutesBefore minutes!"
        }

        showNotification(title, message)
        return Result.success()
    }

    private fun showNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(applicationContext, "SmartCastReminders")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(applicationContext)) {
                notify(System.currentTimeMillis().toInt(), builder.build())
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
