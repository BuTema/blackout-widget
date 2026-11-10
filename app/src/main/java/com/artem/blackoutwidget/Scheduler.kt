package com.artem.blackoutwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Refresher.refresh(applicationContext)
        // Failures are stored and shown in the widget; the next periodic run tries again.
        return Result.success()
    }
}

/** Repaints the widget every half hour so periods switch on time, even between data refreshes. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        Scheduler.ensure(app) // also re-arms the reminder alarm (it is lost on reboot)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Prefs.poke(app)
                ScheduleWidget().updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}

object Scheduler {
    private const val PERIODIC = "periodic_refresh"
    private const val NOW = "refresh_now"
    private const val HALF_HOUR_MS = 30L * 60L * 1000L

    /** Starts the 30-minute data refresh (if not running) and arms the next half-hour repaint. */
    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        scheduleTick(context)
        Reminders.plan(context)
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(tickIntent(context))
    }

    fun scheduleTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = (System.currentTimeMillis() / HALF_HOUR_MS + 1) * HALF_HOUR_MS + 2_000L
        // Inexact alarm: needs no special permission, may fire a little late.
        am.setAndAllowWhileIdle(AlarmManager.RTC, next, tickIntent(context))
    }

    private fun tickIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
