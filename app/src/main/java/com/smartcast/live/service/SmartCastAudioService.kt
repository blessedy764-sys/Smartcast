package com.smartcast.live.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.smartcast.live.MainActivity

/**
 * Keeps the process audio-eligible with the screen off (Android 17 background audio hardening).
 *  - Host:     microphone + mediaPlayback (mic capture, plus playback of the approved guest's voice)
 *  - Receiver: mediaPlayback
 * Start it from a visible Activity (a button tap) so it gets while-in-use capabilities.
 */
class SmartCastAudioService : Service() {
    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_ROOM = "room"
        private const val ACTION_STOP = "com.smartcast.live.action.STOP_SESSION"
        private const val CHANNEL = "smartcast"
        private const val NOTIFICATION_ID = 1

        /** Set by MainActivity so the notification's Stop button can end the session. */
        @Volatile
        var onStopRequested: (() -> Unit)? = null
    }

    private var wake: PowerManager.WakeLock? = null
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }

        val hosting = intent?.getBooleanExtra(EXTRA_HOST, false) ?: false
        val room = intent?.getStringExtra(EXTRA_ROOM) ?: "SmartCast Live"

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SmartCast", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )

        val mediaSession = buildSession(hosting, room)
        val notification = buildNotification(hosting, room, mediaSession)

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val type = if (hosting) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification) // type comes from the manifest on Android 10
            }
        } catch (e: Exception) {
            // e.g. SecurityException when RECORD_AUDIO was not granted before a microphone service
            stopSelf()
            return START_NOT_STICKY
        }

        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartcast:audio").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null
        session?.run { isActive = false; release() }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------

    private fun buildSession(hosting: Boolean, room: String): MediaSession {
        session?.release()
        val s = MediaSession(this, "SmartCastLive").apply {
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, room)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, if (hosting) "Broadcasting live" else "Listening live")
                    .build()
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                override fun onStop() {
                    onStopRequested?.invoke()
                    stopSelf()
                }
            })
            isActive = true
        }
        session = s
        return s
    }

    private fun buildNotification(hosting: Boolean, room: String, mediaSession: MediaSession): Notification {
        // Bring the existing MainActivity forward instead of starting a second copy with no session.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SmartCastAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(room)
            .setContentText(if (hosting) "Broadcasting" else "Listening")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(stopAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }
}
