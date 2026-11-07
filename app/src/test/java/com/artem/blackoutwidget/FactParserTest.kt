package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FactParserTest {
    @Test
    fun parsesOneGroupAndMapsEpochToKyivDate() {
        val days = FactParser.parseGroupDays(Fixtures.FACT, "GPV6.2")
        assertEquals(setOf(LocalDate.of(2026, 11, 4)), days.keys)
        val hours = days.getValue(LocalDate.of(2026, 11, 4))
        assertEquals(24, hours.size)
        assertEquals("no", hours[1])
        assertEquals("yes", hours[5])
        assertEquals("no", hours[24])
    }

    @Test
    fun unknownGroupGivesNoDays() {
        assertEquals(emptyMap<LocalDate, Map<Int, String>>(), FactParser.parseGroupDays(Fixtures.FACT, "GPV9.9"))
    }

    @Test
    fun readsUpdateStamp() {
        assertEquals("04.11.2026 09:44", FactParser.updateStamp(Fixtures.FACT))
    }

    @Test
    fun findsGroupForHouse() {
        assertEquals("GPV6.2", FactParser.groupForHouse(Fixtures.HOME, "9"))
        assertEquals("GPV1.1", FactParser.groupForHouse(Fixtures.HOME, "3б"))
    }

    @Test
    fun latinLookalikeLettersMatchCyrillicHouseNumbers() {
        // "9a" typed with a Latin 'a' must find the key "9а" (Cyrillic а); also case/space insensitive.
        assertEquals("GPV6.2", FactParser.groupForHouse(Fixtures.HOME, " 9A "))
    }

    @Test
    fun unknownOrGrouplessHouseGivesNull() {
        assertNull(FactParser.groupForHouse(Fixtures.HOME, "123"))
        assertNull(FactParser.groupForHouse(Fixtures.HOME, "5"))
        assertNull(FactParser.groupForHouse("""{"result":false}""", "9"))
    }

    @Test
    fun normalizesGroupNames() {
        assertEquals("GPV6.2", GroupUtil.normalizeGroup("6.2"))
        assertEquals("GPV6.2", GroupUtil.normalizeGroup(" gpv6.2 "))
        assertEquals("GPV1.1", GroupUtil.normalizeGroup("GPV1.1"))
        assertNull(GroupUtil.normalizeGroup(""))
        assertNull(GroupUtil.normalizeGroup("62"))
        assertNull(GroupUtil.normalizeGroup("abc"))
    }

    @Test
    fun cacheSurvivesJsonRoundTrip() {
        val days = FactParser.parseGroupDays(Fixtures.FACT, "GPV1.1")
        val cache = Cache(1_700_000_000_000L, "04.11.2026 09:44", "GPV1.1", days)
        val back = CacheJson.fromJson(CacheJson.toJson(cache))
        assertEquals(cache, back)
    }
}
