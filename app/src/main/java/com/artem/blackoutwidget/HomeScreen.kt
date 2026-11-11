package com.artem.blackoutwidget

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private class PlaceRow(val place: Place, val cache: Cache?, val status: Status)

private fun loadRows(ctx: android.content.Context): List<PlaceRow> =
    Prefs.places(ctx).map { PlaceRow(it, Prefs.loadCache(ctx, it.id), Prefs.loadStatus(ctx, it.id)) }

/**
 * The home screen: every place as a card (current period, next one, the day as 48 pills). Press and
 * hold a card to pick it up, drag it over the others (they slide out of the way) and let go to drop it
 * there; tap it for the full schedule; "+" adds a place.
 */
@Composable
fun HomeScreen(onOpenPlace: (String) -> Unit, onAddPlace: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val spacingPx = with(LocalDensity.current) { 12.dp.toPx() }

    var rows by remember { mutableStateOf(loadRows(ctx)) }
    var order by remember { mutableStateOf(rows.map { it.place.id }) }
    var now by remember { mutableStateOf(LocalDateTime.now(ScheduleLogic.ZONE)) }
    var refreshing by remember { mutableStateOf(false) }
    val heights = remember { mutableStateMapOf<String, Float>() }

    // The card in the hand, how far the finger has moved it, and where it is sliding to after release.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleTo by remember { mutableStateOf<Int?>(null) }

    // Re-read the stored data every few seconds so background updates show up; never mid-drag.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            now = LocalDateTime.now(ScheduleLogic.ZONE)
            if (draggingId == null && n % 5 == 0) {
                rows = loadRows(ctx)
                order = rows.map { it.place.id }
            }
            n++
            delay(1000)
        }
    }

    fun heightOf(index: Int) = heights[order[index]] ?: 0f

    // The position the card in the hand would take if it were dropped now: it counts a neighbour as
    // passed once the edge of the card in the hand is beyond the neighbour's middle.
    fun targetFor(start: Int, offset: Float): Int {
        var target = start
        if (offset > 0f) {
            val edge = offset + heightOf(start)
            var top = heightOf(start) + spacingPx
            while (target < order.lastIndex && edge > top + heightOf(target + 1) / 2f) {
                top += heightOf(target + 1) + spacingPx
                target++
            }
        } else if (offset < 0f) {
            var above = 0f
            while (target > 0) {
                above += heightOf(target - 1) + spacingPx
                if (offset < -(above - heightOf(target - 1) / 2f)) target-- else break
            }
        }
        return target
    }

    /** How far the card must travel for the card in the hand to end up at [target]. */
    fun distanceTo(start: Int, target: Int): Float {
        var sum = 0f
        if (target > start) for (i in start + 1..target) sum += heightOf(i) + spacingPx
        if (target < start) for (i in target until start) sum -= heightOf(i) + spacingPx
        return sum
    }

    fun drop() {
        val id = draggingId ?: return
        val start = order.indexOf(id)
        if (start < 0 || settleTo != null) return
        val target = targetFor(start, dragOffset)
        settleTo = target
        scope.launch {
            animate(dragOffset, distanceTo(start, target), animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                dragOffset = value
            }
            // Order, offsets and "in the hand" change together, so nothing jumps.
            val moved = order.toMutableList().also { it.add(target, it.removeAt(start)) }
            order = moved
            Prefs.reorderPlaces(ctx, moved)
            draggingId = null
            dragOffset = 0f
            settleTo = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !refreshing,
                    onClick = {
                        refreshing = true
                        scope.launch {
                            Refresher.refresh(ctx)
                            rows = loadRows(ctx)
                            order = rows.map { it.place.id }
                            refreshing = false
                        }
                    },
                ) {
                    Text(stringResource(if (refreshing) R.string.refreshing else R.string.refresh))
                }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
            }

            if (rows.isEmpty()) {
                Text(stringResource(R.string.places_empty), style = MaterialTheme.typography.bodyLarge)
            } else if (rows.size > 1) {
                Text(
                    stringResource(R.string.home_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val byId = rows.associateBy { it.place.id }
            val start = draggingId?.let { order.indexOf(it) } ?: -1
            val target = if (start >= 0) settleTo ?: targetFor(start, dragOffset) else -1
            val handHeight = if (start >= 0) heightOf(start) + spacingPx else 0f

            for ((index, id) in order.withIndex()) {
                val row = byId[id] ?: continue
                key(id) {
                    val dragged = draggingId == id
                    // The cards between the old and the new place of the dragged one slide out of its way.
                    val shiftTarget = when {
                        start < 0 || dragged -> 0f
                        index in start + 1..target -> -handHeight
                        index in target until start -> handHeight
                        else -> 0f
                    }
                    val slide by animateFloatAsState(shiftTarget, spring(stiffness = Spring.StiffnessMedium), label = "slide")
                    val lift by animateFloatAsState(if (dragged) 1.04f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "lift")
                    PlaceCard(
                        row = row,
                        now = now,
                        dragged = dragged,
                        modifier = Modifier
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragged) dragOffset else if (start >= 0) slide else 0f
                                scaleX = lift
                                scaleY = lift
                            }
                            .onSizeChanged { heights[id] = it.height.toFloat() }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        if (draggingId == null) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragOffset = 0f
                                            draggingId = id
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (draggingId == id && settleTo == null) dragOffset += amount.y
                                    },
                                    onDragEnd = { if (draggingId == id) drop() },
                                    onDragCancel = { if (draggingId == id) drop() },
                                )
                            }
                            .clickable { onOpenPlace(id) },
                    )
                }
            }
            // Room so the last card can be scrolled clear of the "+" button.
            Spacer(Modifier.height(88.dp))
        }

        FloatingActionButton(
            onClick = onAddPlace,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Text("+", fontSize = 30.sp)
        }
    }
}

@Composable
private fun PlaceCard(row: PlaceRow, now: LocalDateTime, dragged: Boolean, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val on = AppColors.on(dark)
    val off = AppColors.off(dark)
    val place = row.place
    val cache = row.cache
    val state = if (cache == null) WidgetState.NoData else ScheduleLogic.state(cache.days, now)
    val name = place.name.ifBlank { stringResource(R.string.default_home_name) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragged) 16.dp else 1.dp),
        // Picked up: a lighter card with a clear outline.
        colors = CardDefaults.cardColors(
            containerColor = if (dragged) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        border = if (dragged) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                val group = (cache?.group ?: place.knownGroup ?: "").removePrefix("GPV")
                if (group.isNotBlank()) {
                    Text(
                        stringResource(R.string.group_in_use, group),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                UpdatedText.format(LocalContext.current, cache?.updatedAtMillis, row.status.lastError != null),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                WidgetState.NoData -> Text(
                    stringResource(if (place.configured) R.string.no_data else R.string.place_not_configured),
                    style = MaterialTheme.typography.bodyLarge,
                )
                WidgetState.AllDayOn -> Text(
                    stringResource(R.string.home_all_day_on),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = on,
                )
                is WidgetState.Periods -> {
                    val cur = state.current
                    Text(
                        ScheduleLogic.hm(cur.start) + "–" + ScheduleLogic.hm(cur.end),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (cur.on) on else off,
                    )
                    val next = state.next
                    if (next != null) {
                        val text = ScheduleLogic.hm(next.start) + "–" + ScheduleLogic.hm(next.end)
                        Text(
                            stringResource(R.string.home_next, text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (next.on) on else off,
                        )
                    }
                }
            }

            val today = cache?.days?.get(now.toLocalDate())
            if (today != null) {
                Text(
                    stringResource(R.string.tab_today) + " · " + now.toLocalDate().format(DateTimeFormatter.ofPattern("dd.MM")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillGraph(ScheduleLogic.slotsOf(today), now.hour * 2 + now.minute / 30, on)
            }
        }
    }
}

/** The day as 48 rounded pills of 30 minutes: orange = power on, gray = off, white = now. */
@Composable
private fun PillGraph(slots: BooleanArray, nowSlot: Int, on: Color) {
    val off = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(16.dp)) {
            for (i in 0 until 48) {
                val color = when {
                    i == nowSlot -> nowColor
                    slots[i] -> on
                    else -> off
                }
                // Power on = full height, off = shorter; all sit on the bottom line.
                val full = slots[i] || i == nowSlot
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 0.7.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (full) 1f else 0.55f)
                            .clip(RoundedCornerShape(50))
                            .background(color),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (label in listOf("0", "6", "12", "18")) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
