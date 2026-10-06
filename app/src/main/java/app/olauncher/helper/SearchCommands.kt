package app.olauncher.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import app.olauncher.R
import app.olauncher.data.Constants
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.pow

/*
Commands typed into the app drawer search:
  timer 10 / timer 90s / timer 1h   starts a timer
  alarm 7:30 / alarm 7 / alarm 6:45pm   sets an alarm
  18*1.2                             shows the answer, tap to copy
  call mum / text mum                opens the dialer or messages for a contact
  !query                             web search (DuckDuckGo, bangs work)
*/

class SearchCommand(
    val verb: String,
    val target: String,
    // Whether the drawer should close itself; commands that open another app close it anyway
    val closesDrawer: Boolean,
    val run: (Context) -> Unit,
)

// Shown when a contact command needs the contacts permission first
const val VERB_ALLOW_CONTACTS = "allow contacts"

private val timerRegex = Regex("""^timer\s+(\d+)\s*(s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?)?$""")
private val alarmRegex = Regex("""^alarm\s+(\d{1,2})(?:[:.]?(\d{2}))?\s*(am|pm)?$""")
private val contactRegex = Regex("""^(call|text)\s+(.+)$""")
private val calcCharsRegex = Regex("""^[\d\s.+\-*/x×()^]+$""")
private val calcOperatorRegex = Regex("""[+\-*/x×^]""")

fun parseSearchCommands(context: Context, rawQuery: String, canReadContacts: Boolean): List<SearchCommand> {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return emptyList()

    if (query.startsWith("!") && query.length > 1)
        return listOf(SearchCommand("search", rawQuery.trim(), closesDrawer = false) {
            it.openUrl(Constants.URL_DUCK_SEARCH + Uri.encode(rawQuery.trim()))
        })

    timerRegex.find(query)?.let { match ->
        val amount = match.groupValues[1].toIntOrNull() ?: return@let
        val unit = match.groupValues[2]
        val (seconds, label) = when {
            unit.startsWith("s") -> amount to "$amount sec"
            unit.startsWith("h") -> amount * 3600 to "$amount hr"
            else -> amount * 60 to "$amount min"
        }
        if (seconds !in 1..86400) return@let
        return listOf(SearchCommand("start timer", label, closesDrawer = true) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (it.startCommand(intent)) it.showToast(it.getString(R.string.timer_started, label))
        })
    }

    alarmRegex.find(query)?.let { match ->
        var hour = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].ifEmpty { "0" }.toInt()
        val period = match.groupValues[3]
        if (minutes > 59) return@let
        if (period.isNotEmpty()) {
            if (hour !in 1..12) return@let
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
        } else if (hour > 23) return@let
        val label = "${match.groupValues[1].toInt()}:%02d$period".format(minutes)
        return listOf(SearchCommand("set alarm", label, closesDrawer = true) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (it.startCommand(intent)) it.showToast(it.getString(R.string.alarm_set, label))
        })
    }

    contactRegex.find(query)?.let { match ->
        val isCall = match.groupValues[1] == "call"
        val name = match.groupValues[2].trim()
        if (!canReadContacts) return listOf(SearchCommand(VERB_ALLOW_CONTACTS, "", closesDrawer = false) {})
        return findContacts(context, name).map { (contactName, number) ->
            SearchCommand(match.groupValues[1], contactName.lowercase(), closesDrawer = false) {
                val intent = if (isCall) Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                it.startCommand(intent)
            }
        }
    }

    calculate(query)?.let { answer ->
        return listOf(SearchCommand("=", answer, closesDrawer = true) {
            val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(answer, answer))
            it.showToast(it.getString(R.string.copied_value, answer))
        })
    }

    return emptyList()
}

private fun Context.startCommand(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    e.printStackTrace()
    showToast(getString(R.string.command_no_app))
    false
}

// Up to 3 contacts whose name, or a word in it, starts with [name], with their main number
private fun findContacts(context: Context, name: String): List<Pair<String, String>> {
    val cleaned = name.replace(Regex("[%_]"), "")
    if (cleaned.isEmpty()) return emptyList()
    val results = linkedMapOf<Long, Pair<String, String>>()
    try {
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            "${Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR ${Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("$cleaned%", "% $cleaned%"),
            "${Phone.DISPLAY_NAME_PRIMARY} ASC, ${Phone.IS_SUPER_PRIMARY} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < 3) {
                val id = cursor.getLong(0)
                val displayName = cursor.getString(1) ?: continue
                val number = cursor.getString(2) ?: continue
                if (id !in results) results[id] = displayName to number
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return results.values.toList()
}

// Arithmetic with + - * / ^ and brackets; x and × also multiply
fun calculate(query: String): String? {
    if (!calcCharsRegex.matches(query) || query.none { it.isDigit() }) return null
    if (!calcOperatorRegex.containsMatchIn(query.trimStart().removePrefix("-"))) return null
    val value = try {
        Calculator(query.replace('x', '*').replace('×', '*').filterNot { it.isWhitespace() }).parse()
    } catch (e: Exception) {
        return null
    }
    if (value.isNaN() || value.isInfinite()) return null
    if (value == Math.rint(value) && abs(value) < 1e15) return value.toLong().toString()
    return BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
}

private class Calculator(private val input: String) {
    private var pos = 0

    fun parse(): Double {
        val value = expression()
        if (pos != input.length) throw IllegalArgumentException("Unexpected '${input[pos]}'")
        return value
    }

    private fun expression(): Double {
        var value = term()
        while (pos < input.length) {
            when (input[pos]) {
                '+' -> { pos++; value += term() }
                '-' -> { pos++; value -= term() }
                else -> return value
            }
        }
        return value
    }

    private fun term(): Double {
        var value = power()
        while (pos < input.length) {
            when (input[pos]) {
                '*' -> { pos++; value *= power() }
                '/' -> { pos++; value /= power() }
                else -> return value
            }
        }
        return value
    }

    private fun power(): Double {
        val base = unary()
        if (pos < input.length && input[pos] == '^') {
            pos++
            return base.pow(power())
        }
        return base
    }

    private fun unary(): Double {
        if (pos < input.length && input[pos] == '-') {
            pos++
            return -unary()
        }
        return primary()
    }

    private fun primary(): Double {
        if (pos < input.length && input[pos] == '(') {
            pos++
            val value = expression()
            if (pos >= input.length || input[pos] != ')') throw IllegalArgumentException("Missing ')'")
            pos++
            return value
        }
        val start = pos
        while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
        return input.substring(start, pos).toDouble()
    }
}
