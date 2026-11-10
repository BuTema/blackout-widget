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
import androidx.glance.layout.Box
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

/** [slots] half-hour slots in a row with the same state. */
private class Run(val on: Boolean, val slots: Int)

/** One day as a line: today or tomorrow, with the half-hour slot of "now" (today only). */
private class Bar(val label: String, val runs: List<Run>, val nowSlot: Int?)

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
    /** Short status for the smallest widget, e.g. "Є світло". */
    val statusShort: String? = null,
    /** Today's and (when published) tomorrow's schedule as lines, for the wide widget. */
    val bars: List<Bar> = emptyList(),
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
        val statusShort = statusText?.let {
            context.getString(if (statusOn) R.string.state_on_short else R.string.state_off_short)
        }
        val bars = ArrayList<Bar>()
        if (cache != null) {
            for ((offset, label) in listOf(0 to R.string.tab_today, 1 to R.string.tab_tomorrow)) {
                val hours = cache.days[now.toLocalDate().plusDays(offset.toLong())] ?: continue
                val runs = ScheduleLogic.runs(ScheduleLogic.slotsOf(hours)).map { Run(it.first, it.second) }
                val nowSlot = if (offset == 0) now.hour * 2 + now.minute / 30 else null
                bars.add(Bar(context.getString(label), runs, nowSlot))
            }
        }
        return Ui(
            place.id, name, updated, statusText, statusOn, first, second, message, messageOn,
            settings.darkAlpha, settings.lightAlpha, settings.textScale, statusShort, bars,
        )
    }
}

/** One widget that adapts to the size the person drags it to: tiny, square (2x2) or wide (4x2 and up). */
@Composable
private fun WidgetContent(ui: Ui) {
    val width = LocalSize.current.width
    when {
        width < 100.dp -> TinyContent(ui)
        width >= 240.dp -> WideContent(ui)
        else -> SquareContent(ui)
    }
}

@Composable
private fun SquareContent(ui: Ui) {
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

private class Colors(
    val background: androidx.glance.unit.ColorProvider,
    val text: androidx.glance.unit.ColorProvider,
    val muted: androidx.glance.unit.ColorProvider,
    val on: androidx.glance.unit.ColorProvider,
    val off: androidx.glance.unit.ColorProvider,
    val good: androidx.glance.unit.ColorProvider,
    val bad: androidx.glance.unit.ColorProvider,
)

private fun colorsFor(ui: Ui) = Colors(
    background = ColorProvider(
        day = Palette.bgLight.copy(alpha = ui.lightAlpha),
        night = Palette.bgDark.copy(alpha = ui.darkAlpha),
    ),
    text = ColorProvider(day = Palette.textLight, night = Palette.textDark),
    muted = ColorProvider(day = Palette.mutedLight, night = Palette.mutedDark),
    on = ColorProvider(day = Palette.onLight, night = Palette.onDark),
    off = ColorProvider(day = Palette.offLight, night = Palette.offDark),
    good = ColorProvider(day = Palette.goodLight, night = Palette.goodDark),
    bad = ColorProvider(day = Palette.badLight, night = Palette.badDark),
)

/** 1x1 and narrow tall widgets: only the state and the current stretch of time. */
@Composable
private fun TinyContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 6.dp
    val inner = (size.width - pad * 2).value.coerceAtLeast(30f)
    val showName = size.height >= 100.dp
    val statusSize = (inner / 5.2f).coerceIn(9f, 16f)
    // "06:30" is about 2.9 character widths per point of font size; two of them are stacked.
    val fitWidth = inner / 3.0f
    val heightLeft = size.height.value - pad.value * 2 - statusSize * 1.3f - (if (showName) 18f else 0f)
    val fitHeight = heightLeft / (2 * 1.25f)
    val timeSize = minOf(fitWidth * ui.textScale, maxOf(fitHeight, 11f)).coerceIn(11f, 32f).sp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(18.dp)
            .padding(pad)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(PlaceKey to (ui.placeId ?: "")))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showName) {
            Text(
                text = ui.name,
                maxLines = 1,
                style = TextStyle(color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }
        if (ui.statusShort != null) {
            Text(
                text = ui.statusShort,
                maxLines = 1,
                style = TextStyle(
                    color = if (ui.statusOn) c.good else c.bad,
                    fontSize = statusSize.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        val first = ui.first
        if (first != null) {
            val color = if (first.on) c.on else c.off
            val style = TextStyle(color = color, fontSize = timeSize, fontWeight = FontWeight.Bold)
            Text(text = first.from, maxLines = 1, style = style)
            Text(text = first.to, maxLines = 1, style = style)
        } else if (ui.message != null) {
            Text(
                text = ui.message,
                maxLines = 2,
                style = TextStyle(
                    color = if (ui.messageOn) c.on else c.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

/** 4x2 and bigger: the state, the current stretch, and today (and tomorrow) as colored lines. */
@Composable
private fun WideContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 10.dp
    val inner = (size.width - pad * 2).value
    val h = size.height.value
    val labelWidth = 46f
    val barWidth = (inner - labelWidth).coerceAtLeast(60f)
    val thick = if (h >= 170f) 22f else 16f
    val thin = if (h >= 170f) 14f else 10f
    val showTomorrow = h >= 125f && ui.bars.size > 1
    val showHours = h >= 100f && ui.bars.isNotEmpty()
    val rangeSize = (if (h >= 130f) 22f else 18f) * ui.textScale.coerceIn(0.8f, 1.3f)
    val showNext = size.width >= 300.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(22.dp)
            .padding(pad)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = GlanceModifier.clickable(
                    actionStartActivity<MainActivity>(actionParametersOf(PlaceKey to (ui.placeId ?: ""))),
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_home),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(c.muted),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = ui.name,
                    maxLines = 1,
                    style = TextStyle(color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = ui.updated,
                maxLines = 1,
                style = TextStyle(color = c.muted, fontSize = 10.sp),
            )
        }
        Spacer(modifier = GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.status != null) {
                Text(
                    text = ui.status,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (ui.statusOn) c.good else c.bad,
                        fontSize = (rangeSize * 0.8f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = GlanceModifier.width(10.dp))
            }
            val first = ui.first
            if (first != null) {
                Text(
                    text = first.from + "–" + first.to,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (first.on) c.on else c.off,
                        fontSize = rangeSize.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            } else if (ui.message != null) {
                Text(
                    text = ui.message,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (ui.messageOn) c.on else c.muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            val second = ui.second
            if (showNext && second != null) {
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "→ " + second.from + "–" + second.to,
                    maxLines = 1,
                    style = TextStyle(color = c.muted, fontSize = 12.sp),
                )
            }
        }
        Spacer(modifier = GlanceModifier.defaultWeight())

        for ((index, bar) in ui.bars.withIndex()) {
            if (index == 1 && !showTomorrow) break
            if (index > 0) Spacer(modifier = GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = GlanceModifier.width(labelWidth.dp)) {
                    Text(
                        text = bar.label,
                        maxLines = 1,
                        style = TextStyle(color = c.muted, fontSize = 9.sp),
                    )
                }
                TimelineBar(bar, barWidth, if (index == 0) thick else thin, c)
            }
        }
        if (showHours) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            Row {
                Spacer(modifier = GlanceModifier.width(labelWidth.dp))
                for (label in listOf("0", "6", "12", "18")) {
                    Box(modifier = GlanceModifier.width((barWidth / 4f).dp)) {
                        Text(text = label, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
                    }
                }
            }
        }
    }
}

/**
 * A day as one line, 48 half-hour slots wide: green = power on, red = off, with a thin marker at the
 * current time. The segments are laid out as plain colored blocks, so the line scales with the widget.
 */
@Composable
private fun TimelineBar(bar: Bar, totalWidth: Float, height: Float, c: Colors) {
    val markerWidth = 2f
    val unit = (totalWidth - markerWidth) / 48f
    Row(modifier = GlanceModifier.height(height.dp)) {
        var start = 0
        for (run in bar.runs) {
            val end = start + run.slots
            val color = if (run.on) c.good else c.bad
            val now = bar.nowSlot
            if (now != null && now >= start && now < end) {
                if (now > start) Segment(((now - start) * unit), height, color)
                Segment(markerWidth, height, c.text)
                if (end > now) Segment(((end - now) * unit), height, color)
            } else {
                Segment(run.slots * unit, height, color)
            }
            start = end
        }
        // Lines without a marker keep the same width as the one with it.
        if (bar.nowSlot == null) Segment(markerWidth, height, c.background)
    }
}

@Composable
private fun Segment(width: Float, height: Float, color: androidx.glance.unit.ColorProvider) {
    Spacer(modifier = GlanceModifier.width(width.dp).height(height.dp).background(color))
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
