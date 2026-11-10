package com.artem.blackoutwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Fetches the schedule, stores it, and repaints the widget. Never throws except on cancellation. */
object Refresher {
    private val mutex = Mutex()

    suspend fun refresh(context: Context) {
        val ctx = context.applicationContext
        mutex.withLock {
            val now = System.currentTimeMillis()
            val previous = Prefs.loadCache(ctx)
            try {
                val fresh = doRefresh(ctx, now)
                Prefs.saveStatus(ctx, now, null)
                afterSuccess(ctx, previous, fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the last cached schedule; just remember that this attempt failed.
                Prefs.saveStatus(ctx, now, e.message ?: e.javaClass.simpleName)
            }
        }
        ScheduleWidget().updateAll(ctx)
    }

    /** Notifies about changes against the previous download and re-arms reminders for the new schedule. */
    private fun afterSuccess(ctx: Context, previous: Cache?, fresh: Cache) {
        try {
            val s = Prefs.load(ctx)
            val today = LocalDate.now(ScheduleLogic.ZONE)
            val events = ChangeDetector.diff(previous, fresh, today).filter {
                when (it) {
                    is ScheduleEvent.Published -> s.notifyTomorrow
                    is ScheduleEvent.Changed -> s.notifyChanged
                }
            }
            if (events.isNotEmpty()) Notifier.scheduleEvents(ctx, events, fresh, today)
            Reminders.plan(ctx)
        } catch (e: Exception) {
            // A notification problem must never turn a successful download into a "failed" refresh.
        }
    }

    private suspend fun doRefresh(ctx: Context, now: Long): Cache {
        val s = Prefs.load(ctx)
        // Exactly one source is in use, chosen in settings: the queue picked by hand, or the address.
        val manual = if (s.byGroup) GroupUtil.normalizeGroup(s.manualGroup) else null
        val hasAddress = !s.byGroup && s.street.isNotBlank() && s.house.isNotBlank()
        val configured = if (s.byGroup) manual != null else hasAddress || s.detectedGroup.isNotBlank()
        if (!configured) throw DtekException(ctx.getString(R.string.err_not_configured))

        val lookup = hasAddress
        val raw = DtekClient(ctx).fetch(
            city = if (lookup) s.city else "",
            street = if (lookup) s.street else "",
        )

        var group = manual
        if (group == null && lookup && raw.home != null) {
            group = FactParser.groupForHouse(raw.home, s.house)
            if (group != null) Prefs.saveDetectedGroup(ctx, group)
        }
        // Never fall back to an old detected group when the user picked a queue by hand.
        if (group == null && !s.byGroup) group = s.detectedGroup.ifBlank { null }
        if (group == null) {
            val extra = raw.warning?.let { " ($it)" } ?: ""
            throw DtekException(ctx.getString(R.string.err_no_group) + extra)
        }

        val days = FactParser.parseGroupDays(raw.fact, group)
        if (days.isEmpty()) {
            throw DtekException(ctx.getString(R.string.err_no_days, group))
        }
        val fresh = Cache(now, FactParser.updateStamp(raw.fact), group, days)
        Prefs.saveCache(ctx, fresh)
        return fresh
    }
}
