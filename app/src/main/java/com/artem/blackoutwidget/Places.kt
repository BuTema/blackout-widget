package com.artem.blackoutwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * A DTEK regional network. Each has its own site with its own street lists and schedules, so the
 * region matters for an address and for a hand-picked queue alike.
 */
enum class DtekRegion(val id: String, val host: String, val defaultCity: String) {
    ODESA("oem", "www.dtek-oem.com.ua", "м. Одеса"),
    KYIV_REGION("krem", "www.dtek-krem.com.ua", ""),
    DNIPRO("dnem", "www.dtek-dnem.com.ua", "м. Дніпро"),
    KYIV("kem", "www.dtek-kem.com.ua", "м. Київ"),
    ;

    val shutdownsUrl: String get() = "https://$host/ua/shutdowns"

    companion object {
        /** Unknown ids (and places saved before regions existed) are Odesa. */
        fun byId(id: String?): DtekRegion = entries.firstOrNull { it.id == id } ?: ODESA
    }
}

/**
 * One place the person wants a schedule for: "Дім", "Мама", "Дача"...
 * It is found either by address or by a hand-picked queue (exactly one of the two is in use).
 */
data class Place(
    val id: String,
    val name: String = "",
    /** [DtekRegion.id] of the DTEK network that serves the place. */
    val regionId: String = DtekRegion.ODESA.id,
    val city: String = "м. Одеса",
    val street: String = "",
    val house: String = "",
    /** True: use [manualGroup]. False: find the queue from the address. */
    val byGroup: Boolean = false,
    /** "GPV6.2", picked by hand; only used when [byGroup] is true. */
    val manualGroup: String = "",
    /** Queue found from the address (when the address was checked or fetched). */
    val detectedGroup: String = "",
    /** Whether the notification options (changes, reminders) apply to this place. */
    val notify: Boolean = true,
) {
    val region: DtekRegion get() = DtekRegion.byId(regionId)

    /** The address is the source and it is filled in, so DTEK is asked for the queue on every refresh. */
    val usesAddress: Boolean
        get() = !byGroup && street.isNotBlank() && house.isNotBlank()

    /** Enough is known to fetch a schedule. */
    val configured: Boolean
        get() = if (byGroup) {
            GroupUtil.normalizeGroup(manualGroup) != null
        } else {
            usesAddress || detectedGroup.isNotBlank()
        }

    /** The queue in use right now, if it is known: "GPV6.2". */
    val knownGroup: String?
        get() = if (byGroup) GroupUtil.normalizeGroup(manualGroup) else detectedGroup.ifBlank { null }
}

/** Saves the list of places as one JSON string. Pure, so it is unit-tested. */
object PlaceJson {
    fun toJson(places: List<Place>): String {
        val array = JSONArray()
        for (p in places) {
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("region", p.regionId)
                    .put("city", p.city)
                    .put("street", p.street)
                    .put("house", p.house)
                    .put("byGroup", p.byGroup)
                    .put("manualGroup", p.manualGroup)
                    .put("detectedGroup", p.detectedGroup)
                    .put("notify", p.notify),
            )
        }
        return array.toString()
    }

    fun fromJson(json: String): List<Place> {
        val array = JSONArray(json)
        val out = ArrayList<Place>()
        val d = Place(id = "")
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            out.add(
                Place(
                    id = id,
                    name = o.optString("name", d.name),
                    regionId = o.optString("region", d.regionId),
                    city = o.optString("city", d.city),
                    street = o.optString("street", d.street),
                    house = o.optString("house", d.house),
                    byGroup = o.optBoolean("byGroup", d.byGroup),
                    manualGroup = o.optString("manualGroup", d.manualGroup),
                    detectedGroup = o.optString("detectedGroup", d.detectedGroup),
                    notify = o.optBoolean("notify", d.notify),
                ),
            )
        }
        return out
    }

    /** A fresh id that is not used by any of [existing]. */
    fun newId(existing: Collection<String>): String {
        var n = existing.size + 1
        while (("p$n") in existing) n++
        return "p$n"
    }
}
