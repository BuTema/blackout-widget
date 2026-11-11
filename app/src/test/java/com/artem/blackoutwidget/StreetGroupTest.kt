package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreetGroupTest {
    private fun h(n: String, g: String?) = HouseInfo(n, g)

    @Test
    fun offersTheQueueTheStreetAgreesOn() {
        val houses = listOf(h("44", "GPV6.2"), h("1/А", "GPV6.2"), h("7/А", "GPV6.2"), h("85", null))
        assertEquals("GPV6.2", AddressValidator.streetGroup(houses))
    }

    @Test
    fun staysQuietWhenThereIsNoAgreement() {
        assertNull(AddressValidator.streetGroup(listOf(h("1", "GPV6.2"))))
        assertNull(AddressValidator.streetGroup(listOf(h("1", "GPV6.2"), h("2", "GPV1.1"))))
        assertNull(AddressValidator.streetGroup(listOf(h("1", null), h("2", null))))
    }

    @Test
    fun theFormCarriesTheSuggestionForAnUnknownHouse() {
        val directory = mapOf("м. Кривий Ріг" to listOf("вул. Станюкевича"))
        val houses = listOf(h("44", "GPV6.2"), h("1/А", "GPV6.2"))
        val lookup = HouseLookup.Loaded("м. Кривий Ріг", "вул. Станюкевича", houses)
        val check = AddressValidator.check(directory, lookup, "м. Кривий Ріг", "вул. Станюкевича", "85")
        assertEquals(AddressState.HOUSE_UNKNOWN, check.state)
        assertEquals("GPV6.2", check.suggestedGroup)
    }
}
