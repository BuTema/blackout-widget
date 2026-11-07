package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NotifyLogicTest {
    private val d3 = LocalDate.of(2026, 11, 3)
    private val d4 = LocalDate.of(2026, 11, 4)
    private val d5 = LocalDate.of(2026, 11, 5)
    private val d6 = LocalDate.of(2026, 11, 6)

    private fun hours(group: String): Map<Int, String> =
        FactParser.parseGroupDays(Fixtures.FACT, group).getValue(d4)

    private fun at(h: Int, m: Int = 0, d: LocalDate = d4): LocalDateTime = d.atTime(h, m)

    private fun cache(vararg days: Pair<LocalDate, Map<Int, String>>, group: String = "GPV6.2") =
        Cache(0L, "", group, mapOf(*days))

    // ---- changes ----

    @Test
    fun changesOfOneDay() {
        val changes = ReminderPlanner.changes(mapOf(d4 to hours("GPV6.2")))
        assertEquals(
            listOf(Change(at(4), true), Change(at(10), false), Change(at(18), true), Change(at(21), false)),
            changes,
        )
    }

    @Test
    fun changesAcrossTwoKnownDaysSkipTheLastEnd() {
        val changes = ReminderPlanner.changes(mapOf(d4 to hours("GPV6.2"), d5 to hours("GPV6.2")))
        assertEquals(8, changes.size)
        assertEquals(Change(at(21, 0, d5), false), changes.last())
        // The outage from 21:00 runs through midnight, so midnight itself is not a change.
        assertFalse(changes.any { it.at == d5.atStartOfDay() })
    }

    @Test
    fun gapBetweenKnownDaysIsNotAChange() {
        val changes = ReminderPlanner.changes(mapOf(d4 to hours("GPV6.2"), d6 to hours("GPV6.2")))
        assertFalse(changes.any { it.at == d5.atStartOfDay() || it.at == d6.atStartOfDay() })
        assertEquals(8, changes.size)
    }

    // ---- reminders ----

    private val opts = NotifyOptions(leads = setOf(15, 30), atChange = true)

    @Test
    fun plansLeadsAndMomentForEachFutureChange() {
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), opts, handledUntil = at(0), now = at(9))
        // Future changes: 10:00 off, 18:00 on, 21:00 off. The 04:00 one is long past.
        assertEquals(9, p.size)
        assertEquals(listOf(at(9, 30), at(9, 45), at(10)), p.take(3).map { it.fireAt })
        assertEquals(listOf(30, 15, 0), p.take(3).map { it.leadMinutes })
        assertEquals(Change(at(10), false), p.first().change)
        assertEquals(Change(at(18), true), p.first().followedBy)
    }

    @Test
    fun lastKnownChangeHasNoFollowingChange() {
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), opts, handledUntil = at(0), now = at(20))
        assertTrue(p.isNotEmpty())
        assertEquals(Change(at(21), false), p.last().change)
        assertEquals(null, p.last().followedBy)
    }

    @Test
    fun noOptionsMeansNoReminders() {
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), NotifyOptions(), at(0), at(9))
        assertTrue(p.isEmpty())
    }

    @Test
    fun canLimitRemindersToPowerReturning() {
        val p = ReminderPlanner.pending(
            mapOf(d4 to hours("GPV6.2")), opts.copy(powerOff = false), handledUntil = at(0), now = at(9),
        )
        assertEquals(3, p.size)
        assertTrue(p.all { it.change.toOn })
    }

    @Test
    fun alreadyHandledRemindersAreSkipped() {
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), opts, handledUntil = at(9, 45), now = at(9, 46))
        assertEquals(at(10), p.first().fireAt)
    }

    @Test
    fun staleRemindersAreDroppedInsteadOfShownLate() {
        // 09:30 + 10 min grace = 09:40 < 09:50, so the 30-minute reminder is gone; 09:45 is still fresh.
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), opts, handledUntil = at(0), now = at(9, 50))
        assertEquals(at(9, 45), p.first().fireAt)
    }

    @Test
    fun dueKeepsOnlyTheMostImminentReminderPerChange() {
        val o = NotifyOptions(leads = setOf(15, 10))
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), o, handledUntil = at(0), now = at(9, 52))
        val due = ReminderPlanner.due(p, asOf = at(9, 52))
        assertEquals(1, due.size)
        assertEquals(10, due.single().leadMinutes)
    }

    @Test
    fun dueIgnoresRemindersInTheFuture() {
        val p = ReminderPlanner.pending(mapOf(d4 to hours("GPV6.2")), opts, handledUntil = at(0), now = at(9, 46))
        val due = ReminderPlanner.due(p, asOf = at(9, 46))
        assertEquals(listOf(15), due.map { it.leadMinutes })
    }

    // ---- schedule change detection ----

    @Test
    fun firstDownloadIsNotNews() {
        assertTrue(ChangeDetector.diff(null, cache(d4 to hours("GPV6.2")), d4).isEmpty())
    }

    @Test
    fun newTomorrowIsPublished() {
        val old = cache(d4 to hours("GPV6.2"))
        val new = cache(d4 to hours("GPV6.2"), d5 to hours("GPV6.2"))
        assertEquals(listOf(ScheduleEvent.Published(d5)), ChangeDetector.diff(old, new, d4))
    }

    @Test
    fun changedHoursAreReported() {
        val old = cache(d4 to hours("GPV6.2"))
        val new = cache(d4 to hours("GPV1.1"))
        assertEquals(listOf(ScheduleEvent.Changed(d4)), ChangeDetector.diff(old, new, d4))
    }

    @Test
    fun rewordedStatusWithSameOnOffIsNotAChange() {
        val reworded = hours("GPV6.2").toMutableMap()
        reworded[5] = "maybe" // was "yes": still counts as power on
        val old = cache(d4 to hours("GPV6.2"))
        val new = cache(d4 to reworded)
        assertTrue(ChangeDetector.diff(old, new, d4).isEmpty())
    }

    @Test
    fun otherQueueIsNotCompared() {
        val old = cache(d4 to hours("GPV6.2"), group = "GPV6.2")
        val new = cache(d4 to hours("GPV1.1"), d5 to hours("GPV1.1"), group = "GPV1.1")
        assertTrue(ChangeDetector.diff(old, new, d4).isEmpty())
    }

    @Test
    fun daysInThePastAndTodayAppearingAreIgnored() {
        val old = cache(d3 to hours("GPV6.2"))
        val new = cache(d3 to hours("GPV1.1"), d4 to hours("GPV6.2"))
        assertTrue(ChangeDetector.diff(old, new, d4).isEmpty())
    }

    // ---- summary ----

    @Test
    fun summarisesPowerOffStretches() {
        assertEquals(
            listOf("00:00–04:00", "10:00–18:00", "21:00–00:00"),
            ScheduleSummary.offRanges(d4, hours("GPV6.2")),
        )
    }

    @Test
    fun summaryIsEmptyWhenPowerIsOnAllDay() {
        assertTrue(ScheduleSummary.offRanges(d4, (1..24).associateWith { "yes" }).isEmpty())
    }
}
