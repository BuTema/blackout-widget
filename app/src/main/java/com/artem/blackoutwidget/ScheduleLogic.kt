package com.artem.blackoutwidget

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A continuous stretch of time with power on (or off). */
data class Period(val start: LocalDateTime, val end: LocalDateTime, val on: Boolean)

sealed interface WidgetState {
    /** No usable schedule for today. */
    data object NoData : WidgetState

    /** Today's schedule has no outages at all. */
    data object AllDayOn : WidgetState

    data class Periods(val current: Period, val next: Period?) : WidgetState
}

/** What the last successful fetch stored: the schedule of the user's group, per day. */
data class Cache(
    val updatedAtMillis: Long,
    val dtekUpdate: String,
    val group: String,
    val days: Map<LocalDate, Map<Int, String>>,
)

object ScheduleLogic {
    /** DTEK publishes schedules in Kyiv time. "Europe/Kiev" is the name every Android version knows. */
    val ZONE: ZoneId = ZoneId.of("Europe/Kiev")

    private const val SLOTS = 48 // half-hour slots per day
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Converts DTEK's hourly statuses (hour 1..24 -> yes/no/first/second/maybe...) into 48
     * half-hour slots, true = power is on.
     *  - "no": off for the whole hour
     *  - "first": off for the first 30 minutes, "second": off for the last 30 minutes
     *  - everything else ("yes", "maybe", "mfirst", "msecond", missing): treated as on
     */
    fun slotsOf(hours: Map<Int, String>): BooleanArray {
        val slots = BooleanArray(SLOTS) { true }
        for (h in 1..24) {
            val i = (h - 1) * 2
            when (hours[h]) {
                "no" -> {
                    slots[i] = false
                    slots[i + 1] = false
                }
                "first" -> slots[i] = false
                "second" -> slots[i + 1] = false
                else -> Unit
            }
        }
        return slots
    }

    /** Groups consecutive slots with the same state: (power on?, number of slots), in order. */
    fun runs(slots: BooleanArray): List<Pair<Boolean, Int>> {
        val result = ArrayList<Pair<Boolean, Int>>()
        var i = 0
        while (i < slots.size) {
            var j = i
            while (j < slots.size && slots[j] == slots[i]) j++
            result.add(slots[i] to (j - i))
            i = j
        }
        return result
    }

    /** Merges consecutive slots (also across midnight, when both days are known) into periods. */
    fun periods(days: Map<LocalDate, Map<Int, String>>): List<Period> {
        val result = ArrayList<Period>()
        var start: LocalDateTime? = null
        var on = true
        var lastEnd: LocalDateTime? = null
        for (date in days.keys.sorted()) {
            val dayStart = date.atStartOfDay()
            if (start != null && lastEnd != dayStart) {
                result.add(Period(start, lastEnd!!, on))
                start = null
            }
            val slots = slotsOf(days.getValue(date))
            for (i in 0 until SLOTS) {
                val t = dayStart.plusMinutes(i * 30L)
                if (start == null) {
                    start = t
                    on = slots[i]
                } else if (slots[i] != on) {
                    result.add(Period(start, t, on))
                    start = t
                    on = slots[i]
                }
            }
            lastEnd = dayStart.plusDays(1)
        }
        if (start != null && lastEnd != null) {
            result.add(Period(start, lastEnd, on))
        }
        return result
    }

    fun state(days: Map<LocalDate, Map<Int, String>>, now: LocalDateTime): WidgetState {
        val today = days[now.toLocalDate()] ?: return WidgetState.NoData
        if (slotsOf(today).all { it }) return WidgetState.AllDayOn
        val list = periods(days)
        val idx = list.indexOfFirst { !now.isBefore(it.start) && now.isBefore(it.end) }
        if (idx < 0) return WidgetState.NoData
        return WidgetState.Periods(list[idx], list.getOrNull(idx + 1))
    }

    fun hm(t: LocalDateTime): String = t.format(HM)
}
