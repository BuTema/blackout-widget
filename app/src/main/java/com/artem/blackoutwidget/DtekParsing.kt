package com.artem.blackoutwidget

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

object GroupUtil {
    private val PATTERN = Regex("^(?:GPV)?(\\d)\\.(\\d)$", RegexOption.IGNORE_CASE)

    /** "6.2", "gpv6.2" and "GPV6.2" all become "GPV6.2"; anything else becomes null. */
    fun normalizeGroup(raw: String): String? {
        val m = PATTERN.matchEntire(raw.trim()) ?: return null
        return "GPV" + m.groupValues[1] + "." + m.groupValues[2]
    }
}

object FactParser {
    private val GROUP_ID = Regex("^GPV\\d+\\.\\d+$")

    private val LATIN_TO_CYRILLIC = mapOf(
        'a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'i' to 'і',
        'k' to 'к', 'm' to 'м', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
    )

    /** House numbers like "9а" use Cyrillic letters; people type the Latin look-alikes. */
    fun normalizeHouse(raw: String): String =
        raw.trim().lowercase().filter { !it.isWhitespace() }
            .map { LATIN_TO_CYRILLIC[it] ?: it }
            .joinToString("")

    /**
     * Reads the getHomeNum response: {"result":true,"data":{"<house>":{"sub_type_reason":["GPV6.2"],...}}}
     * and returns the schedule group of the given house, if the response names one.
     */
    fun groupForHouse(homeJson: String, house: String): String? {
        val data = JSONObject(homeJson).optJSONObject("data") ?: return null
        val wanted = normalizeHouse(house)
        for (key in data.keys()) {
            if (normalizeHouse(key) != wanted) continue
            val reasons = data.optJSONObject(key)?.optJSONArray("sub_type_reason") ?: continue
            for (i in 0 until reasons.length()) {
                val id = reasons.optString(i)
                if (GROUP_ID.matches(id)) return id
            }
        }
        return null
    }

    private val STAMP = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy")

    private fun stampMillis(text: String): Long? = try {
        java.time.LocalDateTime.parse(text.trim(), STAMP).atZone(ScheduleLogic.ZONE).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /**
     * The emergency outage DTEK lists for a house in getHomeNum, if one is still going on:
     * {"type":"2","sub_type":"Аварійні ремонтні роботи","start_date":"07:56 02.07.2025","end_date":"15:25 01.10.2026"}.
     */
    fun emergencyForHouse(homeJson: String, house: String, nowMillis: Long): Emergency? {
        val data = JSONObject(homeJson).optJSONObject("data") ?: return null
        val wanted = normalizeHouse(house)
        for (key in data.keys()) {
            if (normalizeHouse(key) != wanted) continue
            val entry = data.optJSONObject(key) ?: continue
            if (entry.optString("type") != "2") continue
            val end = stampMillis(entry.optString("end_date")) ?: continue
            if (end <= nowMillis) continue
            val start = stampMillis(entry.optString("start_date")) ?: nowMillis
            return Emergency(start, end, entry.optString("sub_type").trim())
        }
        return null
    }

    /**
     * Reads DisconSchedule.fact: {"data":{"<dayStartEpochSeconds>":{"GPV6.2":{"1":"no",...}}},"update":"..."}
     * and returns the hourly statuses of one group for every day present.
     */
    fun parseGroupDays(factJson: String, group: String): Map<LocalDate, Map<Int, String>> {
        val data = JSONObject(factJson).optJSONObject("data") ?: return emptyMap()
        val out = LinkedHashMap<LocalDate, Map<Int, String>>()
        for (key in data.keys()) {
            val epoch = key.toLongOrNull() ?: continue
            val hours = data.optJSONObject(key)?.optJSONObject(group) ?: continue
            val statuses = HashMap<Int, String>()
            for (h in hours.keys()) {
                val hour = h.toIntOrNull() ?: continue
                statuses[hour] = hours.optString(h)
            }
            val date = Instant.ofEpochSecond(epoch).atZone(ScheduleLogic.ZONE).toLocalDate()
            out[date] = statuses
        }
        return out
    }

    fun updateStamp(factJson: String): String = JSONObject(factJson).optString("update", "")
}

object CacheJson {
    fun toJson(c: Cache): String {
        val days = JSONObject()
        for ((date, hours) in c.days) {
            val h = JSONObject()
            for ((hour, status) in hours) h.put(hour.toString(), status)
            days.put(date.toString(), h)
        }
        return JSONObject()
            .put("updated", c.updatedAtMillis)
            .put("dtekUpdate", c.dtekUpdate)
            .put("group", c.group)
            .put("days", days)
            .put("approx", c.approxGroup)
            .also { json ->
                c.emergency?.let {
                    json.put("emergency", JSONObject().put("start", it.startMillis).put("end", it.endMillis).put("reason", it.reason))
                }
            }
            .toString()
    }

    fun fromJson(json: String): Cache {
        val o = JSONObject(json)
        val daysJson = o.getJSONObject("days")
        val days = LinkedHashMap<LocalDate, Map<Int, String>>()
        for (dateKey in daysJson.keys()) {
            val hoursJson = daysJson.getJSONObject(dateKey)
            val hours = HashMap<Int, String>()
            for (h in hoursJson.keys()) hours[h.toInt()] = hoursJson.getString(h)
            days[LocalDate.parse(dateKey)] = hours
        }
        val emergency = o.optJSONObject("emergency")?.let {
            Emergency(it.getLong("start"), it.getLong("end"), it.optString("reason", ""))
        }
        return Cache(o.getLong("updated"), o.optString("dtekUpdate", ""), o.getString("group"), days, emergency, o.optBoolean("approx", false))
    }
}
