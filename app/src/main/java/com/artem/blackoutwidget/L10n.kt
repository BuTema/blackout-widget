package com.artem.blackoutwidget

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The language of the app is the person's own choice (Ukrainian, Russian or English), never the system's: a
 * phone set to another language does not change it. Until a choice is made the app is Ukrainian.
 */
object L10n {
    val SUPPORTED = listOf("uk", "ru", "en")
    private const val DEFAULT = "uk"

    /** The language in use right now; changed by [set] so the application context follows at once. */
    @Volatile
    var active: String = DEFAULT
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("language", Context.MODE_PRIVATE)

    /** True once the person has picked a language (the first-run screen asks for it). */
    fun isChosen(ctx: Context): Boolean = prefs(ctx).getString("lang", null) in SUPPORTED

    fun current(ctx: Context): String = prefs(ctx).getString("lang", null)?.takeIf { it in SUPPORTED } ?: DEFAULT

    fun set(ctx: Context, language: String) {
        require(language in SUPPORTED)
        prefs(ctx).edit().putString("lang", language).commit()
        active = language
        Locale.setDefault(Locale.forLanguageTag(language))
    }

    /** The context with the chosen language; used for the application and for every screen. */
    fun wrap(base: Context): Context {
        val language = current(base)
        active = language
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Resources for [language], built on top of [base]. */
    fun resourcesFor(base: Context, language: String): Resources {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(config).resources
    }

    fun nameOf(language: String): Int = when (language) {
        "ru" -> R.string.lang_ru
        "en" -> R.string.lang_en
        else -> R.string.lang_uk
    }
}

/** Keeps the application context in the chosen language, also after it is changed while the app is running. */
class App : Application() {
    private var startLanguage = ""
    private var other: Resources? = null
    private var otherFor = ""

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(L10n.wrap(base))
        startLanguage = L10n.active
    }

    override fun getResources(): Resources {
        val base = baseContext ?: return super.getResources()
        val language = L10n.active
        if (language == startLanguage) return super.getResources()
        if (other == null || otherFor != language) {
            other = L10n.resourcesFor(base, language)
            otherFor = language
        }
        return other!!
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
