package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressValidatorTest {
    private val dir = mapOf("м. Одеса" to listOf("вул. Юності", "пров. 5-й Травневий"))
    private val houses = AddressParsing.parseHouses(Fixtures.HOME)
    private val loaded = HouseLookup.Loaded("м. Одеса", "пров. 5-й Травневий", houses)

    private fun check(
        city: String = "м. Одеса",
        street: String = "пров. 5-й Травневий",
        house: String = "9",
        lookup: HouseLookup = loaded,
        directory: Map<String, List<String>>? = dir,
    ) = AddressValidator.check(directory, lookup, city, street, house)

    @Test
    fun foundGivesCanonicalNamesAndGroup() {
        val r = check(city = " м.  одеса ", street = "ПРОВ. 5-Й травневий", house = "9")
        assertEquals(AddressState.FOUND, r.state)
        assertEquals("м. Одеса", r.city)
        assertEquals("пров. 5-й Травневий", r.street)
        assertEquals("9", r.house)
        assertEquals("GPV6.2", r.group)
        assertTrue(r.canSave)
    }

    @Test
    fun latinLetterInHouseMatchesCyrillic() {
        val r = check(house = "9a") // Latin a
        assertEquals(AddressState.FOUND, r.state)
        assertEquals("9а", r.house) // DTEK's own spelling, Cyrillic а
    }

    @Test
    fun unknownCityAndStreetAreReportedAtOnce() {
        assertEquals(AddressState.CITY_UNKNOWN, check(city = "м. Київ").state)
        assertEquals(AddressState.STREET_UNKNOWN, check(street = "вул. Нема", house = "").state)
    }

    @Test
    fun missingFieldsGiveEmpty() {
        assertEquals(AddressState.EMPTY, check(house = "").state)
        assertEquals(AddressState.EMPTY, check(street = "").state)
        assertFalse(check(house = "").canSave)
    }

    @Test
    fun unknownHouseAndHouseWithoutGroup() {
        assertEquals(AddressState.HOUSE_UNKNOWN, check(house = "77").state)
        val noGroup = check(house = "5")
        assertEquals(AddressState.NO_GROUP, noGroup.state)
        assertFalse(noGroup.canSave)
    }

    @Test
    fun waitingForLookup() {
        assertEquals(AddressState.CHECKING, check(lookup = HouseLookup.Idle).state)
        assertEquals(AddressState.CHECKING, check(lookup = HouseLookup.Loading).state)
        // A result for another street must not be used.
        val other = HouseLookup.Loaded("м. Одеса", "вул. Юності", houses)
        assertEquals(AddressState.CHECKING, check(lookup = other).state)
    }

    @Test
    fun networkFailureAllowsUnverifiedSave() {
        val r = check(lookup = HouseLookup.Failed)
        assertEquals(AddressState.UNVERIFIED, r.state)
        assertTrue(r.canSave)
        assertNull(r.group)
    }

    @Test
    fun withoutStreetListDtekHouseAnswerDecides() {
        val noDir = null as Map<String, List<String>>?
        val lookup = HouseLookup.Loaded("м. Одеса", "пров. 5-й Травневий", houses)
        assertEquals(AddressState.FOUND, check(directory = noDir, lookup = lookup).state)
        val empty = HouseLookup.Loaded("м. Одеса", "пров. 5-й Травневий", emptyList())
        assertEquals(AddressState.STREET_UNKNOWN, check(directory = noDir, lookup = empty).state)
    }

    @Test
    fun targetOnlyForKnownStreets() {
        assertEquals("м. Одеса" to "вул. Юності", AddressValidator.target(dir, "м. одеса", "вул. юності"))
        assertNull(AddressValidator.target(dir, "м. Одеса", "вул. Юн"))
        assertEquals("а" to "б", AddressValidator.target(null, "а", "б"))
    }
}
