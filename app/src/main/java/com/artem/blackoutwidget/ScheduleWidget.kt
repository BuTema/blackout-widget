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
private class Line(val from: String, val to: String, val caption: String, val on: Boolean, val emergency: Boolean = false)

/** One day: its periods, its 48 half-hour slots (true = power on) and, for today, the slot of "now". */
private class DayView(
    val label: String,
    val lines: List<Line>,
    val slots: BooleanArray,
    val nowSlot: Int?,
    /** Index in [lines] of the period that is on now, or -1. */
    val currentIndex: Int,
    /** Slots that are off because of an emergency outage. */
    val emergency: BooleanArray,
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
    /** The queue as "Queue: 6.2" and short ("6.2"); empty when unknown. */
    val group: String = "",
    val groupShort: String = "",
    /** "Next: 18:00–22:00", as on the cards of the app. */
    val nextText: String = "",
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

    // An emergency outage: the only colour besides orange and gray.
    val emergencyDark = Color(0xFFFF5A4F)
    val emergencyLight = Color(0xFFD32F2F)
}

private class Colors(
    val background: androidx.glance.unit.ColorProvider,
    val text: androidx.glance.unit.ColorProvider,
    val muted: androidx.glance.unit.ColorProvider,
    val on: androidx.glance.unit.ColorProvider,
    val off: androidx.glance.unit.ColorProvider,
    val pillOff: androidx.glance.unit.ColorProvider,
    val chip: androidx.glance.unit.ColorProvider,
    val emergency: androidx.glance.unit.ColorProvider,
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
    emergency = ColorProvider(day = Palette.emergencyLight, night = Palette.emergencyDark),
)

private fun Line.color(c: Colors) = when {
    emergency -> c.emergency
    on -> c.on
    else -> c.off
}

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
                current = Line(ScheduleLogic.hm(cur.start), ScheduleLogic.hm(cur.end), "", cur.on, cur.emergency)
                val nx = state.next
                if (nx != null) {
                    var caption = context.getString(if (nx.on) R.string.power_on_next else R.string.power_off_next)
                    if (nx.start.toLocalDate() != today) caption += context.getString(R.string.tomorrow_suffix)
                    next = Line(ScheduleLogic.hm(nx.start), ScheduleLogic.hm(nx.end), caption, nx.on, nx.emergency)
                }
            }
        }
        val showTomorrow = days.size > 1 && Prefs.isTomorrowShown(context, appWidgetId, today)
        val queue = (cache?.group ?: place.knownGroup ?: "").removePrefix("GPV")
        val approx = cache?.approxGroup == true
        val group = if (queue.isBlank()) "" else context.getString(if (approx) R.string.group_approx_short else R.string.group_in_use, queue)
        val groupShort = if (queue.isBlank()) "" else (if (approx) "≈" else "") + queue
        val nextText = next?.let { context.getString(R.string.home_next, it.from + "–" + it.to) } ?: ""
        return Ui(
            appWidgetId, place.id, name, updated, null, current, next, message, messageOn,
            days, showTomorrow, settings.darkAlpha, settings.lightAlpha, settings.textScale,
            group, groupShort, nextText,
        )
    }

    private fun slotTime(slot: Int): String = String.format(Locale.ROOT, "%02d:%02d", (slot / 2) % 24, slot % 2 * 30)

    private fun dayView(label: String, hours: Map<Int, String>, nowSlot: Int?): DayView {
        val slots = ScheduleLogic.slotsOf(hours)
        val urgent = ScheduleLogic.emergencySlotsOf(hours)
        // 0 = off by the schedule, 1 = on, 2 = off by an emergency outage; a period is a run of one kind.
        val kinds = IntArray(slots.size) { if (urgent[it]) 2 else if (slots[it]) 1 else 0 }
        val lines = ArrayList<Line>()
        var currentIndex = -1
        var start = 0
        while (start < kinds.size) {
            var end = start
            while (end < kinds.size && kinds[end] == kinds[start]) end++
            lines.add(Line(slotTime(start), slotTime(end), "", kinds[start] == 1, kinds[start] == 2))
            if (nowSlot != null && nowSlot >= start && nowSlot < end) currentIndex = lines.lastIndex
            start = end
        }
        return DayView(label, lines, slots, nowSlot, currentIndex, urgent)
    }
}

@Composable
private fun WidgetContent(ui: Ui) {
    val size = LocalSize.current
    when {
        ui.prompt != null -> PromptContent(ui)
        size.width < 100.dp -> TinyContent(ui)
        size.height < 100.dp -> StripContent(ui)
        else -> CardContent(ui)
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
                    color = line.color(c),
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
        val curStyle = { f: Float -> TextStyle(color = cur.color(c), fontSize = f.sp, fontWeight = FontWeight.Bold) }
        val nextStyle = { f: Float ->
            TextStyle(color = next?.color(c) ?: c.off, fontSize = f.sp, fontWeight = FontWeight.Bold)
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

/**
 * The size the launcher reports is the minimum of what the widget is given, and the real widget is
 * usually a little larger, so sizes are fitted to this much more than the reported size.
 */
private const val FIT = 1.15f

/**
 * The widget drawn like the place card of the app: name (and queue) on top, the "updated" line, the current
 * period large, the next one, and the day as pills with the hours below. Sizes only change how much of this
 * fits: the queue needs width, the "updated" line and the day switch need height or width, and in a wide
 * widget the next period sits beside the current one. Tomorrow lists its periods instead.
 */
@Composable
private fun CardContent(ui: Ui) {
    val size = LocalSize.current
    val c = colorsFor(ui)
    val w = size.width.value
    val h = size.height.value
    val wide = w >= 240f
    val pad = if (h < 120f) 8f else 12f
    val inner = w - pad * 2

    val today = ui.days.firstOrNull { it.nowSlot != null }
    val tomorrowDay = ui.days.firstOrNull { it.nowSlot == null }
    // Only widgets bigger than 2x2 carry the today/tomorrow switch.
    val canSwitch = (h >= 160f || w >= 170f) && today != null && tomorrowDay != null
    val showTomorrow = canSwitch && ui.showTomorrow
    val day = if (showTomorrow) tomorrowDay else today

    val nameSize = when {
        h >= 150f -> 20f
        h >= 110f -> 17f
        else -> 15f
    }
    val showUpdated = h >= 120f
    val chipOnUpdatedRow = canSwitch && showUpdated
    val chipOnTop = canSwitch && !showUpdated
    val group = when {
        w < 140f || chipOnTop -> null
        w < 200f -> ui.groupShort
        else -> ui.group
    }?.takeIf { it.isNotEmpty() }
    val showGraph = day != null && (w >= 140f || h >= 150f)
    val pill = if (h >= 150f) 20f else 16f

    val headerH = nameSize * 1.3f + (if (showUpdated) (if (chipOnUpdatedRow) 22f else 13f) else 0f)
    val graphH = if (showGraph) pill + 12f + 4f else 0f
    val free = h - pad * 2 - headerH - graphH - 6f

    val cur = ui.current
    val hasNext = cur != null && ui.next != null
    val nextBeside = wide && hasNext
    val nextBelowH = if (hasNext && !nextBeside) 13f * 1.3f + 2f else 0f
    val fitHeight = (free - nextBelowH) / 1.25f
    val fitWidth = (if (nextBeside) inner * 0.58f else inner) / 5.6f
    val bigSize = minOf(fitWidth * ui.textScale, maxOf(fitHeight, 12f)).coerceIn(12f, 34f)
    val nextSize = (bigSize * 0.5f).coerceIn(11f, 16f)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c.background)
            .cornerRadius(22.dp)
            .padding(pad.dp)
            .clickable(actionRunCallback<RefreshAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        CardHeader(ui, c, nameSize, group, chipOnTop)
        if (showUpdated) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ui.updated,
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = c.muted, fontSize = 10.sp),
                )
                if (chipOnUpdatedRow) DayChip(ui, c)
            }
        }
        Spacer(modifier = GlanceModifier.defaultWeight())

        if (showTomorrow && day != null) {
            val perRow = minOf(if (wide) 4 else if (w >= 170f) 2 else 1, day.lines.size).coerceAtLeast(1)
            val font = (((inner / perRow) - 8f) / 5.4f).coerceIn(11f, 20f)
            val rows = (free / (font * 1.4f)).toInt().coerceIn(1, 8)
            PeriodGrid(day, perRow, font, rows, c)
        } else if (cur == null) {
            if (ui.message != null) {
                Text(
                    text = ui.message,
                    style = TextStyle(
                        color = if (ui.messageOn) c.on else c.muted,
                        fontSize = (bigSize * 0.75f).coerceIn(13f, 22f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        } else if (nextBeside) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                BigPeriod(cur, bigSize, c)
                Spacer(modifier = GlanceModifier.defaultWeight())
                NextText(ui, nextSize, c)
            }
        } else {
            Column {
                BigPeriod(cur, bigSize, c)
                if (hasNext) NextText(ui, nextSize, c)
            }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())
        if (showGraph && day != null) {
            val hPad = (inner / 48f * 0.2f).coerceIn(0.2f, 0.7f)
            GraphRow(day, c, pill, hPad)
        }
    }
}

@Composable
private fun BigPeriod(line: Line, size: Float, c: Colors) {
    Text(
        text = line.from + "–" + line.to,
        maxLines = 1,
        style = TextStyle(color = line.color(c), fontSize = size.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun NextText(ui: Ui, size: Float, c: Colors) {
    val next = ui.next ?: return
    Text(
        text = ui.nextText,
        maxLines = 1,
        style = TextStyle(color = next.color(c), fontSize = size.sp),
    )
}

/** Name (opens the app on this place), then the queue, then the day switch when it is not on the next row. */
@Composable
private fun CardHeader(ui: Ui, c: Colors, nameSize: Float, group: String?, showChip: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = ui.name,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().clickable(openPlace(ui)),
            style = TextStyle(color = c.text, fontSize = nameSize.sp, fontWeight = FontWeight.Bold),
        )
        if (group != null) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(text = group, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 11.sp))
        }
        if (showChip) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            DayChip(ui, c)
        }
    }
}

/** "Tomorrow ›" / "‹ Today": flips the widget between the two days. */
@Composable
private fun DayChip(ui: Ui, c: Colors) {
    if (ui.days.size < 2) return
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
                                color = line.color(c),
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

/** The 48 pills across the full width with the hours 0, 6, 12, 18 below, as on the cards of the app. */
@Composable
private fun GraphRow(day: DayView, c: Colors, pillHeight: Float, hPad: Float) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        PillRow(day, pillHeight, c, GlanceModifier.fillMaxWidth(), hPad)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            for (label in listOf("0", "6", "12", "18")) {
                Box(modifier = GlanceModifier.defaultWeight()) {
                    Text(text = label, maxLines = 1, style = TextStyle(color = c.muted, fontSize = 9.sp))
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
private fun PillRow(day: DayView, height: Float, c: Colors, modifier: GlanceModifier, hPad: Float = 0.6f) {
    Row(modifier = modifier) {
        for (group in 0 until 6) {
            Row(modifier = GlanceModifier.defaultWeight()) {
                for (k in 0 until 8) {
                    val i = group * 8 + k
                    // Power on = the tallest (orange), no power = medium (gray), emergency = the smallest (red);
                    // the pill of "now" is white at the height of its state. All sit on the bottom line.
                    val urgent = day.emergency[i]
                    val color = when {
                        day.nowSlot == i -> c.text
                        urgent -> c.emergency
                        day.slots[i] -> c.on
                        else -> c.pillOff
                    }
                    val share = when {
                        urgent -> 0.35f
                        day.slots[i] -> 1f
                        else -> 0.65f
                    }
                    Box(
                        modifier = GlanceModifier.defaultWeight().height(height.dp).padding(horizontal = hPad.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Spacer(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height((height * share).dp)
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
