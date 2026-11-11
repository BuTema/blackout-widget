package com.artem.blackoutwidget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch

internal val ALL_GROUPS = (1..6).flatMap { major -> (1..2).map { minor -> "GPV$major.$minor" } }

/** App settings: notifications and the look of the widget. (Places live on the home screen.) */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { Prefs.load(ctx) }

    var darkAlpha by remember { mutableStateOf(initial.darkAlpha) }
    var lightAlpha by remember { mutableStateOf(initial.lightAlpha) }
    var textScale by remember { mutableStateOf(initial.textScale) }

    var notifyChanged by remember { mutableStateOf(initial.notifyChanged) }
    var notifyTomorrow by remember { mutableStateOf(initial.notifyTomorrow) }
    var lead60 by remember { mutableStateOf(initial.lead60) }
    var lead30 by remember { mutableStateOf(initial.lead30) }
    var lead15 by remember { mutableStateOf(initial.lead15) }
    var atChange by remember { mutableStateOf(initial.atChange) }
    var remindOff by remember { mutableStateOf(initial.remindOff) }
    var remindOn by remember { mutableStateOf(initial.remindOn) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    // Bumped when the permission dialog closes, so the "blocked" notice is re-evaluated.
    var permissionTick by remember { mutableIntStateOf(0) }

    fun current() = Settings(
        darkAlpha = darkAlpha,
        lightAlpha = lightAlpha,
        textScale = textScale,
        notifyChanged = notifyChanged,
        notifyTomorrow = notifyTomorrow,
        lead60 = lead60,
        lead30 = lead30,
        lead15 = lead15,
        atChange = atChange,
        remindOff = remindOff,
        remindOn = remindOn,
    )

    fun repaint() {
        scope.launch { ScheduleWidget().updateAll(ctx) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionTick++
    }

    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.canPost(ctx)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Stores the notification options at once (no save button) and re-arms the reminder alarm. */
    fun notifyOptionChanged(switchedOn: Boolean) {
        Prefs.saveNotify(ctx, current())
        Reminders.onSettingsChanged(ctx)
        // Notifications also need the background refresh to be running, widget or not.
        Scheduler.ensure(ctx)
        testMessage = null
        if (switchedOn) askNotificationPermission()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
        }
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)

        // ---- Notifications ----
        SectionCard(stringResource(R.string.notifications)) {
            SwitchRow(stringResource(R.string.notify_changed), notifyChanged) {
                notifyChanged = it
                notifyOptionChanged(it)
            }
            SwitchRow(stringResource(R.string.notify_tomorrow), notifyTomorrow) {
                notifyTomorrow = it
                notifyOptionChanged(it)
            }

            Text(
                stringResource(R.string.reminders_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            CheckRow(stringResource(R.string.lead_60), lead60) {
                lead60 = it
                notifyOptionChanged(it)
            }
            CheckRow(stringResource(R.string.lead_30), lead30) {
                lead30 = it
                notifyOptionChanged(it)
            }
            CheckRow(stringResource(R.string.lead_15), lead15) {
                lead15 = it
                notifyOptionChanged(it)
            }
            CheckRow(stringResource(R.string.at_change), atChange) {
                atChange = it
                notifyOptionChanged(it)
            }
            SwitchRow(stringResource(R.string.remind_off), remindOff) {
                remindOff = it
                notifyOptionChanged(false)
            }
            SwitchRow(stringResource(R.string.remind_on), remindOn) {
                remindOn = it
                notifyOptionChanged(false)
            }

            // permissionTick is read so this is re-evaluated after the permission dialog closes.
            val blocked = permissionTick >= 0 && current().anyNotification && !Notifier.canPost(ctx)
            if (blocked) {
                Text(
                    stringResource(R.string.notif_blocked),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        // If the system will not ask again, this is the way to turn them back on.
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                        )
                    },
                ) {
                    Text(stringResource(R.string.notif_allow))
                }
            }

            val testSent = stringResource(R.string.notif_test_sent)
            val testBlocked = stringResource(R.string.notif_blocked)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { testMessage = if (Notifier.test(ctx)) testSent else testBlocked },
            ) {
                Text(stringResource(R.string.notif_test))
            }
            testMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        // ---- Widget ----
        SectionCard(stringResource(R.string.settings_widget_card)) {
            Text(stringResource(R.string.text_size, (textScale * 100).toInt()))
            Slider(
                value = textScale,
                onValueChange = { textScale = it },
                onValueChangeFinished = {
                    Prefs.saveAppearance(ctx, darkAlpha, lightAlpha, textScale)
                    repaint()
                },
                valueRange = 0.6f..1.6f,
            )
            Text(stringResource(R.string.transparency), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.transparency_dark, (darkAlpha * 100).toInt()))
            Slider(
                value = darkAlpha,
                onValueChange = { darkAlpha = it },
                onValueChangeFinished = {
                    Prefs.saveAppearance(ctx, darkAlpha, lightAlpha, textScale)
                    repaint()
                },
                valueRange = 0f..1f,
            )
            Text(stringResource(R.string.transparency_light, (lightAlpha * 100).toInt()))
            Slider(
                value = lightAlpha,
                onValueChange = { lightAlpha = it },
                onValueChangeFinished = {
                    Prefs.saveAppearance(ctx, darkAlpha, lightAlpha, textScale)
                    repaint()
                },
                valueRange = 0f..1f,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    Prefs.setPendingWidgetPlace(ctx, null)
                    requestPinWidget(ctx)
                },
            ) {
                Text(stringResource(R.string.add_widget))
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

/** Second line of a place in lists: its street and house, or its queue. */
@Composable
internal fun placeSubtitle(place: Place): String {
    val group = place.knownGroup?.removePrefix("GPV")
    return when {
        place.byGroup && group != null -> stringResource(R.string.group_in_use, group)
        !place.byGroup && place.street.isNotBlank() -> {
            val address = place.street + if (place.house.isNotBlank()) ", " + place.house else ""
            if (group != null) address + " · " + group else address
        }
        else -> stringResource(R.string.place_not_configured)
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
internal fun RadioRow(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole row is the click target, so the button itself takes no clicks.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The twelve queues in rows of four. */
@Composable
internal fun GroupPicker(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pick_group), style = MaterialTheme.typography.titleSmall)
        ALL_GROUPS.chunked(4).forEach { rowGroups ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowGroups.forEach { id ->
                    FilterChip(
                        selected = selected == id,
                        onClick = { onSelect(id) },
                        label = { Text(id.removePrefix("GPV")) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

internal fun requestPinWidget(ctx: Context) {
    val manager = AppWidgetManager.getInstance(ctx)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(ctx, ScheduleWidgetReceiver::class.java), null, null)
    }
}
