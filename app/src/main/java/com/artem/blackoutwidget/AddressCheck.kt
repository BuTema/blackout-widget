package com.artem.blackoutwidget

/** What the address form can say about the address that is typed in. */
enum class AddressState {
    /** Not filled in yet: nothing to say. */
    EMPTY,
    /** Waiting for DTEK's answer. */
    CHECKING,
    CITY_UNKNOWN,
    STREET_UNKNOWN,
    HOUSE_UNKNOWN,
    /** DTEK knows the house but names no schedule group for it. */
    NO_GROUP,
    /** DTEK confirmed the house and named its group. */
    FOUND,
    /** An emergency outage is on at the house, so DTEK names no group now; the group comes later. */
    EMERGENCY_NO_GROUP,
    /** DTEK could not be asked (no network); the address is kept as typed. */
    UNVERIFIED,
    /** The address that is already saved, untouched. */
    SAVED,
}

/** The verdict plus the values to store: names exactly as DTEK spells them. */
data class AddressCheck(
    val state: AddressState,
    val city: String,
    val street: String,
    val house: String,
    val group: String?,
    /** The queue most houses of the street have, offered when DTEK has nothing for this house. */
    val suggestedGroup: String? = null,
) {
    val canSave: Boolean
        get() = state == AddressState.FOUND || state == AddressState.EMERGENCY_NO_GROUP ||
            state == AddressState.UNVERIFIED || state == AddressState.SAVED
}

/** Where the house list for the typed city and street stands. */
sealed interface HouseLookup {
    data object Idle : HouseLookup
    data object Loading : HouseLookup
    data object Failed : HouseLookup
    data class Loaded(val city: String, val street: String, val houses: List<HouseInfo>) : HouseLookup
}

/** Pure address validation, so it can be unit-tested. */
object AddressValidator {
    /**
     * The city and street exactly as DTEK spells them, or null while they are empty or not in the list.
     * With no street list at all ([directory] null) the typed text is used as it is, and DTEK's
     * house answer decides.
     */
    fun target(directory: Map<String, List<String>>?, city: String, street: String): Pair<String, String>? {
        val c = city.trim()
        val s = street.trim()
        if (c.isEmpty() || s.isEmpty()) return null
        if (directory == null) return c to s
        val canonCity = Suggest.exact(directory.keys, c) ?: return null
        val canonStreet = Suggest.exact(directory[canonCity].orEmpty(), s) ?: return null
        return canonCity to canonStreet
    }

    /**
     * The group nearly all of the street's houses that have one share (at least two houses, four in five
     * of them agreeing), or null. Neighbours usually sit in the same queue, but not always.
     */
    fun streetGroup(houses: List<HouseInfo>): String? {
        val groups = houses.mapNotNull { it.group }
        if (groups.size < 2) return null
        val top = groups.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return if (top.value * 5 >= groups.size * 4) top.key else null
    }

    fun check(
        directory: Map<String, List<String>>?,
        lookup: HouseLookup,
        city: String,
        street: String,
        house: String,
    ): AddressCheck {
        val c = city.trim()
        val s = street.trim()
        val h = house.trim()
        fun result(
            state: AddressState, cc: String = c, ss: String = s, hh: String = h, group: String? = null,
            suggested: String? = null,
        ) = AddressCheck(state, cc, ss, hh, group, suggested)

        if (directory != null && c.isNotEmpty()) {
            val canonCity = Suggest.exact(directory.keys, c) ?: return result(AddressState.CITY_UNKNOWN)
            if (s.isNotEmpty() && Suggest.exact(directory[canonCity].orEmpty(), s) == null) {
                return result(AddressState.STREET_UNKNOWN)
            }
        }
        val target = target(directory, c, s)
        if (target == null || h.isEmpty()) return result(AddressState.EMPTY)
        val (canonCity, canonStreet) = target

        return when (lookup) {
            HouseLookup.Idle, HouseLookup.Loading -> result(AddressState.CHECKING, canonCity, canonStreet)
            HouseLookup.Failed -> result(AddressState.UNVERIFIED, canonCity, canonStreet)
            is HouseLookup.Loaded -> {
                if (lookup.city != canonCity || lookup.street != canonStreet) {
                    result(AddressState.CHECKING, canonCity, canonStreet)
                } else if (lookup.houses.isEmpty()) {
                    // The street list knew the street but DTEK has no houses for it: cannot be checked.
                    result(
                        if (directory == null) AddressState.STREET_UNKNOWN else AddressState.UNVERIFIED,
                        canonCity, canonStreet,
                    )
                } else {
                    val wanted = FactParser.normalizeHouse(h)
                    val match = lookup.houses.firstOrNull { FactParser.normalizeHouse(it.number) == wanted }
                    when {
                        match == null -> result(AddressState.HOUSE_UNKNOWN, canonCity, canonStreet, suggested = streetGroup(lookup.houses))
                        match.group == null && match.emergency ->
                            result(AddressState.EMERGENCY_NO_GROUP, canonCity, canonStreet, match.number)
                        match.group == null -> result(
                            AddressState.NO_GROUP, canonCity, canonStreet, match.number,
                            suggested = streetGroup(lookup.houses),
                        )
                        else -> result(AddressState.FOUND, canonCity, canonStreet, match.number, match.group)
                    }
                }
            }
        }
    }
}
