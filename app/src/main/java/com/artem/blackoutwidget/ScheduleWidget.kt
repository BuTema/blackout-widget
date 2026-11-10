package com.artem.blackoutwidget

import android.appwidget.AppWidgetManager
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
import java.util.Locale

// The widget adapts to the size it is dragged to: tiny (1x1, 1x2), square (2x2, 2x3) and wide (4x2 and up).
// Glance containers hold at most 10 children, so long rows (the 48 pills) are built from groups.

/** One period: "12:00" to "16:00" with an optional caption such as "Power will be on". */
private class Line(val from: String, val to: String, val caption: String, val on: Boolean)

/** One day: its periods, its 48 half-hour slots (true = power on) and, for today, the slot of "now". */
private class DayView(
    val label: String,
    val lines: List<Line>,
    val slots: BooleanArray,
    val nowSlot: Int?,
    /** Index in [lines] of the period that is on now, or -1. */
    val currentIndex: Int,
    val allDayOn: Boolean,
)

/** Everything the widget draws, already resolved to strings, so drawing is trivial. */
private class Ui(
    val appWidgetId: Int,
    /** The place shown; tapping the name opens the app on it. Null when none is chosen yet. */
    val placeId: String?,
    val name: String,
    val updated: String,
    /** Several places exist and this widget has none yet: show a "tap to choose" prompt. */
    val prompt: String?,
    /** The period that is going on now, and the one after it (possibly tomorrow's). */
    val current: Line?,
    val next: Line?,
    val message: String?,
    val messageOn: Boolean,
    val days: List<DayView>,
    val showTomorrow: Boolean,
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

    // Orange (yellow) = power on, gray = power off, for period times and for the pills alike.
    val onDark = Color(0xFFFF9F0A)
    val onLight = Color(0xFFE08600)
    val offDark = Color(0xFFB0B0B5)
    val offLight = Color(0xFF6B6B70)
    val pillOffDark = Color(0xFF5C5C61)
    val pillOffLight = Color(0xFFC4C4C9)

    val chipDark = Color(0x33FFFFFF)
    val chipLight = Color(0x1F000000)
}

private class Colors(
    val background: androidx.glance.unit.ColorProvider,
    val text: androidx.glance.unit.ColorProvider,
    val muted: androidx.glance.unit.ColorProvider,
    val on: androidx.glance.unit.ColorProvider,
    val off: androidx.glance.unit.ColorProvider,
    val pillOff: androidx.glance.unit.ColorProvider,
    val chip: androidx.glance.unit.ColorProvider,
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
    pillOff = ColorProvider(day = Palette.pillOffLight, night = Palette.pillOffDark),
    chip = ColorProvider(day = Palette.chipLight, night = Palette.chipDark),
)

private val PlaceKey = ActionParameters.Key<String>(MainActivity.EXTRA_PLACE)
private val WidgetIdKey = ActionParameters.Key<Int>(AppWidgetManager.EXTRA_APPWIDGET_ID)

class ScheduleWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val ui = buildUi(context, appWidgetId)
        provideContent { WidgetContent(ui) }
    }

    private fun resolvePlace(context: Context, appWidgetId: Int): Place? {
        Prefs.place(context, Prefs.widgetPlaceId(context, appWidgetId))?.let { return it }
        val places = Prefs.places(context)
        // The person pressed "add widget" in a place's editor and the launcher skipped the chooser.
        val pending = Prefs.place(context, Prefs.takePendingWidgetPlace(context))
        val chosen = pending ?: places.singleOrNull()
        if (chosen != null) Prefs.assignWidget(context, appWidgetId, chosen.id)
        return chosen
    }

    private fun buildUi(context: Context, appWidgetId: Int): Ui {
        val settings = Prefs.load(context)
        val place = resolvePlace(context, appWidgetId)
        val defaultName = context.getString(R.string.default_home_name)
        if (place == null) {
            val none = Prefs.places(context).isEmpty()
            return Ui(
                appWidgetId, null, defaultName, "",
                if (none) null else context.getString(R.string.widget_choose_place),
                null, null,
                if (none) context.getString(R.string.err_not_configured) else null, false,
                emptyList(), false,
                settings.darkAlpha, settings.lightAlpha, settings.textScale,
            )
        }
        val cache = Prefs.loadCache(context, place.id)
        val status = Prefs.loadStatus(context, place.id)
        val now = LocalDateTime.now(ScheduleLogic.ZONE)
        val today = now.toLocalDate()

        val name = place.name.ifBlank { defaultName }
        val updated = UpdatedText.format(context, cache?.updatedAtMillis, status.lastError != null)

        val days = ArrayList<DayView>()
        if (cache != null) {
            for ((offset, label) in listOf(0 to R.string.tab_today, 1 to R.string.tab_tomorrow)) {
                val hours = cache.days[today.plusDays(offset.toLong())] ?: continue
                val nowSlot = if (offset == 0) now.hour * 2 + now.minute / 30 else null
                days.add(dayView(context.getString(label), hours, nowSlot))
            }
        }

        var current: Line? = null
        var next: Line? = null
        var message: String? = null
        var messageOn = false
        when (val state = if (cache == null) WidgetState.NoData else ScheduleLogic.state(cache.days, now)) {
            WidgetState.NoData -> message = context.getString(R.string.no_data)
            WidgetState.AllDayOn -> {
                message = context.getString(R.string.all_day_on)
                messageOn = true
            }
            is WidgetState.Periods -> {
                val cur = state.current
                current = Line(ScheduleLogic.hm(cur.start), ScheduleLogic.hm(cur.end), "", cur.on)
                val nx = state.next
                if (nx != null) {
                    var caption = context.getString(if (nx.on) R.string.power_on_next else R.string.power_off_next)
                    if (nx.start.toLocalDate() != today) caption += context.getString(R.string.tomorrow_suffix)
                    next = Line(ScheduleLogic.hm(nx.start), ScheduleLogic.hm(nx.end), caption, nx.on)
                }
            }
        }
        val showTomorrow = days.size > 1 && Prefs.isTomorrowShown(context, appWidgetId, today)
        return Ui(
            appWidgetId, place.id, name, updated, null, current, next, message, messageOn,
            days, showTomorrow, settings.darkAlpha, settings.lightAlpha, settings.textScale,
        )
    }

    private fun slotTime(slot: Int): String = String.format(Locale.ROOT, "%02d:%02d", (slot / 2) % 24, slot % 2 * 30)

    private fun dayView(label: String, hours: Map<Int, String>, nowSlot: Int?): DayView {
        val slots = ScheduleLogic.slotsOf(hours)
        val runs = ScheduleLogic.runs(slots)
        val lines = ArrayList<Line>()
        var start = 0
        var currentIndex = -1
        for ((i, run) in runs.withIndex()) {
            val end = start + run.second
            lines.add(Line(slotTime(start), slotTime(end), "", run.first))
            if (nowSlot != null && nowSlot >= start && nowSlot < end) currentIndex = i
            start = end
        }
        return DayView(label, lines, slots, nowSlot, currentIndex, runs.size == 1 && runs[0].first)
    }
}

@Composable
private fun WidgetContent(ui: Ui) {
    val size = LocalSize.current
    when {
        ui.prompt != null -> PromptContent(ui)
        size.width < 100.dp -> TinyContent(ui)
        size.width >= 240.dp -> WideContent(ui)
        else -> SquareContent(ui)
    }
}

/** "Tap to choose a place": for widgets that have none yet, when several places exist. */
@Composable
private fun PromptContent(ui: Ui) {
    val c = colorsFor(ui)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(18.dp)
            .padding(8.dp)
            .clickable(actionStartActivity<WidgetConfigActivity>(actionParametersOf(WidgetIdKey to ui.appWidgetId))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ui.prompt ?: "",
            style = TextStyle(color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** 1x1 and narrow tall widgets: only the current stretch of time, as large as it fits. */
@Composable
private fun TinyContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 4.dp
    val inner = (size.width - pad * 2).value.coerceAtLeast(30f)
    val showName = size.height >= 100.dp
    // "06:30" is about 2.9 character widths per point of font size; the two times are stacked.
    val fitWidth = inner / 2.9f
    val heightLeft = size.height.value - pad.value * 2 - (if (showName) 20f else 0f)
    val fitHeight = heightLeft / (2 * 1.2f)
    val timeSize = minOf(fitWidth * ui.textScale, maxOf(fitHeight, 12f)).coerceIn(12f, 44f).sp

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
                style = TextStyle(color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
        val cur = ui.current
        if (cur != null) {
            val style = TextStyle(color = if (cur.on) c.on else c.off, fontSize = timeSize, fontWeight = FontWeight.Bold)
            Text(text = cur.from, maxLines = 1, style = style)
            Text(text = cur.to, maxLines = 1, style = style)
        } else if (ui.message != null) {
            Text(
                text = ui.message,
                maxLines = 3,
                style = TextStyle(
                    color = if (ui.messageOn) c.on else c.muted,
                    fontSize = (timeSize.value * 0.55f).coerceIn(11f, 18f).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/** The name row: home icon, name (opens the app) and, when tomorrow is known, the day switch. */
@Composable
private fun Header(ui: Ui, c: Colors, nameSize: Float, showIcon: Boolean, showSwitch: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.defaultWeight().clickable(
                // The key name becomes the intent extra the app reads to open the right place.
                actionStartActivity<MainActivity>(actionParametersOf(PlaceKey to (ui.placeId ?: ""))),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Image(
                    provider = ImageProvider(R.drawable.ic_home),
                    contentDescription = null,
                    modifier = GlanceModifier.size((nameSize + 3f).dp),
                    colorFilter = ColorFilter.tint(c.muted),
                )
                Spacer(modifier = GlanceModifier.width(5.dp))
            }
            Text(
                text = ui.name,
                maxLines = 1,
                style = TextStyle(color = c.text, fontSize = nameSize.sp, fontWeight = FontWeight.Bold),
            )
        }
        if (showSwitch && ui.days.size > 1) {
            Box(
                modifier = GlanceModifier
                    .background(c.chip)
                    .cornerRadius(12.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clickable(actionRunCallback<ToggleDayAction>()),
            ) {
                Text(
                    text = if (ui.showTomorrow) "‹ " + ui.days[0].label else ui.days[1].label + " ›",
                    maxLines = 1,
                    style = TextStyle(color = c.text, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/** Two lines for 2x2 / 2x3: the current period large, the next below it. Tomorrow lists all its periods. */
@Composable
private fun SquareContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 10.dp
    val innerWidth = (size.width - pad * 2).value.coerceAtLeast(60f)
    val tall = size.height >= 150.dp
    val nameSize = if (tall) 19f else 16f
    val captionSize = if (tall) 12f else 11f
    val showNextCaption = size.height >= 120.dp
    val wide = size.width >= 140.dp

    // "06:30–13:30" is about 5.7 character widths of bold text per point of font size, so this is the
    // largest size that still fits the width. The person's size setting scales it; the height caps it.
    val fitWidth = innerWidth / 5.7f
    val headerHeight = nameSize * 1.3f + 13f
    val lines = if (ui.next != null) 2 else 1
    val captionHeight = if (ui.next != null && showNextCaption) captionSize * 1.25f else 0f
    val heightLeft = size.height.value - pad.value * 2 - headerHeight - captionHeight - 8f
    val fitHeight = heightLeft / (lines * 1.25f)
    val timeSize = minOf(fitWidth * ui.textScale, maxOf(fitHeight, 12f)).coerceIn(12f, 44f)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(22.dp)
            .padding(pad)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Header(ui, c, nameSize, showIcon = wide, showSwitch = true)
        Text(
            text = ui.updated,
            maxLines = 1,
            style = TextStyle(color = c.muted, fontSize = 10.sp),
        )
        Spacer(modifier = GlanceModifier.defaultWeight())

        val day = if (ui.showTomorrow) ui.days.getOrNull(1) else null
        if (day != null) {
            // Tomorrow: every period, as many as fit.
            val lineSize = (timeSize * 0.8f).coerceAtLeast(12f)
            val room = ((size.height.value - pad.value * 2 - headerHeight - 8f) / (lineSize * 1.3f)).toInt()
            Column {
                for (line in day.lines.take(room.coerceIn(1, 8))) {
                    Text(
                        text = line.from + "–" + line.to,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (line.on) c.on else c.off,
                            fontSize = lineSize.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        } else {
            if (ui.message != null) {
                Text(
                    text = ui.message,
                    style = TextStyle(
                        color = if (ui.messageOn) c.on else c.muted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            ui.current?.let { LineBlock(it, timeSize, captionSize, false, c) }
            ui.next?.let {
                Spacer(modifier = GlanceModifier.height(6.dp))
                LineBlock(it, timeSize * 0.9f, captionSize, showNextCaption, c)
            }
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun LineBlock(line: Line, timeSize: Float, captionSize: Float, showCaption: Boolean, c: Colors) {
    Column {
        Text(
            text = line.from + "–" + line.to,
            maxLines = 1,
            style = TextStyle(
                color = if (line.on) c.on else c.off,
                fontSize = timeSize.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (showCaption && line.caption.isNotEmpty()) {
            Text(
                text = line.caption,
                maxLines = 1,
                style = TextStyle(color = c.muted, fontSize = captionSize.sp),
            )
        }
    }
}

/**
 * 4x2 and bigger: the periods of the day and the day as 48 pills. When there is room for both days
 * they are stacked (periods, then the pills); otherwise a switch in the corner flips to tomorrow.
 */
@Composable
private fun WideContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 10f
    val inner = size.width.value - pad * 2
    val h = size.height.value
    val scale = ui.textScale.coerceIn(0.8f, 1.5f)
    val labelWidth = 46f
    val graphWidth = (inner - labelWidth).coerceAtLeast(60f)

    val today = ui.days.getOrNull(0)
    val tomorrow = ui.days.getOrNull(1)

    // How many periods fit in a row, and how many rows a day takes.
    fun periodSize(large: Boolean) = (if (large) 15f else 13f) * scale
    fun perRow(size: Float) = (inner / (11f * size * 0.56f + 10f)).toInt().coerceIn(1, 6)
    fun rowsOf(day: DayView?, size: Float) = if (day == null) 0 else (day.lines.size + perRow(size) - 1) / perRow(size)

    val headerH = 26f
    val bigH = 34f
    val graphH = 32f
    val gap = 6f

    // Both days at once need a lot of height; if there is room, show them (and a big current period).
    val largeSize = periodSize(true)
    val needBoth = pad * 2 + headerH + bigH + gap * 4 +
        rowsOf(today, largeSize) * largeSize * 1.5f + graphH +
        rowsOf(tomorrow, largeSize) * largeSize * 1.5f + graphH
    val both = tomorrow != null && h >= needBoth

    val smallSize = periodSize(false)
    val needBig = pad * 2 + headerH + bigH + gap * 3 + rowsOf(today, smallSize) * smallSize * 1.5f + graphH
    val showBig = both || h >= needBig

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(22.dp)
            .padding(pad.dp)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Header(ui, c, if (both || h >= 150f) 19f else 17f, showIcon = true, showSwitch = !both)
        if (showBig) {
            val cur = ui.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cur != null) {
                    Text(
                        text = cur.from + "–" + cur.to,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (cur.on) c.on else c.off,
                            fontSize = (26f * scale).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                } else if (ui.message != null) {
                    Text(
                        text = ui.message,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (ui.messageOn) c.on else c.muted,
                            fontSize = (20f * scale).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(text = ui.updated, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 10.sp))
            }
        } else {
            Text(text = ui.updated, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 10.sp))
        }
        Spacer(modifier = GlanceModifier.height(gap.dp))

        val shown = if (both) listOf(today, tomorrow) else listOf(if (ui.showTomorrow) tomorrow else today)
        val fontSize = periodSize(both)
        val perRow = perRow(fontSize)
        // Rows of periods that still fit above the graphs.
        val graphsH = shown.size * (graphH + gap)
        val used = pad * 2 + headerH + (if (showBig) bigH else 13f) + gap + graphsH
        val roomRows = ((h - used) / (fontSize * 1.5f)).toInt().coerceAtLeast(1)

        if (!both) {
            val day = shown[0]
            if (day != null) PeriodGrid(day, fontSize, perRow, inner, roomRows, c)
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (day != null) GraphRow(day, graphWidth, labelWidth, c, thick = h >= 150f)
        } else {
            for (day in shown) {
                if (day != null) {
                    // One container per day keeps the root within Glance's limit of 10 children.
                    Column(modifier = GlanceModifier.padding(bottom = gap.dp)) {
                        PeriodGrid(day, fontSize, perRow, inner, 4, c)
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        GraphRow(day, graphWidth, labelWidth, c, thick = true)
                    }
                }
            }
        }
    }
}

/** The periods of a day in rows of equal columns; the one that is on now is larger. */
@Composable
private fun PeriodGrid(day: DayView, fontSize: Float, perRow: Int, inner: Float, maxRows: Int, c: Colors) {
    val cell = inner / perRow
    Column {
        for (row in day.lines.withIndex().toList().chunked(perRow).take(maxRows.coerceIn(1, 8))) {
            Row {
                for ((index, line) in row) {
                    val isCurrent = index == day.currentIndex
                    Box(modifier = GlanceModifier.width(cell.dp)) {
                        Text(
                            text = line.from + "–" + line.to,
                            maxLines = 1,
                            style = TextStyle(
                                color = if (line.on) c.on else c.off,
                                fontSize = (if (isCurrent) fontSize + 3f else fontSize).sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** A day label on the left and the 48 pills (30 minutes each) beside it; hours 0, 6, 12, 18 below. */
@Composable
private fun GraphRow(day: DayView, graphWidth: Float, labelWidth: Float, c: Colors, thick: Boolean) {
    val pillHeight = if (thick) 20f else 16f
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = GlanceModifier.width(labelWidth.dp)) {
                Text(text = day.label, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
            }
            PillRow(day, graphWidth, pillHeight, c)
        }
        Row {
            Spacer(modifier = GlanceModifier.width(labelWidth.dp))
            for (label in listOf("0", "6", "12", "18")) {
                Box(modifier = GlanceModifier.width((graphWidth / 4f).dp)) {
                    Text(text = label, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
                }
            }
        }
    }
}

/** 48 rounded pills: orange = power on for those 30 minutes, gray = off; "now" is drawn in the text color. */
@Composable
private fun PillRow(day: DayView, width: Float, height: Float, c: Colors) {
    val unit = width / 48f
    val gap = (unit * 0.12f).coerceIn(0.4f, 1.2f)
    val radius = minOf(unit / 2f, height / 2f)
    Row {
        for (group in 0 until 6) {
            Row {
                for (k in 0 until 8) {
                    val i = group * 8 + k
                    val color = when {
                        day.nowSlot == i -> c.text
                        day.slots[i] -> c.on
                        else -> c.pillOff
                    }
                    Box(modifier = GlanceModifier.width(unit.dp).height(height.dp).padding(horizontal = gap.dp)) {
                        Spacer(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(color)
                                .cornerRadius(radius.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Tapping the widget body refreshes the data right away. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Scheduler.refreshNow(context)
    }
}

/** The corner switch: today <-> tomorrow, remembered per widget until midnight. */
class ToggleDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        Prefs.toggleTomorrow(context, appWidgetId, LocalDateTime.now(ScheduleLogic.ZONE).toLocalDate())
        ScheduleWidget().update(context, glanceId)
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
