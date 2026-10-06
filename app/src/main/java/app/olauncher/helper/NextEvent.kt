package app.olauncher.helper

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
The next calendar event, shown on home under the date:
  "10:30 standup", "standup in 25m" within the hour, "standup now" while it's on,
  and "tue 9:00 standup" for tomorrow. Looks 24 hours ahead and skips all-day,
  cancelled and declined events.
*/

data class NextEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
)

private const val LOOK_AHEAD_MS = 24 * 60 * 60 * 1000L
private const val MINUTE_MS = 60 * 1000L

fun Context.canReadCalendar() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

fun queryNextEvent(context: Context, now: Long = System.currentTimeMillis()): NextEvent? {
    if (!context.canReadCalendar()) return null
    val uri = Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now)
        ContentUris.appendId(it, now + LOOK_AHEAD_MS)
    }.build()
    val selection = "${Instances.ALL_DAY} = 0" +
            " AND ${Instances.VISIBLE} = 1" +
            " AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})" +
            " AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}" +
            " AND ${Instances.END} > ?"
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END),
            selection,
            arrayOf(now.toString()),
            "${Instances.BEGIN} ASC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            NextEvent(
                eventId = cursor.getLong(0),
                title = cursor.getString(1).orEmpty().trim(),
                begin = cursor.getLong(2),
                end = cursor.getLong(3),
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun formatNextEvent(context: Context, event: NextEvent, now: Long = System.currentTimeMillis()): String {
    val title = event.title.ifEmpty { "busy" }.lowercase()
    val untilStart = event.begin - now
    if (untilStart <= 0) return "$title now"
    if (untilStart < 60 * MINUTE_MS) return "$title in ${(untilStart + MINUTE_MS - 1) / MINUTE_MS}m"

    val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    var time = DateFormat.format(pattern, event.begin).toString()
    if (!isSameDay(now, event.begin))
        time = DateFormat.format("EEE", Date(event.begin)).toString().lowercase(Locale.getDefault()) + " " + time
    return "$time $title"
}

fun openCalendarEvent(context: Context, event: NextEvent) {
    try {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        openCalendar(context)
    }
}

// Milliseconds until the next whole minute, so relative times stay current
fun millisToNextMinute(now: Long = System.currentTimeMillis()) = MINUTE_MS - now % MINUTE_MS

private fun isSameDay(a: Long, b: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = a }
    val second = Calendar.getInstance().apply { timeInMillis = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
