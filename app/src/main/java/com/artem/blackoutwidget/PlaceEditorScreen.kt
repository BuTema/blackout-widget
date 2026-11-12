package com.artem.blackoutwidget

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Add or change one place: its name, how its queue is found (address or queue), and whether the
 * notifications apply to it. A [placeId] that is not stored yet means a new place.
 */
@Composable
fun PlaceEditorScreen(
    placeId: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(placeId) { Prefs.place(ctx, placeId) }
    val initial = existing ?: Place(id = placeId)

    var name by rememberSaveable(placeId) { mutableStateOf(initial.name) }
    var byGroup by rememberSaveable(placeId) { mutableStateOf(initial.byGroup) }
    var group by rememberSaveable(placeId) { mutableStateOf(initial.manualGroup) }
    var notify by rememberSaveable(placeId) { mutableStateOf(initial.notify) }
    var useNeighbor by rememberSaveable(placeId) { mutableStateOf(initial.neighborGroup.isNotBlank()) }
    var regionId by rememberSaveable(placeId) { mutableStateOf(initial.regionId) }
    val region = DtekRegion.byId(regionId)
    // Switching region starts the address over: streets and queues belong to one DTEK network.
    val sameRegion = regionId == initial.regionId
    var addressCheck by remember(placeId) { mutableStateOf<AddressCheck?>(null) }
    var detected by remember(placeId) { mutableStateOf(initial.detectedGroup) }
    var stored by remember(placeId) { mutableStateOf(existing != null) }
    var status by remember(placeId) { mutableStateOf(Prefs.loadStatus(ctx, placeId)) }
    var cache by remember(placeId) { mutableStateOf(Prefs.loadCache(ctx, placeId)) }
    var busy by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    // One hidden DTEK page shared by all address lookups while this screen is open.
    val session = remember(regionId) { DtekSession(ctx, region) }
    DisposableEffect(session) { onDispose { session.close() } }

    /** The place as it will be stored: DTEK's spelling when the address was checked, the old one otherwise. */
    fun current(): Place {
        val c = addressCheck
        val neighborOk = useNeighbor && c?.suggestedGroup != null
        val checked = if (!byGroup && c != null && (c.canSave || neighborOk)) c else null
        // The neighbours' queue goes with an address DTEK has no queue for; an own queue always wins.
        val neighbor = when {
            byGroup || !useNeighbor -> ""
            c?.state == AddressState.FOUND -> ""
            else -> c?.suggestedGroup ?: initial.neighborGroup
        }
        return initial.copy(
            neighborGroup = neighbor,
            name = name.trim(),
            regionId = regionId,
            city = checked?.city ?: if (sameRegion) initial.city else region.defaultCity,
            street = checked?.street ?: if (sameRegion) initial.street else "",
            house = checked?.house ?: if (sameRegion) initial.house else "",
            byGroup = byGroup,
            manualGroup = group,
            detectedGroup = if (checked != null) checked.group.orEmpty() else if (sameRegion) detected else "",
            notify = notify,
        )
    }

    // While a refresh runs, poll the stored result so the status line updates by itself.
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (true) {
            delay(1000)
            val s = Prefs.loadStatus(ctx, placeId)
            if (s.lastAttemptMillis >= startedAt) {
                status = s
                cache = Prefs.loadCache(ctx, placeId)
                detected = Prefs.place(ctx, placeId)?.detectedGroup ?: detected
                busy = false
                break
            }
        }
    }

    val canSave = !busy && if (byGroup) {
        GroupUtil.normalizeGroup(group) != null
    } else {
        addressCheck?.canSave == true || (useNeighbor && addressCheck?.suggestedGroup != null)
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
        Text(
            stringResource(if (stored) R.string.settings_place_card else R.string.place_new),
            style = MaterialTheme.typography.headlineMedium,
        )

        SectionCard(stringResource(R.string.settings_place_card)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.field_name)) },
                placeholder = { Text(stringResource(R.string.default_home_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(R.string.place_notify), notify) { notify = it }
        }

        SectionCard(stringResource(R.string.region_title)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (r in DtekRegion.entries) {
                    FilterChip(
                        selected = r == region,
                        onClick = {
                            regionId = r.id
                            addressCheck = null
                        },
                        label = { Text(regionName(r)) },
                    )
                }
            }
        }

        SectionCard(stringResource(R.string.settings_source)) {
            RadioRow(
                title = stringResource(R.string.source_address),
                description = stringResource(R.string.source_address_desc),
                selected = !byGroup,
                onSelect = { byGroup = false },
            )
            RadioRow(
                title = stringResource(R.string.source_group),
                description = stringResource(R.string.source_group_desc),
                selected = byGroup,
                onSelect = { byGroup = true },
            )
            Spacer(Modifier.padding(top = 4.dp))
            if (byGroup) {
                GroupPicker(selected = group, onSelect = { group = it })
            } else {
                key(regionId) {
                    AddressPicker(
                        session = session,
                        region = region,
                        initialCity = if (sameRegion) initial.city else region.defaultCity,
                        initialStreet = if (sameRegion) initial.street else "",
                        initialHouse = if (sameRegion) initial.house else "",
                        initialGroup = if (sameRegion) initial.detectedGroup else "",
                        onCheck = { addressCheck = it },
                        useNeighbor = useNeighbor,
                        onUseNeighbor = { useNeighbor = it },
                        onUseGroup = { group = it; byGroup = true },
                    )
                }
            }

            Button(
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    val saved = current()
                    Prefs.savePlace(ctx, saved)
                    stored = true
                    detected = saved.detectedGroup
                    onSaved(saved.id)
                    Scheduler.ensure(ctx)
                    busy = true
                    scope.launch { Refresher.refresh(ctx) }
                },
            ) {
                Text(stringResource(if (busy) R.string.refreshing else R.string.save_and_refresh))
            }
            if (!canSave && !busy) {
                Text(
                    stringResource(R.string.save_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val shownGroup = if (byGroup) {
                GroupUtil.normalizeGroup(group)
            } else {
                addressCheck?.group ?: detected.ifBlank { null }
            }
            Text(
                text = if (shownGroup != null) {
                    stringResource(R.string.group_in_use, shownGroup.removePrefix("GPV"))
                } else {
                    stringResource(R.string.group_unknown)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (stored) {
                val c = cache
                val lastData = if (c == null) {
                    stringResource(R.string.never_updated)
                } else {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(c.updatedAtMillis)) +
                        if (c.dtekUpdate.isNotBlank()) "  (DTEK: ${c.dtekUpdate})" else ""
                }
                Text(stringResource(R.string.last_data, lastData), style = MaterialTheme.typography.bodyMedium)
                status.lastError?.let {
                    Text(
                        stringResource(R.string.last_error, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (stored) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // The widget that is added next shows this place, without asking.
                    Prefs.setPendingWidgetPlace(ctx, placeId)
                    requestPinWidget(ctx)
                },
            ) {
                Text(stringResource(R.string.widget_for_place))
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { askDelete = true },
            ) {
                Text(stringResource(R.string.delete_place), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    if (askDelete) {
        val title = name.trim().ifBlank { stringResource(R.string.default_home_name) }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title, title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askDelete = false
                        Prefs.deletePlace(ctx, placeId)
                        Reminders.plan(ctx)
                        scope.launch { ScheduleWidget().updateAll(ctx) }
                        onDeleted(placeId)
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The short name of a DTEK region, as shown on its chip. */
@Composable
internal fun regionName(region: DtekRegion): String = stringResource(
    when (region) {
        DtekRegion.ODESA -> R.string.region_odesa
        DtekRegion.KYIV_REGION -> R.string.region_kyiv_region
        DtekRegion.DNIPRO -> R.string.region_dnipro
        DtekRegion.KYIV -> R.string.region_kyiv
    },
)
