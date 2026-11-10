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
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import java.time.LocalDateTime

/** One period: "12:00" to "16:00" with an optional caption such as "Power will be on". */
private class Line(val from: String, val to: String, val caption: String, val on: Boolean)

/** Everything the widget draws, already resolved to strings, so drawing is trivial. */
private class Ui(
    /** The place shown; tapping the name opens the app on it. Null when no place exists yet. */
    val placeId: String?,
    val name: String,
    val updated: String,
    /** "Power ON" / "Power OFF" right after the update time; null when there is no schedule. */
    val status: String?,
    val statusOn: Boolean,
    val first: Line?,
    val second: Line?,
    val message: String?,
    val messageOn: Boolean,
    val darkAlpha: Float,
    val lightAlpha: Float,
    val textScale: Float,
)

private object Palette {
    val bgDark = Color(0xFF1C1C1E)
    val bgLight = Color(0xFFF2F2F7)

    val textDark = Color.White
    val textLight = Color(0xFF1C1C1E)

    val mutedDark = Color(0xFF8E8E93)
    val mutedLight = Color(0xFF6B6B70)

    // Period times: orange = power on, gray = power off, as in the reference widget.
    val onDark = Color(0xFFFF9F0A)
    val onLight = Color(0xFFE08600)
    val offDark = Color(0xFFB0B0B5)
    val offLight = Color(0xFF6B6B70)

    // The status line right below the update time: green = power is on now, red = off.
    val goodDark = Color(0xFF4CD27A)
    val goodLight = Color(0xFF1F8A3C)
    val badDark = Color(0xFFFF6B5F)
    val badLight = Color(0xFFC62828)
}

private val PlaceKey = ActionParameters.Key<String>(MainActivity.EXTRA_PLACE)

class ScheduleWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val ui = buildUi(context, appWidgetId)
        provideContent { WidgetContent(ui) }
    }

    private fun buildUi(context: Context, appWidgetId: Int): Ui {
        val settings = Prefs.load(context)
        val place = Prefs.placeForWidget(context, appWidgetId)
        if (place == null) {
            return Ui(
                null, context.getString(R.string.default_home_name), "", null, false, null, null,
                context.getString(R.string.err_not_configured), false,
                settings.darkAlpha, settings.lightAlpha, settings.textScale,
            )
        }
        val cache = Prefs.loadCache(context, place.id)
        val status = Prefs.loadStatus(context, place.id)
        val now = LocalDateTime.now(ScheduleLogic.ZONE)

        val name = place.name.ifBlank { context.getString(R.string.default_home_name) }
        val updated = UpdatedText.format(context, cache?.updatedAtMillis, status.lastError != null)

        val state = if (cache == null) WidgetState.NoData else ScheduleLogic.state(cache.days, now)

        var statusText: String? = null
        var statusOn = false
        var first: Line? = null
        var second: Line? = null
        var message: String? = null
        var messageOn = false
        when (state) {
            WidgetState.NoData -> message = context.getString(R.string.no_data)
            WidgetState.AllDayOn -> {
                statusText = context.getString(R.string.state_on)
                statusOn = true
                message = context.getString(R.string.all_day_on)
                messageOn = true
            }
            is WidgetState.Periods -> {
                val cur = state.current
                statusText = context.getString(if (cur.on) R.string.state_on else R.string.state_off)
                statusOn = cur.on
                // The status line already says what the current period is, so it needs no caption.
                first = Line(ScheduleLogic.hm(cur.start), ScheduleLogic.hm(cur.end), "", cur.on)
                val next = state.next
                if (next != null) {
                    var caption = context.getString(if (next.on) R.string.power_on_next else R.string.power_off_next)
                    if (next.start.toLocalDate() != now.toLocalDate()) caption += context.getString(R.string.tomorrow_suffix)
                    second = Line(ScheduleLogic.hm(next.start), ScheduleLogic.hm(next.end), caption, next.on)
                }
            }
        }
        return Ui(
            place.id, name, updated, statusText, statusOn, first, second, message, messageOn,
            settings.darkAlpha, settings.lightAlpha, settings.textScale,
        )
    }
}

@Composable
private fun WidgetContent(ui: Ui) {
    val size = LocalSize.current
    val pad = 10.dp
    val innerWidth = (size.width - pad * 2).value.coerceAtLeast(60f)
    val tight = size.width < 160.dp
    val statusSize = (innerWidth / 7.6f).coerceIn(14f, 26f)
    val tall = size.height >= 150.dp
    val nameSize = if (tall) 17.sp else 15.sp
    val captionSize = if (tall) 12.sp else 11.sp
    val showNextCaption = size.height >= 130.dp

    // "06:30–13:30" is about 5.7 character widths of bold text per point of font size (6.3 with spaces
    // around the dash), so this is the largest size that still fits the width. The person's size
    // setting scales it; the height of the widget caps it so two periods never run out of room.
    val fitWidth = innerWidth / (if (tight) 5.7f else 6.3f)
    val lines = if (ui.second != null) 2 else 1
    val headerHeight = 22f + 13f + (if (ui.status != null) statusSize * 1.25f + 6f else 0f)
    val captionHeight = if (ui.second != null && showNextCaption) captionSize.value * 1.25f else 0f
    val heightLeft = size.height.value - pad.value * 2 - headerHeight - captionHeight - 6f
    val fitHeight = heightLeft / (lines * 1.25f)
    val timeSize = minOf(fitWidth * ui.textScale, maxOf(fitHeight, 12f)).coerceIn(12f, 44f).sp

    val background = ColorProvider(
        day = Palette.bgLight.copy(alpha = ui.lightAlpha),
        night = Palette.bgDark.copy(alpha = ui.darkAlpha),
    )
    val text = ColorProvider(day = Palette.textLight, night = Palette.textDark)
    val muted = ColorProvider(day = Palette.mutedLight, night = Palette.mutedDark)
    val on = ColorProvider(day = Palette.onLight, night = Palette.onDark)
    val off = ColorProvider(day = Palette.offLight, night = Palette.offDark)
    val good = ColorProvider(day = Palette.goodLight, night = Palette.goodDark)
    val bad = ColorProvider(day = Palette.badLight, night = Palette.badDark)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(22.dp)
            .padding(pad)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(
                // The key name becomes the intent extra the app reads to open the right place.
                actionStartActivity<MainActivity>(actionParametersOf(PlaceKey to (ui.placeId ?: ""))),
            ),
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
                style = TextStyle(color = text, fontSize = nameSize, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = ui.updated,
            maxLines = 1,
            style = TextStyle(color = muted, fontSize = 10.sp),
        )
        if (ui.status != null) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = ui.status,
                maxLines = 1,
                style = TextStyle(
                    color = if (ui.statusOn) good else bad,
                    fontSize = statusSize.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())

        if (ui.message != null) {
            Text(
                text = ui.message,
                style = TextStyle(
                    color = if (ui.messageOn) on else muted,
                    fontSize = if (ui.status != null) captionSize else 18.sp,
                    fontWeight = if (ui.status != null) FontWeight.Normal else FontWeight.Bold,
                ),
            )
        }
        if (ui.first != null) {
            LineBlock(ui.first, tight, timeSize, captionSize, true, on, off, muted)
        }
        if (ui.second != null) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            LineBlock(ui.second, tight, timeSize, captionSize, showNextCaption, on, off, muted)
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun LineBlock(
    line: Line,
    tight: Boolean,
    timeSize: androidx.compose.ui.unit.TextUnit,
    captionSize: androidx.compose.ui.unit.TextUnit,
    showCaption: Boolean,
    on: androidx.glance.unit.ColorProvider,
    off: androidx.glance.unit.ColorProvider,
    muted: androidx.glance.unit.ColorProvider,
) {
    // Narrow widgets drop the spaces around the dash so the times can be set larger.
    val range = if (tight) line.from + "–" + line.to else line.from + " - " + line.to
    Column {
        Text(
            text = range,
            maxLines = 1,
            style = TextStyle(
                color = if (line.on) on else off,
                fontSize = timeSize,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (showCaption && line.caption.isNotEmpty()) {
            Text(
                text = line.caption,
                maxLines = 1,
                style = TextStyle(color = muted, fontSize = captionSize),
            )
        }
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) Prefs.forgetWidget(context, id)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Without a widget the background refresh is still needed while notifications are switched on.
        if (!Prefs.load(context).anyNotification) Scheduler.cancel(context)
    }
}
