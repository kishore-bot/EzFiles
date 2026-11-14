package com.ezfiles.reader.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Calendar
import java.util.TimeZone

@Composable
actual fun rememberQuickActions(): QuickActions? {
    val context = LocalContext.current
    return remember(context) { AndroidQuickActions(context) }
}

/**
 * Plain intents that need no permission: ACTION_DIAL only fills in the
 * dialer, ACTION_SENDTO opens a mail draft and ACTION_INSERT opens the
 * calendar's new-event screen. The app never reads or writes the calendar.
 */
private class AndroidQuickActions(private val context: Context) : QuickActions {
    override fun dial(number: String) = start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    override fun email(address: String) = start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))

    override fun addToCalendar(title: String, year: Int, month: Int, day: Int): Boolean {
        // All-day events are midnight to midnight in UTC.
        val begin = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + DAY_MS)
        return start(intent)
    }

    private fun start(intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
