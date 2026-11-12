package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborGroupTest {
    @Test
    fun placeKeepsTheNeighbourQueueThroughJson() {
        val p = Place(id = "a", street = "вул. Станюкевича", house = "85", neighborGroup = "GPV6.2")
        val back = PlaceJson.fromJson(PlaceJson.toJson(listOf(p))).single()
        assertEquals("GPV6.2", back.neighborGroup)
        assertTrue(back.configured)
        assertEquals("", PlaceJson.fromJson(PlaceJson.toJson(listOf(Place(id = "b")))).single().neighborGroup)
    }

    @Test
    fun cacheRemembersThatTheGroupIsApproximate() {
        val c = Cache(1L, "x", "GPV6.2", emptyMap(), null, true)
        assertTrue(CacheJson.fromJson(CacheJson.toJson(c)).approxGroup)
    }

    @Test
    fun anEmergencyHouseAlsoGetsTheSuggestion() {
        val directory = mapOf("м. Кривий Ріг" to listOf("вул. Станюкевича"))
        val houses = listOf(
            HouseInfo("44", "GPV6.2", true), HouseInfo("1/А", "GPV6.2", true), HouseInfo("85", null, true),
        )
        val lookup = HouseLookup.Loaded("м. Кривий Ріг", "вул. Станюкевича", houses)
        val check = AddressValidator.check(directory, lookup, "м. Кривий Ріг", "вул. Станюкевича", "85")
        assertEquals(AddressState.EMERGENCY_NO_GROUP, check.state)
        assertEquals("GPV6.2", check.suggestedGroup)
    }
}
