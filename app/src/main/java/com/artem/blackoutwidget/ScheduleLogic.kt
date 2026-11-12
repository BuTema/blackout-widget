package com.artem.blackoutwidget

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A continuous stretch of time with power on (or off). */
data class Period(val start: LocalDateTime, val end: LocalDateTime, val on: Boolean, val emergency: Boolean = false)

/** An emergency outage DTEK reports for an address: it overrides the schedule until [endMillis]. */
data class Emergency(val startMillis: Long, val endMillis: Long, val reason: String)

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
    val emergency: Emergency? = null,
    /** The group is the neighbours' one, not DTEK's answer for the house itself. */
    val approxGroup: Boolean = false,
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
            val mixed = mixedCode(hours[h])
            if (mixed != null) {
                slots[i] = mixed[0] == 'y'
                slots[i + 1] = mixed[1] == 'y'
                continue
            }
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

    /**
     * An hour touched by an emergency outage is stored as "x" plus one letter per half hour:
     * y = power on, n = off by the schedule, e = off by an emergency outage ("xye", "xee", ...).
     */
    private fun mixedCode(status: String?): String? =
        if (status != null && status.length == 3 && status[0] == 'x') status.substring(1) else null

    /** The half-hour slots (true) that are off because of an emergency outage. */
    fun emergencySlotsOf(hours: Map<Int, String>): BooleanArray {
        val slots = BooleanArray(SLOTS)
        for (h in 1..24) {
            val mixed = mixedCode(hours[h]) ?: continue
            slots[(h - 1) * 2] = mixed[0] == 'e'
            slots[(h - 1) * 2 + 1] = mixed[1] == 'e'
        }
        return slots
    }

    /** Marks the slots an emergency outage covers; they count as power off and are remembered as such. */
    fun overlayEmergency(days: Map<LocalDate, Map<Int, String>>, e: Emergency): Map<LocalDate, Map<Int, String>> {
        val from = java.time.Instant.ofEpochMilli(e.startMillis).atZone(ZONE).toLocalDateTime()
        val to = java.time.Instant.ofEpochMilli(e.endMillis).atZone(ZONE).toLocalDateTime()
        val out = LinkedHashMap<LocalDate, Map<Int, String>>()
        for ((date, hours) in days) {
            val planned = slotsOf(hours)
            val start = date.atStartOfDay()
            fun covered(i: Int): Boolean {
                val s = start.plusMinutes(i * 30L)
                return s < to && s.plusMinutes(30) > from
            }
            fun letter(i: Int) = if (covered(i)) 'e' else if (planned[i]) 'y' else 'n'
            val changed = HashMap(hours)
            for (h in 1..24) {
                val i = (h - 1) * 2
                if (covered(i) || covered(i + 1)) changed[h] = "x" + letter(i) + letter(i + 1)
            }
            out[date] = changed
        }
        return out
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
    fun periods(days: Map<LocalDate, Map<Int, String>>, splitEmergency: Boolean = false): List<Period> {
        val result = ArrayList<Period>()
        var start: LocalDateTime? = null
        var on = true
        var emergency = false
        var lastEnd: LocalDateTime? = null
        for (date in days.keys.sorted()) {
            val dayStart = date.atStartOfDay()
            if (start != null && lastEnd != dayStart) {
                result.add(Period(start, lastEnd!!, on, emergency))
                start = null
            }
            val slots = slotsOf(days.getValue(date))
            val urgent = if (splitEmergency) emergencySlotsOf(days.getValue(date)) else BooleanArray(SLOTS)
            for (i in 0 until SLOTS) {
                val t = dayStart.plusMinutes(i * 30L)
                if (start == null) {
                    start = t
                    on = slots[i]
                    emergency = urgent[i]
                } else if (slots[i] != on || urgent[i] != emergency) {
                    result.add(Period(start, t, on, emergency))
                    start = t
                    on = slots[i]
                    emergency = urgent[i]
                }
            }
            lastEnd = dayStart.plusDays(1)
        }
        if (start != null && lastEnd != null) {
            result.add(Period(start, lastEnd, on, emergency))
        }
        return result
    }

    fun state(days: Map<LocalDate, Map<Int, String>>, now: LocalDateTime): WidgetState {
        val today = days[now.toLocalDate()] ?: return WidgetState.NoData
        if (slotsOf(today).all { it }) return WidgetState.AllDayOn
        val list = periods(days, splitEmergency = true)
        val idx = list.indexOfFirst { !now.isBefore(it.start) && now.isBefore(it.end) }
        if (idx < 0) return WidgetState.NoData
        return WidgetState.Periods(list[idx], list.getOrNull(idx + 1))
    }

    fun hm(t: LocalDateTime): String = t.format(HM)
}
