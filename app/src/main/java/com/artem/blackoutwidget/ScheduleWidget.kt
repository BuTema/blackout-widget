package com.artem.blackoutwidget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One "time range + caption" pair: "12:00 - 16:00" / "Power will be on". */
private class Line(val time: String, val caption: String, val on: Boolean)

/** Everything the widget draws, already resolved to strings, so drawing is trivial. */
private class Ui(
    val name: String,
    val updated: String,
    val first: Line?,
    val second: Line?,
    val message: String?,
    val messageOn: Boolean,
    val darkAlpha: Float,
    val lightAlpha: Float,
)

private object Palette {
    val bgDark = Color(0xFF1C1C1E)
    val bgLight = Color(0xFFF2F2F7)

    val textDark = Color.White
    val textLight = Color(0xFF1C1C1E)

    val mutedDark = Color(0xFF8E8E93)
    val mutedLight = Color(0xFF6B6B70)

    val onDark = Color(0xFFFF9F0A)
    val onLight = Color(0xFFE08600)

    // "Power off" time text: gray, as in the reference widget.
    val offDark = Color(0xFFB0B0B5)
    val offLight = Color(0xFF6B6B70)
}

class ScheduleWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = buildUi(context)
        provideContent { WidgetContent(ui) }
    }

    private fun buildUi(context: Context): Ui {
        val settings = Prefs.load(context)
        val cache = Prefs.loadCache(context)
        val status = Prefs.loadStatus(context)

        val name = settings.name.ifBlank { context.getString(R.string.default_home_name) }
        val updated = updatedText(context, cache?.updatedAtMillis, status.lastError != null)

        val state = if (cache == null) {
            WidgetState.NoData
        } else {
            ScheduleLogic.state(cache.days, LocalDateTime.now(ScheduleLogic.ZONE))
        }

        var first: Line? = null
        var second: Line? = null
        var message: String? = null
        var messageOn = false
        when (state) {
            WidgetState.NoData -> message = context.getString(R.string.no_data)
            WidgetState.AllDayOn -> {
                message = context.getString(R.string.all_day_on)
                messageOn = true
            }
            is WidgetState.Periods -> {
                val cur = state.current
                first = Line(
                    range(cur),
                    context.getString(if (cur.on) R.string.power_on_now else R.string.power_off_now),
                    cur.on,
                )
                val next = state.next
                if (next != null) {
                    val today = LocalDateTime.now(ScheduleLogic.ZONE).toLocalDate()
                    var caption = context.getString(if (next.on) R.string.power_on_next else R.string.power_off_next)
                    if (next.start.toLocalDate() != today) caption += context.getString(R.string.tomorrow_suffix)
                    second = Line(range(next), caption, next.on)
                }
            }
        }
        return Ui(name, updated, first, second, message, messageOn, settings.darkAlpha, settings.lightAlpha)
    }

    private fun range(p: Period): String = ScheduleLogic.hm(p.start) + " - " + ScheduleLogic.hm(p.end)

    private fun updatedText(context: Context, updatedMillis: Long?, failed: Boolean): String =
        UpdatedText.format(context, updatedMillis, failed)
}

@Composable
private fun WidgetContent(ui: Ui) {
    val width = LocalSize.current.width
    val timeSize = if (width >= 150.dp) 20.sp else 16.sp
    val captionSize = if (width >= 150.dp) 12.sp else 11.sp

    val background = ColorProvider(
        day = Palette.bgLight.copy(alpha = ui.lightAlpha),
        night = Palette.bgDark.copy(alpha = ui.darkAlpha),
    )
    val text = ColorProvider(day = Palette.textLight, night = Palette.textDark)
    val muted = ColorProvider(day = Palette.mutedLight, night = Palette.mutedDark)
    val on = ColorProvider(day = Palette.onLight, night = Palette.onDark)
    val off = ColorProvider(day = Palette.offLight, night = Palette.offDark)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(22.dp)
            .padding(14.dp)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_home),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(muted),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = ui.name,
                maxLines = 1,
                style = TextStyle(color = text, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = ui.updated,
            maxLines = 1,
            style = TextStyle(color = muted, fontSize = 10.sp),
        )
        Spacer(modifier = GlanceModifier.defaultWeight())

        if (ui.message != null) {
            Text(
                text = ui.message,
                style = TextStyle(
                    color = if (ui.messageOn) on else muted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        if (ui.first != null) {
            LineBlock(ui.first, timeSize, captionSize, on, off, muted)
        }
        if (ui.second != null) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            LineBlock(ui.second, timeSize, captionSize, on, off, muted)
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun LineBlock(
    line: Line,
    timeSize: androidx.compose.ui.unit.TextUnit,
    captionSize: androidx.compose.ui.unit.TextUnit,
    on: androidx.glance.unit.ColorProvider,
    off: androidx.glance.unit.ColorProvider,
    muted: androidx.glance.unit.ColorProvider,
) {
    Column {
        Text(
            text = line.time,
            maxLines = 1,
            style = TextStyle(
                color = if (line.on) on else off,
                fontSize = timeSize,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = line.caption,
            maxLines = 1,
            style = TextStyle(color = muted, fontSize = captionSize),
        )
    }
}

/** Tapping the widget body refreshes the data right away. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Scheduler.refreshNow(context)
    }
}

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.ensure(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Without a widget the background refresh is still needed while notifications are switched on.
        if (!Prefs.load(context).anyNotification) Scheduler.cancel(context)
    }
}
