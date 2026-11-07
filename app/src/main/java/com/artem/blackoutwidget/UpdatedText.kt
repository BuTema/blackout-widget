package com.artem.blackoutwidget

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** "Updated at 14:40" for the widget and the app. A trailing mark says the latest refresh failed. */
object UpdatedText {
    fun format(context: Context, updatedMillis: Long?, failed: Boolean): String {
        val base = if (updatedMillis == null) {
            context.getString(R.string.never_updated)
        } else {
            val zone = ZoneId.systemDefault()
            val updatedAt = Instant.ofEpochMilli(updatedMillis).atZone(zone)
            val hm = updatedAt.format(DateTimeFormatter.ofPattern("HH:mm"))
            if (updatedAt.toLocalDate() == ZonedDateTime.now(zone).toLocalDate()) {
                context.getString(R.string.updated_today, hm)
            } else {
                context.getString(R.string.updated_date, updatedAt.format(DateTimeFormatter.ofPattern("dd.MM")), hm)
            }
        }
        return if (failed) "$base ⚠" else base
    }
}
