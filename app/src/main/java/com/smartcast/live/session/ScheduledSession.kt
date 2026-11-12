package com.smartcast.live.session

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionStatus {
    SCHEDULED,
    STARTING_SOON,
    LIVE,
    PAUSED,
    COMPLETED,
    CANCELLED
}

enum class SessionCategory {
    MEETING, DISCUSSION, MUSIC, TEACHING, ANNOUNCEMENT, CUSTOM
}

enum class RecurrenceRule {
    NONE, DAILY, EVERY_SUNDAY, WEEKDAYS, WEEKENDS, MONTHLY
}

@Entity(tableName = "scheduled_sessions")
data class ScheduledSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val description: String,
    val startTimeMillis: Long,       // Epoch timestamp of when it starts
    val durationMinutes: Int,        // 30, 60, 120, etc.
    val coverImagePath: String?,     // Local path or URI for cover image
    val category: SessionCategory,
    val isPublic: Boolean,           // Public or Private
    var status: SessionStatus = SessionStatus.SCHEDULED,
    val recurrenceRule: RecurrenceRule = RecurrenceRule.NONE,
    val reminderTriggers: List<Int> = listOf(15, 5, 0), // Minutes before session to notify
    val autoStart: Boolean = false   // Automatically go live when countdown reaches zero
)
