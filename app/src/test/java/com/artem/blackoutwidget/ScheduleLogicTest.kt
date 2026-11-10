package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleLogicTest {
    private val d4 = LocalDate.of(2026, 11, 4)
    private val d5 = LocalDate.of(2026, 11, 5)

    private fun day(group: String, date: LocalDate = d4) =
        FactParser.parseGroupDays(Fixtures.FACT, group).getValue(LocalDate.of(2026, 11, 4)).let { date to it }

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m)

    private fun fmt(p: Period) = "${ScheduleLogic.hm(p.start)}-${ScheduleLogic.hm(p.end)} " + if (p.on) "on" else "off"

    @Test
    fun hourlyStatusesBecomeHalfHourSlots() {
        val slots = ScheduleLogic.slotsOf(mapOf(1 to "no", 2 to "first", 3 to "second", 4 to "yes", 5 to "maybe"))
        assertFalse(slots[0]); assertFalse(slots[1]) // hour 1 off
        assertFalse(slots[2]); assertTrue(slots[3]) // hour 2: first half off
        assertTrue(slots[4]); assertFalse(slots[5]) // hour 3: second half off
        assertTrue(slots[6]); assertTrue(slots[7]) // hour 4 on
        assertTrue(slots[8]); assertTrue(slots[9]) // hour 5 "maybe" counts as on
        assertEquals(48, slots.size)
        assertTrue(slots[47]) // missing hours count as on
    }

    @Test
    fun realGroup62Periods() {
        val periods = ScheduleLogic.periods(mapOf(day("GPV6.2")))
        assertEquals(
            listOf("00:00-04:00 off", "04:00-10:00 on", "10:00-18:00 off", "18:00-21:00 on", "21:00-00:00 off"),
            periods.map(::fmt),
        )
    }

    @Test
    fun halfHourEntriesShiftPeriodEdges() {
        val periods = ScheduleLogic.periods(mapOf(day("GPV1.1")))
        assertEquals(
            listOf("00:00-03:30 on", "03:30-07:30 off", "07:30-14:00 on", "14:00-21:30 off", "21:30-00:00 on"),
            periods.map(::fmt),
        )
    }

    @Test
    fun duringOutageShowsCurrentOffThenNextOn() {
        val state = ScheduleLogic.state(mapOf(day("GPV6.2")), at(d4, 17, 40))
        state as WidgetState.Periods
        assertEquals("10:00-18:00 off", fmt(state.current))
        assertEquals("18:00-21:00 on", fmt(state.next!!))
    }

    @Test
    fun whilePowerIsOnShowsCurrentOnThenNextOff() {
        val state = ScheduleLogic.state(mapOf(day("GPV6.2")), at(d4, 18, 0)) as WidgetState.Periods
        assertEquals("18:00-21:00 on", fmt(state.current))
        assertEquals("21:00-00:00 off", fmt(state.next!!))
    }

    @Test
    fun periodBoundaryBelongsToTheNewPeriod() {
        val before = ScheduleLogic.state(mapOf(day("GPV6.2")), at(d4, 9, 59)) as WidgetState.Periods
        val exact = ScheduleLogic.state(mapOf(day("GPV6.2")), at(d4, 10, 0)) as WidgetState.Periods
        assertEquals("04:00-10:00 on", fmt(before.current))
        assertEquals("10:00-18:00 off", fmt(exact.current))
    }

    @Test
    fun outageSpanningMidnightIsMergedWhenTomorrowIsKnown() {
        val days = mapOf(day("GPV6.2"), day("GPV6.2", d5))
        val state = ScheduleLogic.state(days, at(d4, 22, 0)) as WidgetState.Periods
        assertEquals(at(d4, 21), state.current.start)
        assertEquals(at(d5, 4), state.current.end)
        assertFalse(state.current.on)
        assertEquals(at(d5, 4), state.next!!.start)
    }

    @Test
    fun lastPeriodOfKnownDataHasNoNext() {
        val state = ScheduleLogic.state(mapOf(day("GPV6.2")), at(d4, 22, 0)) as WidgetState.Periods
        assertNull(state.next)
    }

    @Test
    fun noOutagesTodayMeansAllDayOn() {
        val allOn = (1..24).associateWith { "yes" }
        assertEquals(WidgetState.AllDayOn, ScheduleLogic.state(mapOf(d4 to allOn), at(d4, 12)))
    }

    @Test
    fun missingTodayMeansNoData() {
        assertEquals(WidgetState.NoData, ScheduleLogic.state(mapOf(day("GPV6.2", d5)), at(d4, 12)))
        assertEquals(WidgetState.NoData, ScheduleLogic.state(emptyMap(), LocalDateTime.of(2026, 11, 4, 12, 0)))
    }

    @Test
    fun runsGroupConsecutiveSlots() {
        assertEquals(listOf(true to 48), ScheduleLogic.runs(BooleanArray(48) { true }))
        val slots = BooleanArray(48) { it !in 12..13 && it != 20 }
        assertEquals(
            listOf(true to 12, false to 2, true to 6, false to 1, true to 27),
            ScheduleLogic.runs(slots),
        )
        assertTrue(ScheduleLogic.runs(BooleanArray(0)).isEmpty())
    }
}
