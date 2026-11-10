package com.artem.blackoutwidget

import android.content.Context

/** Options that are the same for every place: widget look and notifications. */
data class Settings(
    val darkAlpha: Float = 0.85f,
    val lightAlpha: Float = 0.85f,
    /** Multiplies the size of the period times on the widget (1.0 = automatic fit). */
    val textScale: Float = 1f,
    /** Notify when a known day's on/off hours change. */
    val notifyChanged: Boolean = true,
    /** Notify when a new day (tomorrow) appears in the schedule. */
    val notifyTomorrow: Boolean = true,
    val lead60: Boolean = false,
    val lead30: Boolean = false,
    val lead15: Boolean = false,
    /** Also notify at the moment power is scheduled to switch. */
    val atChange: Boolean = false,
    val remindOff: Boolean = true,
    val remindOn: Boolean = true,
) {
    fun reminderOptions(): NotifyOptions {
        val leads = HashSet<Int>()
        if (lead60) leads.add(60)
        if (lead30) leads.add(30)
        if (lead15) leads.add(15)
        return NotifyOptions(leads, atChange, remindOff, remindOn)
    }

    /** True if any notification at all is switched on. */
    val anyNotification: Boolean
        get() = notifyChanged || notifyTomorrow || reminderOptions().enabled
}

data class Status(val lastAttemptMillis: Long, val lastError: String?)

object Prefs {
    /** The place that existed before the app could have several. Its data keeps the old storage keys. */
    private const val MAIN = "main"
    private const val PENDING_TTL_MS = 3 * 60 * 1000L

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- Global settings ----

    fun load(ctx: Context): Settings {
        val p = sp(ctx)
        val d = Settings()
        return Settings(
            darkAlpha = p.getFloat("darkAlpha", d.darkAlpha),
            lightAlpha = p.getFloat("lightAlpha", d.lightAlpha),
            textScale = p.getFloat("textScale", d.textScale),
            notifyChanged = p.getBoolean("notifyChanged", d.notifyChanged),
            notifyTomorrow = p.getBoolean("notifyTomorrow", d.notifyTomorrow),
            lead60 = p.getBoolean("lead60", d.lead60),
            lead30 = p.getBoolean("lead30", d.lead30),
            lead15 = p.getBoolean("lead15", d.lead15),
            atChange = p.getBoolean("atChange", d.atChange),
            remindOff = p.getBoolean("remindOff", d.remindOff),
            remindOn = p.getBoolean("remindOn", d.remindOn),
        )
    }

    fun saveNotify(ctx: Context, s: Settings) {
        sp(ctx).edit()
            .putBoolean("notifyChanged", s.notifyChanged)
            .putBoolean("notifyTomorrow", s.notifyTomorrow)
            .putBoolean("lead60", s.lead60)
            .putBoolean("lead30", s.lead30)
            .putBoolean("lead15", s.lead15)
            .putBoolean("atChange", s.atChange)
            .putBoolean("remindOff", s.remindOff)
            .putBoolean("remindOn", s.remindOn)
            .apply()
    }

    fun saveAppearance(ctx: Context, dark: Float, light: Float, textScale: Float) {
        sp(ctx).edit()
            .putFloat("darkAlpha", dark)
            .putFloat("lightAlpha", light)
            .putFloat("textScale", textScale)
            .apply()
    }

    // ---- Places ----

    @Synchronized
    fun places(ctx: Context): List<Place> {
        val p = sp(ctx)
        val json = p.getString("places", null)
        if (json != null) {
            return try {
                PlaceJson.fromJson(json)
            } catch (e: Exception) {
                emptyList()
            }
        }
        // First start of a version with several places: carry the single old place over.
        val migrated = legacyPlace(ctx)
        val list = if (migrated != null) listOf(migrated) else emptyList()
        p.edit().putString("places", PlaceJson.toJson(list)).apply()
        return list
    }

    fun place(ctx: Context, id: String?): Place? = if (id == null) null else places(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun savePlace(ctx: Context, place: Place) {
        val list = places(ctx).toMutableList()
        val index = list.indexOfFirst { it.id == place.id }
        if (index >= 0) list[index] = place else list.add(place)
        sp(ctx).edit().putString("places", PlaceJson.toJson(list)).apply()
    }

    /** Removes the place together with its stored schedule and every widget that showed it. */
    @Synchronized
    fun deletePlace(ctx: Context, id: String) {
        val list = places(ctx).filter { it.id != id }
        val p = sp(ctx)
        val edit = p.edit().putString("places", PlaceJson.toJson(list))
        edit.remove(cacheKey(id)).remove(attemptKey(id)).remove(errorKey(id))
        for (key in p.all.keys) {
            if (key.startsWith("widget_") && p.getString(key, null) == id) edit.remove(key)
        }
        edit.apply()
    }

    @Synchronized
    fun saveDetectedGroup(ctx: Context, id: String, group: String) {
        val current = place(ctx, id) ?: return
        if (current.detectedGroup != group) savePlace(ctx, current.copy(detectedGroup = group))
    }

    private fun legacyPlace(ctx: Context): Place? {
        val p = sp(ctx)
        val d = Place(id = MAIN)
        val street = p.getString("street", d.street) ?: d.street
        val manual = p.getString("manualGroup", "") ?: ""
        val detected = p.getString("detectedGroup", "") ?: ""
        if (street.isBlank() && manual.isBlank() && detected.isBlank()) return null
        return Place(
            id = MAIN,
            name = p.getString("name", d.name) ?: d.name,
            city = p.getString("city", d.city) ?: d.city,
            street = street,
            house = p.getString("house", d.house) ?: d.house,
            // Older versions had no switch: a hand-picked group simply overrode the address.
            byGroup = if (p.contains("byGroup")) p.getBoolean("byGroup", false) else manual.isNotBlank(),
            manualGroup = manual,
            detectedGroup = detected,
        )
    }

    // ---- Widgets: which place each one shows ----

    fun widgetPlaceId(ctx: Context, appWidgetId: Int): String? = sp(ctx).getString("widget_$appWidgetId", null)

    fun assignWidget(ctx: Context, appWidgetId: Int, placeId: String) {
        sp(ctx).edit().putString("widget_$appWidgetId", placeId).apply()
    }

    fun forgetWidget(ctx: Context, appWidgetId: Int) {
        sp(ctx).edit().remove("widget_$appWidgetId").remove("tomorrow_$appWidgetId").apply()
    }

    /** Whether this widget currently shows tomorrow instead of today (the choice lasts until midnight). */
    fun isTomorrowShown(ctx: Context, appWidgetId: Int, today: java.time.LocalDate): Boolean =
        sp(ctx).getString("tomorrow_$appWidgetId", null) == today.toString()

    fun toggleTomorrow(ctx: Context, appWidgetId: Int, today: java.time.LocalDate) {
        val edit = sp(ctx).edit()
        if (isTomorrowShown(ctx, appWidgetId, today)) {
            edit.remove("tomorrow_$appWidgetId")
        } else {
            edit.putString("tomorrow_$appWidgetId", today.toString())
        }
        edit.apply()
    }

    /** The place of a widget; widgets from before several places existed show the first one. */
    fun placeForWidget(ctx: Context, appWidgetId: Int): Place? =
        place(ctx, widgetPlaceId(ctx, appWidgetId)) ?: places(ctx).firstOrNull()

    /** "Add widget" pressed in a place's editor: the next widget that is added shows that place. */
    fun setPendingWidgetPlace(ctx: Context, placeId: String?) {
        val edit = sp(ctx).edit()
        if (placeId == null) {
            edit.remove("pendingWidgetPlace").remove("pendingWidgetAt")
        } else {
            edit.putString("pendingWidgetPlace", placeId).putLong("pendingWidgetAt", System.currentTimeMillis())
        }
        edit.apply()
    }

    fun takePendingWidgetPlace(ctx: Context): String? {
        val p = sp(ctx)
        val id = p.getString("pendingWidgetPlace", null)
        val at = p.getLong("pendingWidgetAt", 0L)
        setPendingWidgetPlace(ctx, null)
        return if (id != null && System.currentTimeMillis() - at < PENDING_TTL_MS) id else null
    }

    // ---- Per-place schedule and refresh status ----

    private fun cacheKey(id: String) = if (id == MAIN) "cache" else "cache_$id"
    private fun attemptKey(id: String) = if (id == MAIN) "lastAttempt" else "lastAttempt_$id"
    private fun errorKey(id: String) = if (id == MAIN) "lastError" else "lastError_$id"

    fun loadCache(ctx: Context, placeId: String): Cache? {
        val json = sp(ctx).getString(cacheKey(placeId), null) ?: return null
        return try {
            CacheJson.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun saveCache(ctx: Context, placeId: String, cache: Cache) {
        sp(ctx).edit().putString(cacheKey(placeId), CacheJson.toJson(cache)).apply()
    }

    fun loadStatus(ctx: Context, placeId: String): Status {
        val p = sp(ctx)
        return Status(p.getLong(attemptKey(placeId), 0L), p.getString(errorKey(placeId), null))
    }

    fun saveStatus(ctx: Context, placeId: String, attemptMillis: Long, error: String?) {
        sp(ctx).edit().putLong(attemptKey(placeId), attemptMillis).putString(errorKey(placeId), error).apply()
    }

    // ---- Reminders ----

    /** Reminders whose time is at or before this moment (epoch millis) were already shown or skipped. */
    fun loadHandledUntil(ctx: Context): Long = sp(ctx).getLong("handledUntil", 0L)

    fun saveHandledUntil(ctx: Context, millis: Long) {
        sp(ctx).edit().putLong("handledUntil", millis).apply()
    }
}
