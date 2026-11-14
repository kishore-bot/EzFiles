package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/**
 * Hands a phone number, an email address or a date to the phone's own apps.
 * Nothing happens without the user finishing it there: the dialer only shows
 * the number, the mail app opens a draft, the calendar opens a new event.
 * Each returns `false` when no installed app takes it.
 */
interface QuickActions {
    fun dial(number: String): Boolean

    fun email(address: String): Boolean

    /** An all-day event on [year]-[month]-[day] (month 1–12) titled [title]. */
    fun addToCalendar(title: String, year: Int, month: Int, day: Int): Boolean
}

/** `null` where the platform has none (iOS is parked). */
@Composable
expect fun rememberQuickActions(): QuickActions?
