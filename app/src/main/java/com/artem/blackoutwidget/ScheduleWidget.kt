package com.artem.blackoutwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
        val initial = buildUi(context, appWidgetId)
        provideContent {
            // Glance keeps this composition running for a while and update() does not rerun provideGlance
            // meanwhile, so the widget follows the stored data itself: a tap on the day switch, a place
            // chosen, a download finished - each is a write to the settings, and the UI is rebuilt at once.
            val flow = remember {
                Prefs.changes(context)
                    .onStart { emit(Unit) }
                    .map { buildUi(context, appWidgetId) }
                    .flowOn(Dispatchers.Default)
            }
            val ui by flow.collectAsState(initial)
            WidgetContent(ui)
        }
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
        size.height < 100.dp -> StripContent(ui)
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

/** Tapping a small widget opens the app on its place. */
private fun openPlace(ui: Ui) = actionStartActivity<MainActivity>(actionParametersOf(PlaceKey to (ui.placeId ?: "")))

/** The text shown instead of periods: "no outages today", "no schedule yet". */
@Composable
private fun MessageText(ui: Ui, c: Colors, size: Float) {
    Text(
        text = ui.message ?: "",
        maxLines = 3,
        style = TextStyle(
            color = if (ui.messageOn) c.on else c.muted,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/**
 * One cell wide (1x1 ... 1x5): the periods as stacked pairs of times, as large as the width allows.
 * A tall widget lists as many periods as fit, starting with the current one.
 */
@Composable
private fun TinyContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 4f
    val h = size.height.value
    val inner = (size.width.value - pad * 2).coerceAtLeast(30f)
    val showName = h >= 100f
    val nameH = if (showName) 20f else 0f
    val avail = h - pad * 2 - nameH
    // "06:30" is about 2.9 character widths of bold text per point; one period is two stacked times.
    val byWidth = inner * FIT / 2.9f * ui.textScale
    val byHeight = avail / (2 * 1.2f + 0.3f)
    val font = minOf(byWidth, maxOf(byHeight, 12f)).coerceIn(12f, 44f)
    val blockH = font * 2 * 1.2f + 8f
    val capacity = (avail / blockH).toInt().coerceIn(1, 8)

    val day = ui.days.getOrNull(0)
    val start = day?.currentIndex?.takeIf { it >= 0 } ?: 0
    val lines: List<Line> = when {
        ui.current == null -> emptyList()
        day != null && day.currentIndex >= 0 -> day.lines.drop(start).take(capacity)
        else -> listOfNotNull(ui.current, ui.next).take(capacity)
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(18.dp)
            .padding(pad.dp)
            .clickable(openPlace(ui)),
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
        if (lines.isEmpty()) {
            if (ui.message != null) MessageText(ui, c, (font * 0.55f).coerceIn(11f, 18f))
        } else {
            for ((i, line) in lines.withIndex()) {
                val style = TextStyle(
                    color = if (line.on) c.on else c.off,
                    fontSize = (if (i == 0) font else font * 0.88f).sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = GlanceModifier.padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = line.from, maxLines = 1, style = style)
                    Text(text = line.to, maxLines = 1, style = style)
                }
            }
        }
    }
}

/**
 * One cell tall (2x1, 3x1, 4x1 and wider): everything is set in a single row, so the text is large and
 * nothing overlaps. Narrow: name above, current and next below. Medium: periods left, name right.
 * Wide: name and update time, current and next side by side, and a thin strip of the 48 pills.
 */
@Composable
private fun StripContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 6f
    val w = size.width.value
    val h = size.height.value
    val innerW = w - pad * 2
    val innerH = h - pad * 2
    val shrink = if (ui.textScale < 1f) ui.textScale else 1f
    val cur = ui.current
    val next = ui.next

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(20.dp)
            .padding(pad.dp)
            .clickable(openPlace(ui)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cur == null) {
            Text(text = ui.name, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            if (ui.message != null) MessageText(ui, c, (innerH * 0.4f).coerceIn(12f, 22f))
            return@Column
        }
        val curStyle = { f: Float -> TextStyle(color = if (cur.on) c.on else c.off, fontSize = f.sp, fontWeight = FontWeight.Bold) }
        val nextStyle = { f: Float ->
            TextStyle(color = if (next?.on == true) c.on else c.off, fontSize = f.sp, fontWeight = FontWeight.Bold)
        }
        when {
            w < 170f -> {
                val f = (minOf(innerW / 5.6f, (innerH - 16f) / (1.25f * 1.8f), 40f) * shrink).coerceAtLeast(11f)
                Text(text = ui.name, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                Text(text = cur.from + "–" + cur.to, maxLines = 1, style = curStyle(f))
                if (next != null) Text(text = next.from + "–" + next.to, maxLines = 1, style = nextStyle(f * 0.8f))
            }
            w < 240f -> {
                val rightW = minOf(innerW * 0.32f, 84f)
                val f = (minOf((innerW - rightW) / 5.6f, innerH / (1.25f * 1.8f), 40f) * shrink).coerceAtLeast(11f)
                Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = cur.from + "–" + cur.to, maxLines = 1, style = curStyle(f))
                        if (next != null) Text(text = next.from + "–" + next.to, maxLines = 1, style = nextStyle(f * 0.8f))
                    }
                    Column(modifier = GlanceModifier.width(rightW.dp), horizontalAlignment = Alignment.End) {
                        Text(text = ui.name, maxLines = 1, style = TextStyle(color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        if (innerH >= 52f) {
                            Text(text = ui.updated, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
                        }
                    }
                }
            }
            else -> {
                val day = ui.days.getOrNull(0)
                val graphH = if (day != null) 10f else 0f
                val availH = innerH - 14f - graphH - 6f
                val f = (minOf((innerW / 2f - 4f) / 5.6f, availH / 1.25f, 40f) * shrink).coerceAtLeast(11f)
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = ui.name, maxLines = 1, style = TextStyle(color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }
                    Text(text = ui.updated, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 10.sp))
                }
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = cur.from + "–" + cur.to, maxLines = 1, style = curStyle(f))
                    }
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        if (next != null) Text(text = next.from + "–" + next.to, maxLines = 1, style = nextStyle(f * 0.85f))
                    }
                }
                if (day != null) PillRow(day, graphH, c, GlanceModifier.fillMaxWidth())
            }
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

/**
 * The size the launcher reports is the minimum of what the widget is given, and the real widget is
 * usually a little larger, so sizes are fitted to this much more than the reported size.
 */
private const val FIT = 1.15f

/** Two lines for 2x2 / 2x3: the current period large, the next below it. Tomorrow lists all its periods. */
@Composable
private fun SquareContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = if (size.height < 120.dp) 8.dp else 10.dp
    val h = size.height.value
    val innerWidth = (size.width - pad * 2).value.coerceAtLeast(60f)
    val nameSize = when {
        h >= 150f -> 19f
        h >= 110f -> 16f
        else -> 14f
    }
    val showUpdated = h >= 135f
    val captionSize = if (h >= 150f) 12f else 11f
    val showNextCaption = h >= 150f
    val wide = size.width >= 140.dp
    // Only widgets bigger than 2x2 carry the today/tomorrow switch; a 2x2 shows what is going on now.
    val canSwitch = size.height >= 160.dp || size.width >= 170.dp
    val showTomorrow = canSwitch && ui.showTomorrow

    // "06:30–13:30" is about 5.1 character widths of bold text per point of font size, so this is the
    // largest size that still fits the width. The person's size setting scales it; the height caps it.
    val fitWidth = innerWidth / 5.6f
    val headerHeight = nameSize * 1.3f + (if (showUpdated) 13f else 0f)
    val lines = if (ui.next != null) 2 else 1
    val captionHeight = if (ui.next != null && showNextCaption) captionSize * 1.25f else 0f
    val heightLeft = h * FIT - pad.value * 2 - headerHeight - captionHeight - 8f
    val fitHeight = heightLeft / (lines * 1.2f)
    val timeSize = minOf(fitWidth * ui.textScale, maxOf(fitHeight, 12f)).coerceIn(12f, 48f)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(22.dp)
            .padding(pad)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Header(ui, c, nameSize, showIcon = wide && h >= 110f, showSwitch = canSwitch)
        if (showUpdated) {
            Text(
                text = ui.updated,
                maxLines = 1,
                style = TextStyle(color = c.muted, fontSize = 10.sp),
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())

        val day = if (showTomorrow) ui.days.getOrNull(1) else null
        if (day != null) {
            // Tomorrow: every period, as many as fit.
            val lineSize = (timeSize * 0.8f).coerceAtLeast(12f)
            val room = ((h * FIT - pad.value * 2 - headerHeight - 8f) / (lineSize * 1.3f)).toInt()
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
                Spacer(modifier = GlanceModifier.height(4.dp))
                LineBlock(it, timeSize * 0.85f, captionSize, showNextCaption, c)
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
 * 4x2 and bigger: the periods of the day and the day as 48 pills, using the whole width. When there is
 * room for both days they are stacked; otherwise a switch in the corner flips to tomorrow, and when the
 * list does not fit it starts at the current period.
 */
@Composable
private fun WideContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val pad = 8f
    val inner = size.width.value - pad * 2
    // The reported height is the least the widget has, so heights are fitted to it exactly: nothing is cut off.
    val h = size.height.value
    val scale = ui.textScale.coerceIn(0.8f, 1.5f)
    val labelWidth = 40f

    val today = ui.days.getOrNull(0)
    val tomorrow = ui.days.getOrNull(1)

    // The periods are set in equal columns that fill the width; more columns on wider widgets.
    val maxPerRow = if (inner > 330f) 4 else 3
    fun perRowOf(day: DayView?) = minOf(maxPerRow, day?.lines?.size ?: 1).coerceAtLeast(1)
    fun fontOf(perRow: Int, cap: Float) =
        (((inner * FIT / perRow) - 8f) / 5.4f).coerceIn(11f, cap) * scale
    fun rowsOf(day: DayView?, perRow: Int) = if (day == null) 0 else (day.lines.size + perRow - 1) / perRow

    val headerH = 28f
    val gap = 4f
    val pillLarge = 22f
    val pillSmall = 16f
    fun graphH(pill: Float) = pill + 12f + gap

    // Both days at once, with a big current period, when the height allows.
    val bigSize = ((inner * FIT * 0.5f) / 5.1f).coerceIn(22f, 36f) * scale
    val bigH = bigSize * 1.25f
    val fBoth = fontOf(perRowOf(today), 20f)
    val needBoth = pad * 2 + headerH + bigH + 3 * gap +
        rowsOf(today, perRowOf(today)) * fBoth * 1.4f + graphH(pillLarge) +
        rowsOf(tomorrow, perRowOf(tomorrow)) * fBoth * 1.4f + graphH(pillLarge)
    val both = tomorrow != null && h >= needBoth

    val oneDay = if (ui.showTomorrow) tomorrow else today
    val thick = both || h >= 150f
    val pill = if (thick) pillLarge else pillSmall
    val headerGap = 2 * gap + graphH(pill)

    // One day: the periods get the largest font at which they fill the width and the height that is left,
    // in 1..4 columns, whichever arrangement allows the biggest text.
    data class Fit(val perRow: Int, val font: Float)
    fun bestFit(day: DayView?, availableHeight: Float): Fit {
        val n = day?.lines?.size ?: 1
        var best = Fit(1, 11f)
        for (perRow in 1..minOf(maxPerRow, n).coerceAtLeast(1)) {
            val rows = (n + perRow - 1) / perRow
            val byWidth = ((inner * FIT / perRow) - 8f) / 5.4f
            val byHeight = availableHeight / (rows * 1.45f)
            val font = minOf(byWidth, byHeight, 40f)
            if (font > best.font) best = Fit(perRow, font)
        }
        return Fit(best.perRow, if (scale < 1f) best.font * scale else best.font)
    }
    val availablePlain = h - (pad * 2 + headerH + 13f + headerGap)
    val availableBig = availablePlain - (bigH - 13f)
    val showBig = both || (!ui.showTomorrow && bestFit(oneDay, availableBig).font >= 18f)
    val fit = bestFit(oneDay, if (showBig) availableBig else availablePlain)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(22.dp)
            .padding(pad.dp)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        Header(ui, c, if (h >= 150f) 19f else 17f, showIcon = true, showSwitch = !both)
        if (showBig) {
            val cur = ui.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cur != null) {
                    Text(
                        text = cur.from + "–" + cur.to,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (cur.on) c.on else c.off,
                            fontSize = bigSize.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                } else if (ui.message != null) {
                    Text(
                        text = ui.message,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (ui.messageOn) c.on else c.muted,
                            fontSize = (bigSize * 0.7f).sp,
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

        if (!both) {
            val day = oneDay
            // The periods fill what is left between the header and the graph, centred in it.
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (day != null) PeriodGrid(day, fit.perRow, fit.font, 8, c)
            }
            if (day != null) GraphRow(day, labelWidth, c, pill)
        } else {
            for (day in listOf(today, tomorrow)) {
                if (day != null) {
                    // One container per day keeps the root within Glance's limit of 10 children.
                    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = gap.dp)) {
                        PeriodGrid(day, perRowOf(day), fBoth, 4, c)
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        GraphRow(day, labelWidth, c, pillLarge)
                    }
                }
            }
        }
    }
}

/**
 * The periods of a day in rows of equal columns that fill the width; the one that is on now is larger.
 * When [maxRows] cannot show them all, today's list starts at the current period.
 */
@Composable
private fun PeriodGrid(day: DayView, perRow: Int, fontSize: Float, maxRows: Int, c: Colors) {
    val all = day.lines.withIndex().toList()
    val capacity = perRow * maxRows.coerceIn(1, 8)
    val shown = if (all.size > capacity && day.currentIndex > 0) all.drop(day.currentIndex) else all
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        for (row in shown.take(capacity).chunked(perRow)) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                for ((index, line) in row) {
                    val isCurrent = index == day.currentIndex
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = line.from + "–" + line.to,
                            maxLines = 1,
                            style = TextStyle(
                                color = if (line.on) c.on else c.off,
                                fontSize = (if (isCurrent) fontSize else fontSize * 0.9f).sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            ),
                        )
                    }
                }
                // Keep the columns aligned in a short last row.
                for (k in row.size until perRow) Spacer(modifier = GlanceModifier.defaultWeight())
            }
        }
    }
}

/** A day label on the left and the 48 pills beside it, across the full width; hours 0, 6, 12, 18 below. */
@Composable
private fun GraphRow(day: DayView, labelWidth: Float, c: Colors, pillHeight: Float) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = GlanceModifier.width(labelWidth.dp)) {
                Text(text = day.label, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
            }
            PillRow(day, pillHeight, c, GlanceModifier.defaultWeight())
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Spacer(modifier = GlanceModifier.width(labelWidth.dp))
            Row(modifier = GlanceModifier.defaultWeight()) {
                for (label in listOf("0", "6", "12", "18")) {
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = label, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
                    }
                }
            }
        }
    }
}

/**
 * 48 rounded pills: orange = power on for those 30 minutes, gray = off; "now" is drawn in the text color.
 * Every pill takes an equal share of whatever width the widget really has.
 */
@Composable
private fun PillRow(day: DayView, height: Float, c: Colors, modifier: GlanceModifier) {
    Row(modifier = modifier) {
        for (group in 0 until 6) {
            Row(modifier = GlanceModifier.defaultWeight()) {
                for (k in 0 until 8) {
                    val i = group * 8 + k
                    val color = when {
                        day.nowSlot == i -> c.text
                        day.slots[i] -> c.on
                        else -> c.pillOff
                    }
                    Box(modifier = GlanceModifier.defaultWeight().height(height.dp).padding(horizontal = 0.6.dp)) {
                        Spacer(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(color)
                                .cornerRadius(3.dp),
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
