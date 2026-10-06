package app.olauncher.helper

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/*
How long the phone has been in use (screen on and unlocked) between [start] and [end],
whatever was on screen, including the launcher itself.
Built from the system's screen and lock screen events.
*/

// Look back before [start] so a session already running at that point is counted from [start]
private const val LOOK_BACK_MS = 12 * 60 * 60 * 1000L

@RequiresApi(Build.VERSION_CODES.P)
fun unlockedScreenTime(context: Context, start: Long, end: Long): Long {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val events = usageStatsManager.queryEvents(start - LOOK_BACK_MS, end) ?: return 0L

    var screenOn = false
    var locked = true
    var inUseSince: Long? = null
    var total = 0L

    fun update(timestamp: Long) {
        val inUse = screenOn && !locked
        val since = inUseSince
        if (inUse && since == null) inUseSince = timestamp
        if (!inUse && since != null) {
            total += (minOf(timestamp, end) - maxOf(since, start)).coerceAtLeast(0L)
            inUseSince = null
        }
    }

    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOn = false
            UsageEvents.Event.KEYGUARD_SHOWN -> locked = true
            UsageEvents.Event.KEYGUARD_HIDDEN -> locked = false
            else -> continue
        }
        update(event.timeStamp)
    }

    // Still in use now
    inUseSince?.let { total += (end - maxOf(it, start)).coerceAtLeast(0L) }
    return total
}
