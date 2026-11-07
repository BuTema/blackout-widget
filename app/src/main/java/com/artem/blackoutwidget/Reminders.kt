package com.artem.blackoutwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime

/**
 * Keeps one alarm armed for the next reminder. When it fires, [fire] shows what is due and arms the next
 * one. [plan] is safe to call at any time (after a refresh, a settings change, boot...).
 */
object Reminders {
    /** Called after the user changes notification options: forget old moments, then re-arm. */
    fun onSettingsChanged(ctx: Context) {
        Prefs.saveHandledUntil(ctx, System.currentTimeMillis())
        plan(ctx)
    }

    fun plan(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(app)
        am.cancel(pi)

        val options = Prefs.load(app).reminderOptions()
        val cache = Prefs.loadCache(app)
        if (!options.enabled || cache == null) return

        val nowMs = System.currentTimeMillis()
        var handledMs = Prefs.loadHandledUntil(app)
        if (handledMs == 0L) {
            // First run: do not show reminders for moments that passed before they were switched on.
            handledMs = nowMs
            Prefs.saveHandledUntil(app, handledMs)
        }
        val next = ReminderPlanner
            .pending(cache.days, options, toLocal(handledMs), toLocal(nowMs))
            .firstOrNull() ?: return
        // A reminder that is already due (alarm delayed, or just planned) fires almost immediately.
        arm(am, pi, maxOf(toMillis(next.fireAt), nowMs + 1_000L))
    }

    /** Runs when the alarm goes off. */
    fun fire(ctx: Context) {
        val app = ctx.applicationContext
        val options = Prefs.load(app).reminderOptions()
        val cache = Prefs.loadCache(app)
        val nowMs = System.currentTimeMillis()
        if (options.enabled && cache != null) {
            val now = toLocal(nowMs)
            val pending = ReminderPlanner.pending(cache.days, options, toLocal(Prefs.loadHandledUntil(app)), now)
            for (r in ReminderPlanner.due(pending, now.plusSeconds(5))) {
                Notifier.reminder(app, r)
            }
        }
        // Everything up to (about) now is handled, even if nothing was shown (data changed meanwhile).
        Prefs.saveHandledUntil(app, nowMs + 5_000L)
        plan(app)
    }

    private fun arm(am: AlarmManager, pi: PendingIntent, atMillis: Long) {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                return
            }
        } catch (e: SecurityException) {
            // Exact alarms were revoked; fall through to the inexact one.
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    private fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            1,
            Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun toMillis(t: LocalDateTime): Long = t.atZone(ScheduleLogic.ZONE).toInstant().toEpochMilli()

    private fun toLocal(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(ScheduleLogic.ZONE).toLocalDateTime()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Reminders.fire(app)
                // A change just happened (or is about to): repaint the widget right away.
                ScheduleWidget().updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
