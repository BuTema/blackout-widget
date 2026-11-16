package com.artem.blackoutwidget

import android.content.Context

/** Google Play build: updates come from Google Play only, so the app neither checks nor installs by itself. */
object AppUpdater {
    const val enabled = false
    suspend fun check(ctx: Context): UpdateInfo? = null
    fun dismiss(ctx: Context, info: UpdateInfo) {}
    fun install(ctx: Context, info: UpdateInfo) {}
}
