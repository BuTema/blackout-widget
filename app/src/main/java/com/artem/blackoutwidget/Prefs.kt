package com.artem.blackoutwidget

import android.content.Context

data class Settings(
    val name: String = "",
    val city: String = "м. Одеса",
    val street: String = "",
    val house: String = "",
    /** "GPV6.2" if the user picked the group by hand, otherwise empty (detected from the address). */
    val manualGroup: String = "",
    /** Group found from the address on an earlier fetch. */
    val detectedGroup: String = "",
    val darkAlpha: Float = 0.85f,
    val lightAlpha: Float = 0.85f,
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
    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context): Settings {
        val p = sp(ctx)
        val d = Settings()
        return Settings(
            name = p.getString("name", d.name) ?: d.name,
            city = p.getString("city", d.city) ?: d.city,
            street = p.getString("street", d.street) ?: d.street,
            house = p.getString("house", d.house) ?: d.house,
            manualGroup = p.getString("manualGroup", d.manualGroup) ?: d.manualGroup,
            detectedGroup = p.getString("detectedGroup", d.detectedGroup) ?: d.detectedGroup,
            darkAlpha = p.getFloat("darkAlpha", d.darkAlpha),
            lightAlpha = p.getFloat("lightAlpha", d.lightAlpha),
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

    fun save(ctx: Context, s: Settings) {
        sp(ctx).edit()
            .putString("name", s.name)
            .putString("city", s.city)
            .putString("street", s.street)
            .putString("house", s.house)
            .putString("manualGroup", s.manualGroup)
            .putString("detectedGroup", s.detectedGroup)
            .putFloat("darkAlpha", s.darkAlpha)
            .putFloat("lightAlpha", s.lightAlpha)
            .apply()
        saveNotify(ctx, s)
    }

    /** Writes only the notification options, so it never overwrites the address or the detected group. */
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

    fun saveAlphas(ctx: Context, dark: Float, light: Float) {
        sp(ctx).edit().putFloat("darkAlpha", dark).putFloat("lightAlpha", light).apply()
    }

    fun saveDetectedGroup(ctx: Context, group: String) {
        sp(ctx).edit().putString("detectedGroup", group).apply()
    }

    fun loadCache(ctx: Context): Cache? {
        val json = sp(ctx).getString("cache", null) ?: return null
        return try {
            CacheJson.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun saveCache(ctx: Context, cache: Cache) {
        sp(ctx).edit().putString("cache", CacheJson.toJson(cache)).apply()
    }

    fun loadStatus(ctx: Context): Status {
        val p = sp(ctx)
        return Status(p.getLong("lastAttempt", 0L), p.getString("lastError", null))
    }

    fun saveStatus(ctx: Context, attemptMillis: Long, error: String?) {
        sp(ctx).edit().putLong("lastAttempt", attemptMillis).putString("lastError", error).apply()
    }

    /** Reminders whose time is at or before this moment (epoch millis) were already shown or skipped. */
    fun loadHandledUntil(ctx: Context): Long = sp(ctx).getLong("handledUntil", 0L)

    fun saveHandledUntil(ctx: Context, millis: Long) {
        sp(ctx).edit().putLong("handledUntil", millis).apply()
    }
}
