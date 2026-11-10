package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceJsonTest {
    @Test
    fun roundTrip() {
        val places = listOf(
            Place("main", "Дім", "oem", "м. Одеса", "пров. 5-й Травневий", "9", false, "", "GPV6.2", true),
            Place("p2", "Мама", "krem", byGroup = true, manualGroup = "GPV1.1", notify = false),
        )
        assertEquals(places, PlaceJson.fromJson(PlaceJson.toJson(places)))
    }

    @Test
    fun regionDefaultsToOdesaAndUnknownIdsToo() {
        assertEquals(DtekRegion.ODESA, PlaceJson.fromJson("""[{"id":"x"}]""").single().region)
        assertEquals(DtekRegion.ODESA, DtekRegion.byId("nope"))
        assertEquals(DtekRegion.KYIV_REGION, Place("a", regionId = "krem").region)
    }

    @Test
    fun skipsEntriesWithoutId() {
        assertEquals(emptyList<Place>(), PlaceJson.fromJson("""[{"name":"x"},{"id":""}]"""))
    }

    @Test
    fun newIdIsUnused() {
        assertEquals("p1", PlaceJson.newId(emptyList()))
        assertEquals("p3", PlaceJson.newId(listOf("main", "p2")))
        assertEquals("p4", PlaceJson.newId(listOf("p2", "p3", "p1")))
    }

    @Test
    fun configuredAndKnownGroup() {
        val byAddress = Place("a", street = "вул. А", house = "1")
        assertTrue(byAddress.configured)
        assertTrue(byAddress.usesAddress)
        assertNull(byAddress.knownGroup)

        val detectedOnly = Place("b", detectedGroup = "GPV6.2")
        assertTrue(detectedOnly.configured)
        assertEquals("GPV6.2", detectedOnly.knownGroup)

        val manual = Place("c", byGroup = true, manualGroup = "GPV3.1", detectedGroup = "GPV6.2")
        assertTrue(manual.configured)
        assertFalse(manual.usesAddress)
        assertEquals("GPV3.1", manual.knownGroup)

        assertFalse(Place("d").configured)
        assertFalse(Place("e", byGroup = true).configured)
    }
}
