package com.artem.blackoutwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** A trimmed copy of what DTEK answered for м. Кривий Ріг, вул. Станюкевича during an emergency outage. */
class EmergencyHousesTest {
    private val outage = """"sub_type":"Аварійні ремонтні роботи","start_date":"22:07 10.11.2026","end_date":"11:50 11.11.2026","type":"2","voluntarily":null,"cek":null"""
    private val json = """
        {"result":true,"data":{
          "44":{$outage,"sub_type_reason":["GPV6.2"]},
          "85":{$outage,"sub_type_reason":[]},
          "3/А":{$outage,"sub_type_reason":[]}},
         "updateTimestamp":"11:32 11.11.2026"}
    """.trimIndent()

    private val now = LocalDateTime.of(2026, 11, 11, 11, 40).atZone(ScheduleLogic.ZONE).toInstant().toEpochMilli()

    @Test
    fun housesWithoutAGroupButWithAnOutageAreFlagged() {
        val houses = AddressParsing.parseHouses(json).associateBy { it.number }
        assertEquals("GPV6.2", houses.getValue("44").group)
        assertNull(houses.getValue("85").group)
        assertTrue(houses.getValue("85").emergency)
        assertTrue(houses.getValue("3/А").emergency)
    }

    @Test
    fun theOutageIsReadForAHouseWithoutAGroup() {
        val e = FactParser.emergencyForHouse(json, "85", now)
        assertNotNull(e)
        assertEquals("Аварійні ремонтні роботи", e!!.reason)
        assertNull(FactParser.groupForHouse(json, "85"))
        // The outage ended at 11:50.
        val later = LocalDateTime.of(2026, 11, 11, 12, 0).atZone(ScheduleLogic.ZONE).toInstant().toEpochMilli()
        assertNull(FactParser.emergencyForHouse(json, "85", later))
    }

    @Test
    fun theAddressFormAcceptsSuchAHouse() {
        val directory = mapOf("м. Кривий Ріг" to listOf("вул. Станюкевича"))
        val lookup = HouseLookup.Loaded("м. Кривий Ріг", "вул. Станюкевича", AddressParsing.parseHouses(json))
        val check = AddressValidator.check(directory, lookup, "м. Кривий Ріг", "вул. Станюкевича", "85")
        assertEquals(AddressState.EMERGENCY_NO_GROUP, check.state)
        assertTrue(check.canSave)
        assertEquals("85", check.house)
        // A house without a group and without an outage is still an error.
        val plain = HouseLookup.Loaded("м. Кривий Ріг", "вул. Станюкевича", listOf(HouseInfo("85", null)))
        val bad = AddressValidator.check(directory, plain, "м. Кривий Ріг", "вул. Станюкевича", "85")
        assertEquals(AddressState.NO_GROUP, bad.state)
        assertFalse(bad.canSave)
    }
}
