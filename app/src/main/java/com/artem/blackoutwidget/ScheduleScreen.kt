package com.artem.blackoutwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayTextStyle
import java.util.Locale

// Orange = power on, gray = off, as on the widget. White hour numbers read on both.
private val CellOn = Color(0xFFE88F00)
private val CellOff = Color(0xFF6F6F75)
private val CellUrgent = Color(0xFFD84B3F)

private fun textOn(dark: Boolean) = AppColors.on(dark)
private fun textOff(dark: Boolean) = AppColors.off(dark)
private fun textUrgent(dark: Boolean) = AppColors.emergency(dark)

/** One place in detail: today / tomorrow, the 24-hour grid (two rows of 12) and the list of periods. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(placeId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    var settings by remember { mutableStateOf(Prefs.load(ctx)) }
    var places by remember { mutableStateOf(Prefs.places(ctx)) }
    // The place that is shown: the chosen one, or the first if that one is gone.
    val place = places.firstOrNull { it.id == placeId } ?: places.firstOrNull()
    val shownId = place?.id
    var cache by remember(shownId) { mutableStateOf(shownId?.let { Prefs.loadCache(ctx, it) }) }
    var status by remember(shownId) {
        mutableStateOf(shownId?.let { Prefs.loadStatus(ctx, it) } ?: Status(0L, null))
    }
    var now by remember { mutableStateOf(LocalDateTime.now(ScheduleLogic.ZONE)) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    // Tick every second for the countdown; re-read stored data every 10 s so background updates show up.
    LaunchedEffect(shownId) {
        var n = 0
        while (true) {
            now = LocalDateTime.now(ScheduleLogic.ZONE)
            if (n % 10 == 0) {
                settings = Prefs.load(ctx)
                places = Prefs.places(ctx)
                if (shownId != null) {
                    cache = Prefs.loadCache(ctx, shownId)
                    status = Prefs.loadStatus(ctx, shownId)
                }
            }
            n++
            delay(1000)
        }
    }

    val days = cache?.days ?: emptyMap()
    val today = now.toLocalDate()
    val date = if (tab == 0) today else today.plusDays(1)
    val hours = days[date]
    val widgetState = ScheduleLogic.state(days, now)

    val title = (place?.name ?: "").ifBlank { stringResource(R.string.default_home_name) }
    val untilOnText = stringResource(R.string.until_on)
    val untilOffText = stringResource(R.string.until_off)

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                Refresher.refresh(ctx)
                settings = Prefs.load(ctx)
                if (shownId != null) {
                    cache = Prefs.loadCache(ctx, shownId)
                    status = Prefs.loadStatus(ctx, shownId)
                }
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (widgetState is WidgetState.Periods && widgetState.next != null) {
                    val secs = Duration.between(now, widgetState.current.end).seconds.coerceAtLeast(0)
                    val timer = String.format(Locale.ROOT, "%02d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (widgetState.current.on) CellOn else CellOff),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(timer, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (widgetState.current.on) untilOffText else untilOnText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (cache == null) {
            Text(stringResource(R.string.empty_hint), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.edit))
            }
        } else {
            val emergency = cache?.emergency?.takeIf { it.endMillis > System.currentTimeMillis() }
            if (emergency != null) EmergencyNotice(emergency, dark, noGroup = cache?.group.isNullOrBlank())
            DaySwitch(tab = tab, today = today, onSelect = { tab = it })

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hours == null) {
                        Text(stringResource(R.string.no_schedule_for_day), style = MaterialTheme.typography.bodyLarge)
                        if (tab == 1 && settings.notifyTomorrow) {
                            Text(stringResource(R.string.tomorrow_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val slots = ScheduleLogic.slotsOf(hours)
                        val currentSlot = if (tab == 0) now.hour * 2 + now.minute / 30 else -1
                        HourGrid(slots, ScheduleLogic.emergencySlotsOf(hours), currentSlot)

                        val group = (cache?.group ?: "").removePrefix("GPV")
                        if (group.isNotBlank()) {
                            Text(
                                stringResource(if (cache?.approxGroup == true) R.string.group_approx else R.string.group_in_use, group),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Column {
                            for (p in ScheduleLogic.periods(mapOf(date to hours), splitEmergency = true)) {
                                PeriodRow(
                                    period = p,
                                    dark = dark,
                                    current = tab == 0 && !now.isBefore(p.start) && now.isBefore(p.end),
                                    past = tab == 0 && !p.end.isAfter(now),
                                )
                            }
                        }
                    }
                }
            }

            Text(
                UpdatedText.format(ctx, cache?.updatedAtMillis, status.lastError != null),
                style = MaterialTheme.typography.bodySmall,
            )
            status.lastError?.let {
                Text(
                    stringResource(R.string.last_error, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    }
}

/** Shown while DTEK lists an emergency outage for the address: until when, and why. */
@Composable
private fun EmergencyNotice(e: Emergency, dark: Boolean, noGroup: Boolean) {
    val color = textUrgent(dark)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.emergency_title) + " · " + stringResource(R.string.emergency_until, emergencyEnd(e)),
                color = color,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            if (e.reason.isNotBlank()) {
                Text(stringResource(R.string.emergency_reason, e.reason), style = MaterialTheme.typography.bodyMedium)
            }
            if (noGroup) {
                Text(
                    stringResource(R.string.emergency_no_group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Two big buttons, each with the day and its date, so it is always clear which day is on screen. */
@Composable
private fun DaySwitch(tab: Int, today: LocalDate, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        DayTab(stringResource(R.string.tab_today), shortDate(today), tab == 0, Modifier.weight(1f)) { onSelect(0) }
        DayTab(stringResource(R.string.tab_tomorrow), shortDate(today.plusDays(1)), tab == 1, Modifier.weight(1f)) {
            onSelect(1)
        }
    }
}

@Composable
private fun DayTab(title: String, date: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(date, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun shortDate(date: LocalDate): String {
    val day = date.dayOfWeek.getDisplayName(DayTextStyle.SHORT, Locale.getDefault())
    return day + ", " + date.format(DateTimeFormatter.ofPattern("dd.MM"))
}

/** 24 hours as two rows of 12 cells; each cell is split in halves, so half-hour outages are visible. */
@Composable
private fun HourGrid(slots: BooleanArray, urgent: BooleanArray, currentSlot: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 12) {
                    val hour = row * 12 + col
                    HourCell(
                        hour = hour,
                        firstHalf = cellColor(slots[hour * 2], urgent[hour * 2]),
                        secondHalf = cellColor(slots[hour * 2 + 1], urgent[hour * 2 + 1]),
                        isNow = currentSlot / 2 == hour,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun cellColor(on: Boolean, urgent: Boolean) = if (urgent) CellUrgent else if (on) CellOn else CellOff

@Composable
private fun HourCell(
    hour: Int,
    firstHalf: Color,
    secondHalf: Color,
    isNow: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val frame = if (isNow) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier
    Box(
        modifier = modifier
            .height(46.dp)
            .then(frame)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(firstHalf),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(secondHalf),
            )
        }
        Text(hour.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeriodRow(period: Period, dark: Boolean, current: Boolean, past: Boolean) {
    val color = if (period.emergency) textUrgent(dark) else if (period.on) textOn(dark) else textOff(dark)
    val minutes = Duration.between(period.start, period.end).toMinutes()
    val highlight = if (current) {
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(highlight)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (past) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ScheduleLogic.hm(period.start) + " - " + ScheduleLogic.hm(period.end),
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.duration_fmt, minutes / 60, minutes % 60),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
