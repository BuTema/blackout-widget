package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class EmergencyTest {
    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(ScheduleLogic.ZONE).toInstant().toEpochMilli()

    private fun home(type: String, end: String) = """
        {"result":true,"data":{"9":{"type":"$type","sub_type":"Аварійні ремонтні роботи",
        "start_date":"07:56 02.07.2026","end_date":"$end","sub_type_reason":[]}}}
    """.trimIndent()

    @Test
    fun readsAnActiveEmergencyOutage() {
        val e = FactParser.emergencyForHouse(home("2", "15:25 01.10.2026"), "9", millis(2026, 9, 1, 12, 0))
        assertNotNull(e)
        assertEquals("Аварійні ремонтні роботи", e!!.reason)
        assertEquals(millis(2026, 10, 1, 15, 25), e.endMillis)
        assertEquals(millis(2026, 7, 2, 7, 56), e.startMillis)
    }

    @Test
    fun ignoresFinishedPlannedAndOtherHouses() {
        val now = millis(2026, 9, 1, 12, 0)
        assertNull(FactParser.emergencyForHouse(home("2", "15:25 01.08.2026"), "9", now)) // ended
        assertNull(FactParser.emergencyForHouse(home("1", "15:25 01.10.2026"), "9", now)) // not an emergency
        assertNull(FactParser.emergencyForHouse(home("2", "15:25 01.10.2026"), "11", now)) // another house
        assertNull(FactParser.emergencyForHouse(home("2", "soon"), "9", now)) // unreadable time
    }

    @Test
    fun overlayMarksTheCoveredHalfHoursAsOff() {
        val date = LocalDate.of(2026, 11, 11)
        val days = mapOf(date to (1..24).associateWith { "yes" })
        val e = Emergency(millis(2026, 11, 11, 10, 15), millis(2026, 11, 11, 11, 45), "test")
        val hours = ScheduleLogic.overlayEmergency(days, e).getValue(date)

        val slots = ScheduleLogic.slotsOf(hours)
        val urgent = ScheduleLogic.emergencySlotsOf(hours)
        // 10:15-11:45 touches the half hours that start at 10:00, 10:30, 11:00 and 11:30.
        for (i in 0 until 48) {
            val covered = i in 20..23
            assertEquals("slot $i on", !covered, slots[i])
            assertEquals("slot $i emergency", covered, urgent[i])
        }
    }

    @Test
    fun emergencyKeepsPlannedOutagesAndSplitsPeriods() {
        val date = LocalDate.of(2026, 11, 11)
        // Planned: off 12:00-13:00 (hour 13 = "no").
        val planned = (1..24).associateWith { if (it == 13) "no" else "yes" }
        val e = Emergency(millis(2026, 11, 11, 10, 0), millis(2026, 11, 11, 12, 0), "test")
        val days = ScheduleLogic.overlayEmergency(mapOf(date to planned), e)

        val periods = ScheduleLogic.periods(days, splitEmergency = true)
        assertEquals(listOf(true, false, false, true), periods.map { it.on })
        assertEquals(listOf(false, true, false, false), periods.map { it.emergency })
        // Without the split an emergency and a planned outage next to each other are one period of no power.
        assertEquals(listOf(true, false, true), ScheduleLogic.periods(days).map { it.on })
        val slots = ScheduleLogic.slotsOf(days.getValue(date))
        assertFalse(slots[24]) // 12:00-12:30 is still off by the schedule
        assertTrue(slots[26]) // 13:00-13:30 is on again
    }

    @Test
    fun cacheKeepsTheEmergencyThroughJson() {
        val date = LocalDate.of(2026, 11, 11)
        val e = Emergency(1L, 2L, "reason")
        val cache = Cache(5L, "stamp", "GPV1.1", mapOf(date to mapOf(1 to "xee")), e)
        val back = CacheJson.fromJson(CacheJson.toJson(cache))
        assertEquals(e, back.emergency)
        assertTrue(ScheduleLogic.emergencySlotsOf(back.days.getValue(date))[0])
        assertNull(CacheJson.fromJson(CacheJson.toJson(cache.copy(emergency = null))).emergency)
    }
}
