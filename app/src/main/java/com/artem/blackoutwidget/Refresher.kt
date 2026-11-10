package com.artem.blackoutwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Fetches the schedules of all places, stores them, and repaints the widgets. Never throws except on cancellation. */
object Refresher {
    private val mutex = Mutex()

    suspend fun refresh(context: Context) {
        val ctx = context.applicationContext
        mutex.withLock {
            val now = System.currentTimeMillis()
            val places = Prefs.places(ctx)
            if (places.isNotEmpty()) {
                try {
                    refreshAll(ctx, places, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the last cached schedules; just remember that this attempt failed.
                    val message = e.message ?: e.javaClass.simpleName
                    for (place in places) Prefs.saveStatus(ctx, place.id, now, message)
                }
            }
            try {
                Reminders.plan(ctx)
            } catch (e: Exception) {
                // A reminder problem must never turn a successful download into a "failed" refresh.
            }
        }
        ScheduleWidget().updateAll(ctx)
    }

    private suspend fun refreshAll(ctx: Context, all: List<Place>, now: Long) {
        // A place that is not filled in yet cannot be fetched; say so instead of silently skipping it.
        val ready = all.filter { it.configured }
        for (place in all) {
            if (!place.configured) {
                Prefs.saveStatus(ctx, place.id, now, ctx.getString(R.string.err_not_configured))
            }
        }
        if (ready.isEmpty()) return

        // One page load serves every place: the schedule holds all queues, and the house lookups of
        // the places that are found by address are made from the same page.
        val lookups = ready.map { if (it.usesAddress) it.city to it.street else "" to "" }
        val raw = DtekClient(ctx).fetch(lookups)

        for ((index, place) in ready.withIndex()) {
            val previous = Prefs.loadCache(ctx, place.id)
            try {
                val fresh = buildCache(ctx, place, raw, raw.homes.getOrNull(index), now)
                Prefs.saveCache(ctx, place.id, fresh)
                Prefs.saveStatus(ctx, place.id, now, null)
                afterSuccess(ctx, place, previous, fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Prefs.saveStatus(ctx, place.id, now, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun buildCache(ctx: Context, place: Place, raw: DtekClient.Raw, home: String?, now: Long): Cache {
        // Exactly one source is in use, chosen in settings: the queue picked by hand, or the address.
        var group = if (place.byGroup) GroupUtil.normalizeGroup(place.manualGroup) else null
        if (group == null && place.usesAddress && home != null) {
            group = FactParser.groupForHouse(home, place.house)
            if (group != null) Prefs.saveDetectedGroup(ctx, place.id, group)
        }
        // Never fall back to an old detected group when the user picked a queue by hand.
        if (group == null && !place.byGroup) group = place.detectedGroup.ifBlank { null }
        if (group == null) {
            val extra = raw.warning?.let { " ($it)" } ?: ""
            throw DtekException(ctx.getString(R.string.err_no_group) + extra)
        }

        val days = FactParser.parseGroupDays(raw.fact, group)
        if (days.isEmpty()) {
            throw DtekException(ctx.getString(R.string.err_no_days, group))
        }
        return Cache(now, FactParser.updateStamp(raw.fact), group, days)
    }

    /** Notifies about changes against the previous download of this place. */
    private fun afterSuccess(ctx: Context, place: Place, previous: Cache?, fresh: Cache) {
        try {
            if (!place.notify) return
            val s = Prefs.load(ctx)
            val today = LocalDate.now(ScheduleLogic.ZONE)
            val events = ChangeDetector.diff(previous, fresh, today).filter {
                when (it) {
                    is ScheduleEvent.Published -> s.notifyTomorrow
                    is ScheduleEvent.Changed -> s.notifyChanged
                }
            }
            if (events.isNotEmpty()) Notifier.scheduleEvents(ctx, place, events, fresh, today)
        } catch (e: Exception) {
            // A notification problem must never turn a successful download into a "failed" refresh.
        }
    }
}
