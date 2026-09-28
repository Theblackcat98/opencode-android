package dev.opencode.android.feature.servers.ui

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamps in the connection history and the event list.
 *
 * `SimpleDateFormat` is used for the millisecond time-of-day because the connection log reads as a
 * trace, where `HH:mm:ss.SSS` is the useful form. The formatter is created per call, which is fine
 * at the rate these lists change, and it is never shared across threads.
 */
internal object ClockFormatter {

    fun timeWithMillis(timestamp: Long): String =
        SimpleDateFormat(TIME_WITH_MILLIS, Locale.getDefault()).format(Date(timestamp))

    fun dateTime(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
}

private const val TIME_WITH_MILLIS = "HH:mm:ss.SSS"
