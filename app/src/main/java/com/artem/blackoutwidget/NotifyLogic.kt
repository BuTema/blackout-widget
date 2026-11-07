package com.artem.blackoutwidget

import java.time.LocalDate
import java.time.LocalDateTime

/** A real switch between power on and power off at [at]. */
data class Change(val at: LocalDateTime, val toOn: Boolean)

/** One notification to show at [fireAt]: [leadMinutes] before [change] (0 = at the moment of the change). */
data class Reminder(
    val fireAt: LocalDateTime,
    val change: Change,
    val leadMinutes: Int,
    /** The change after this one, when the known schedule contains it. */
    val followedBy: Change?,
)

data class NotifyOptions(
    /** Minutes before a change at which to remind, e.g. {15, 30, 60}. */
    val leads: Set<Int> = emptySet(),
    /** Also notify at the moment of the change. */
    val atChange: Boolean = false,
    val powerOff: Boolean = true,
    val powerOn: Boolean = true,
) {
    val enabled: Boolean get() = (leads.isNotEmpty() || atChange) && (powerOff || powerOn)
}

/** What changed between two downloads of the schedule. */
sealed interface ScheduleEvent {
    val date: LocalDate

    /** A day that was not in the previous download (typically tomorrow). */
    data class Published(override val date: LocalDate) : ScheduleEvent

    /** A day that was already known, but whose on/off hours are different now. */
    data class Changed(override val date: LocalDate) : ScheduleEvent
}

object ReminderPlanner {
    /** A reminder that is more than this late is dropped instead of shown. */
    const val GRACE_MINUTES = 10L

    /**
     * The switches between power on and off in the known schedule. Only a boundary between two touching
     * periods counts: the end of the last known period (or a gap between days) says nothing about what
     * happens next.
     */
    fun changes(days: Map<LocalDate, Map<Int, String>>): List<Change> {
        val periods = ScheduleLogic.periods(days)
        val out = ArrayList<Change>()
        for (i in 1 until periods.size) {
            val prev = periods[i - 1]
            val cur = periods[i]
            if (prev.end == cur.start && prev.on != cur.on) out.add(Change(cur.start, cur.on))
        }
        return out
    }

    /**
     * Reminders that have not been shown yet: later than [handledUntil], and not older than
     * [GRACE_MINUTES] at [now]. Sorted by time; at equal times the longest lead comes first.
     */
    fun pending(
        days: Map<LocalDate, Map<Int, String>>,
        options: NotifyOptions,
        handledUntil: LocalDateTime,
        now: LocalDateTime,
    ): List<Reminder> {
        val all = changes(days)
        val offsets = options.leads.toMutableSet()
        if (options.atChange) offsets.add(0)
        val result = ArrayList<Reminder>()
        for ((i, change) in all.withIndex()) {
            if (change.toOn && !options.powerOn) continue
            if (!change.toOn && !options.powerOff) continue
            val followedBy = all.getOrNull(i + 1)
            for (lead in offsets) {
                val fireAt = change.at.minusMinutes(lead.toLong())
                if (fireAt.isAfter(handledUntil) && fireAt.plusMinutes(GRACE_MINUTES).isAfter(now)) {
                    result.add(Reminder(fireAt, change, lead, followedBy))
                }
            }
        }
        return result.sortedWith(compareBy<Reminder> { it.fireAt }.thenByDescending { it.leadMinutes })
    }

    /** The reminders from [pending] whose time has come, keeping only the most imminent one per change. */
    fun due(pending: List<Reminder>, asOf: LocalDateTime): List<Reminder> =
        pending
            .filter { !it.fireAt.isAfter(asOf) }
            .groupBy { it.change.at }
            .map { (_, group) -> group.minBy { it.leadMinutes } }
            .sortedBy { it.fireAt }
}

object ChangeDetector {
    /**
     * Compares the previous and the new download for the same queue. Days before [today] are ignored, and
     * nothing is reported for the very first download or when the queue itself changed.
     */
    fun diff(old: Cache?, new: Cache, today: LocalDate): List<ScheduleEvent> {
        if (old == null || old.group != new.group) return emptyList()
        val out = ArrayList<ScheduleEvent>()
        for (date in new.days.keys.sorted()) {
            if (date.isBefore(today)) continue
            val newHours = new.days.getValue(date)
            val oldHours = old.days[date]
            if (oldHours == null) {
                // Today appearing for the first time is not news; a later day appearing is.
                if (date.isAfter(today)) out.add(ScheduleEvent.Published(date))
            } else if (!ScheduleLogic.slotsOf(oldHours).contentEquals(ScheduleLogic.slotsOf(newHours))) {
                // Compared as on/off slots, so a rewording like "maybe" -> "yes" is not a change.
                out.add(ScheduleEvent.Changed(date))
            }
        }
        return out
    }
}

object ScheduleSummary {
    /** The power-off stretches of one day as "HH:mm–HH:mm" strings. */
    fun offRanges(date: LocalDate, hours: Map<Int, String>): List<String> =
        ScheduleLogic.periods(mapOf(date to hours))
            .filter { !it.on }
            .map { ScheduleLogic.hm(it.start) + "–" + ScheduleLogic.hm(it.end) }
}
