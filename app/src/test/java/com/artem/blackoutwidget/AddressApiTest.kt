package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressApiTest {
    private val streets = listOf("вул. Травнева", "пров. 5-й Травневий", "вул. Юності", "вул. Мала Травнева")

    @Test
    fun parsesStreetsAsLists() {
        val json = """{"result":true,"streets":{"м. Одеса":["вул. Юності","пров. 5-й Травневий"],"смт Авангард":["вул. Центральна"]}}"""
        val out = AddressParsing.parseStreets(json)
        assertEquals(listOf("м. Одеса", "смт Авангард"), out.keys.toList())
        assertEquals(listOf("вул. Юності", "пров. 5-й Травневий"), out.getValue("м. Одеса"))
    }

    @Test
    fun parsesStreetsAsObjects() {
        val json = """{"result":true,"streets":{"м. Одеса":{"0":"вул. А","1":"вул. Б"}}}"""
        val out = AddressParsing.parseStreets(json)
        assertEquals(setOf("вул. А", "вул. Б"), out.getValue("м. Одеса").toSet())
    }

    @Test
    fun unexpectedStreetShapeGivesEmpty() {
        assertTrue(AddressParsing.parseStreets("""{"result":false}""").isEmpty())
    }

    @Test
    fun parsesHousesSortedNaturallyWithGroups() {
        val houses = AddressParsing.parseHouses(Fixtures.HOME)
        assertEquals(listOf("3б", "5", "9", "9а"), houses.map { it.number })
        assertEquals(listOf("GPV1.1", null, "GPV6.2", "GPV6.2"), houses.map { it.group })
    }

    @Test
    fun housesOfUnknownStreetAreEmpty() {
        assertTrue(AddressParsing.parseHouses("""{"result":false}""").isEmpty())
    }

    @Test
    fun suggestionsStartWithTheNameThenContain() {
        assertEquals(
            listOf("вул. Травнева", "пров. 5-й Травневий", "вул. Мала Травнева"),
            Suggest.filter(streets, "трав"),
        )
    }

    @Test
    fun suggestionsMatchTypedPrefixToo() {
        assertEquals(listOf("вул. Травнева"), Suggest.filter(streets, "ВУЛ.  травн"))
    }

    @Test
    fun emptyQueryGivesFirstItemsAndLimitApplies() {
        assertEquals(streets.take(2), Suggest.filter(streets, "", limit = 2))
        assertEquals(1, Suggest.filter(streets, "трав", limit = 1).size)
    }

    @Test
    fun noMatchGivesNothing() {
        assertTrue(Suggest.filter(streets, "zzz").isEmpty())
    }

    @Test
    fun exactIgnoresCaseAndSpaces() {
        assertEquals("вул. Юності", Suggest.exact(streets, "  ВУЛ.   юності "))
        assertNull(Suggest.exact(streets, "вул. Юн"))
        assertNull(Suggest.exact(streets, ""))
    }
}
