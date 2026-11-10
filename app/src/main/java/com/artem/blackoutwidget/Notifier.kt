package com.artem.blackoutwidget

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Builds and shows the app's notifications. Texts come from string resources (Ukrainian / English). */
object Notifier {
    private const val CH_CHANGES = "schedule_changes"
    private const val CH_REMINDERS = "reminders"

    /** False when the user has not granted (Android 13+) or has turned off notifications for the app. */
    fun canPost(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_CHANGES,
                ctx.getString(R.string.channel_changes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_REMINDERS,
                ctx.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** "Tomorrow's schedule published" / "Today's schedule changed", with the new power-off hours. */
    fun scheduleEvents(ctx: Context, place: Place, events: List<ScheduleEvent>, cache: Cache, today: LocalDate) {
        for (e in events) {
            val hours = cache.days[e.date] ?: continue
            val day = dayWord(ctx, e.date, today)
            val title = when (e) {
                is ScheduleEvent.Published -> ctx.getString(R.string.notif_published_title, day)
                is ScheduleEvent.Changed -> ctx.getString(R.string.notif_changed_title, day)
            }
            val ranges = ScheduleSummary.offRanges(e.date, hours)
            val text = if (ranges.isEmpty()) {
                ctx.getString(R.string.notif_no_outages)
            } else {
                ctx.getString(R.string.notif_off_ranges, ranges.joinToString(", "))
            }
            val kind = if (e is ScheduleEvent.Published) 1 else 2
            post(ctx, CH_CHANGES, kind * 1000 + e.date.dayOfYear, title, text, place)
        }
    }

    /** One reminder: "power goes off at 18:00 (in 15 min)" or, at the moment itself, "power is off". */
    fun reminder(ctx: Context, place: Place, r: Reminder) {
        val at = ScheduleLogic.hm(r.change.at)
        val next = r.followedBy?.let { ScheduleLogic.hm(it.at) }
        val title: String
        val text: String
        if (r.leadMinutes > 0) {
            val lead = leadText(ctx, r.leadMinutes)
            if (r.change.toOn) {
                title = ctx.getString(R.string.reminder_on_title)
                text = ctx.getString(R.string.reminder_on_text, at, lead)
            } else {
                title = ctx.getString(R.string.reminder_off_title)
                text = ctx.getString(R.string.reminder_off_text, at, lead)
            }
        } else if (r.change.toOn) {
            title = ctx.getString(R.string.reminder_on_now_title)
            text = if (next != null) {
                ctx.getString(R.string.reminder_on_now_text_until, next)
            } else {
                ctx.getString(R.string.reminder_now_text_unknown)
            }
        } else {
            title = ctx.getString(R.string.reminder_off_now_title)
            text = if (next != null) {
                ctx.getString(R.string.reminder_off_now_text_until, next)
            } else {
                ctx.getString(R.string.reminder_now_text_unknown)
            }
        }
        // Reminders for the same change share an id, so a later one replaces an earlier one.
        val change = r.change.at
        val id = 10_000 + (change.toLocalDate().toEpochDay() % 1000).toInt() * 100 +
            change.hour * 2 + change.minute / 30
        post(ctx, CH_REMINDERS, id, title, text, place)
    }

    /** Shows a test notification. Returns false when notifications are blocked. */
    fun test(ctx: Context): Boolean =
        post(ctx, CH_CHANGES, 9_999, ctx.getString(R.string.notif_test_title), ctx.getString(R.string.notif_test_text))

    private fun leadText(ctx: Context, minutes: Int): String =
        if (minutes % 60 == 0) {
            ctx.getString(R.string.hours_short, minutes / 60)
        } else {
            ctx.getString(R.string.minutes_short, minutes)
        }

    private fun dayWord(ctx: Context, date: LocalDate, today: LocalDate): String = when (date) {
        today -> ctx.getString(R.string.day_today)
        today.plusDays(1) -> ctx.getString(R.string.day_tomorrow)
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM"))
    }

    /**
     * Shows one notification. With several places the place name is shown on it and tapping it opens that
     * place; each place also gets its own range of ids, so notifications of different places never replace
     * each other.
     */
    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, place: Place? = null): Boolean {
        if (!canPost(ctx)) return false
        ensureChannels(ctx)
        val places = Prefs.places(ctx)
        val slot = if (place == null) 0 else places.indexOfFirst { it.id == place.id }.coerceAtLeast(0) % 20
        val open = PendingIntent.getActivity(
            ctx,
            slot,
            MainActivity.openIntent(ctx, place?.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(
                if (channel == CH_REMINDERS) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS,
            )
        if (place != null && places.size > 1) {
            builder.setSubText(place.name.ifBlank { ctx.getString(R.string.default_home_name) })
        }
        NotificationManagerCompat.from(ctx).notify(id + slot * 1_000_000, builder.build())
        return true
    }
}
