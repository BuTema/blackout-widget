package com.artem.blackoutwidget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One house number from DTEK's list for a street, with its schedule group when DTEK names one. */
data class HouseInfo(val number: String, val group: String?)

/** Reads DTEK's address answers. Pure functions, no Android calls, so they are unit-tested. */
object AddressParsing {
    private val GROUP_ID = Regex("^GPV\\d+\\.\\d+$")
    private val LEADING_NUMBER = Regex("^(\\d+)")

    /**
     * getStreets: {"result":true,"streets":{"м. Одеса":["вул. Юності","пров. 5-й Травневий",...],...}}
     * One call returns every city of the region with its streets. Returns an empty map for any other shape.
     */
    fun parseStreets(json: String): Map<String, List<String>> {
        val streets = JSONObject(json).optJSONObject("streets") ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (city in streets.keys()) {
            val list = ArrayList<String>()
            val array = streets.optJSONArray(city)
            if (array != null) {
                for (i in 0 until array.length()) {
                    val name = array.optString(i)
                    if (name.isNotBlank()) list.add(name)
                }
            } else {
                // Tolerate {"0":"вул. А","1":"вул. Б"} too.
                val obj = streets.optJSONObject(city)
                if (obj != null) {
                    for (key in obj.keys()) {
                        val name = obj.optString(key)
                        if (name.isNotBlank()) list.add(name)
                    }
                }
            }
            out[city] = list
        }
        return out
    }

    /**
     * getHomeNum: {"result":true,"data":{"9":{"sub_type_reason":["GPV6.2"],...},"9а":{...}}}
     * The keys are exactly the house numbers DTEK knows on that street. Empty when the street is unknown.
     */
    fun parseHouses(json: String): List<HouseInfo> {
        val data = JSONObject(json).optJSONObject("data") ?: return emptyList()
        val out = ArrayList<HouseInfo>()
        for (number in data.keys()) {
            var group: String? = null
            val reasons = data.optJSONObject(number)?.optJSONArray("sub_type_reason")
            if (reasons != null) {
                for (i in 0 until reasons.length()) {
                    val id = reasons.optString(i)
                    if (GROUP_ID.matches(id)) {
                        group = id
                        break
                    }
                }
            }
            out.add(HouseInfo(number, group))
        }
        // 3, 3а, 5, 9, 9а, 10 - by the leading number, then by the rest.
        return out.sortedWith(
            compareBy<HouseInfo>(
                { LEADING_NUMBER.find(it.number)?.value?.toIntOrNull() ?: Int.MAX_VALUE },
                { it.number },
            ),
        )
    }
}

/** Ranks autocomplete suggestions. */
object Suggest {
    private val SPACES = Regex("\\s+")
    private val TYPE_PREFIX = Regex("^\\S+\\.\\s*")

    fun normalize(text: String): String = text.trim().lowercase().replace(SPACES, " ")

    /** "вул. Травнева" -> "травнева": people type the name, not the "вул." / "пров." part. */
    private fun bare(normalized: String): String = normalized.replace(TYPE_PREFIX, "")

    /** Items that start with the query (ignoring "вул."), then items that merely contain it. */
    fun filter(items: List<String>, query: String, limit: Int = 8): List<String> {
        val q = normalize(query)
        if (q.isEmpty()) return items.take(limit)
        val starts = ArrayList<String>()
        val contains = ArrayList<String>()
        for (item in items) {
            val n = normalize(item)
            when {
                n.startsWith(q) || bare(n).startsWith(q) -> starts.add(item)
                n.contains(q) -> contains.add(item)
            }
            if (starts.size >= limit) break
        }
        return (starts + contains).take(limit)
    }

    /** The item that equals the query, ignoring case and extra spaces; null if there is none. */
    fun exact(items: Collection<String>, query: String): String? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        return items.firstOrNull { normalize(it) == q }
    }
}

/** The region's city -> streets list, downloaded once from DTEK and kept on disk. */
object AddressDirectory {
    private const val FILE = "dtek_streets.json"
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    @Volatile
    private var memory: Map<String, List<String>>? = null

    fun cached(): Map<String, List<String>>? = memory

    /** Reads the saved copy, if any. */
    suspend fun load(ctx: Context): Map<String, List<String>>? = withContext(Dispatchers.Default) {
        val inMemory = memory
        if (inMemory != null) return@withContext inMemory
        val file = File(ctx.applicationContext.filesDir, FILE)
        if (!file.exists()) return@withContext null
        try {
            val parsed = AddressParsing.parseStreets(file.readText())
            if (parsed.isEmpty()) null else parsed.also { memory = it }
        } catch (e: Exception) {
            null
        }
    }

    fun isStale(ctx: Context): Boolean {
        val file = File(ctx.applicationContext.filesDir, FILE)
        return !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
    }

    /** Downloads the list. Throws when DTEK's answer is not a usable street list. */
    suspend fun refresh(ctx: Context, session: DtekSession): Map<String, List<String>> {
        val text = session.post("getStreets", emptyList())
        val parsed = withContext(Dispatchers.Default) { AddressParsing.parseStreets(text) }
        if (parsed.isEmpty()) throw DtekException("Unexpected street list from DTEK: " + text.take(120))
        withContext(Dispatchers.IO) { File(ctx.applicationContext.filesDir, FILE).writeText(text) }
        memory = parsed
        return parsed
    }
}

object AddressApi {
    private val STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** The house numbers DTEK has for [street] in [city]; empty if DTEK does not know that street. */
    suspend fun houses(session: DtekSession, city: String, street: String): List<HouseInfo> {
        val stamp = LocalDateTime.now(ScheduleLogic.ZONE).format(STAMP)
        val text = session.post(
            "getHomeNum",
            listOf("city" to city, "street" to street, "updateFact" to stamp),
        )
        return withContext(Dispatchers.Default) { AddressParsing.parseHouses(text) }
    }
}
