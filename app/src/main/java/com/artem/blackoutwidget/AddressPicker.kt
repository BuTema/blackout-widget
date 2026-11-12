package com.artem.blackoutwidget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val HOUSE_SUGGESTIONS = 12

/**
 * City, street and house fields that check themselves against DTEK's own lists and suggest matches as
 * the person types. The verdict is reported through [onCheck] whenever it changes.
 */
@Composable
fun AddressPicker(
    session: DtekSession,
    region: DtekRegion,
    initialCity: String,
    initialStreet: String,
    initialHouse: String,
    initialGroup: String,
    onCheck: (AddressCheck) -> Unit,
    useNeighbor: Boolean,
    onUseNeighbor: (Boolean) -> Unit,
    onUseGroup: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var city by remember { mutableStateOf(initialCity) }
    var street by remember { mutableStateOf(initialStreet) }
    var house by remember { mutableStateOf(initialHouse) }
    var directory by remember { mutableStateOf(AddressDirectory.cached(region)) }
    var directoryFailed by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<HouseLookup>(HouseLookup.Idle) }
    // DTEK is only contacted once the person touches a field, so opening settings stays quiet.
    var interacted by remember { mutableStateOf(false) }

    val untouched = city.trim() == initialCity.trim() &&
        street.trim() == initialStreet.trim() &&
        house.trim() == initialHouse.trim()
    val hasSaved = untouched && initialStreet.isNotBlank() && initialGroup.isNotBlank()
    // An address saved without a known queue has to be checked right away; a complete one is left alone.
    val needNetwork = interacted || !untouched || (initialStreet.isNotBlank() && initialGroup.isBlank())

    // The street list: the saved copy at once, a fresh one from DTEK when it is missing or old.
    LaunchedEffect(Unit) {
        if (directory == null) directory = AddressDirectory.load(ctx, region)
    }
    LaunchedEffect(needNetwork) {
        if (!needNetwork) return@LaunchedEffect
        if (directory != null && !AddressDirectory.isStale(ctx, region)) return@LaunchedEffect
        try {
            directory = AddressDirectory.refresh(ctx, region, session)
            directoryFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            directoryFailed = true
        }
    }
    val directoryReady = directory != null || directoryFailed

    // The house list of the street, once city and street are known.
    val dir = directory
    val target = AddressValidator.target(dir, city, street)
    LaunchedEffect(target, directoryReady, needNetwork) {
        if (target == null || !directoryReady || !needNetwork) return@LaunchedEffect
        val known = lookup
        if (known is HouseLookup.Loaded && known.city == target.first && known.street == target.second) {
            return@LaunchedEffect
        }
        lookup = HouseLookup.Loading
        delay(400)
        lookup = try {
            HouseLookup.Loaded(target.first, target.second, AddressApi.houses(session, target.first, target.second))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HouseLookup.Failed
        }
    }

    val live = AddressValidator.check(dir, lookup, city, street, house)
    val check = if (hasSaved) {
        AddressCheck(AddressState.SAVED, initialCity, initialStreet, initialHouse, initialGroup)
    } else {
        live
    }
    LaunchedEffect(check) { onCheck(check) }

    // Suggestions.
    val cityList = remember(dir) { dir?.keys?.toList().orEmpty() }
    val citySuggestions = if (city.isNotBlank()) Suggest.filter(cityList, city) else emptyList()
    val canonCity = dir?.keys?.let { Suggest.exact(it, city) }
    val streetSuggestions = if (dir != null && canonCity != null && street.isNotBlank()) {
        Suggest.filter(dir[canonCity].orEmpty(), street)
    } else {
        emptyList()
    }
    val loadedHouses = (lookup as? HouseLookup.Loaded)
        ?.takeIf { target != null && it.city == target.first && it.street == target.second }
        ?.houses
        .orEmpty()
    val houseSuggestions = Suggest.filter(loadedHouses.map { it.number }, house, HOUSE_SUGGESTIONS)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SuggestField(
            value = city,
            onValueChange = { city = it },
            label = stringResource(R.string.field_city),
            suggestions = citySuggestions,
            isError = check.state == AddressState.CITY_UNKNOWN,
            onFocus = { interacted = true },
        )
        SuggestField(
            value = street,
            onValueChange = { street = it },
            label = stringResource(R.string.field_street),
            suggestions = streetSuggestions,
            isError = check.state == AddressState.STREET_UNKNOWN,
            onFocus = { interacted = true },
        )
        SuggestField(
            value = house,
            onValueChange = { house = it },
            label = stringResource(R.string.field_house),
            suggestions = houseSuggestions,
            isError = check.state == AddressState.HOUSE_UNKNOWN || check.state == AddressState.NO_GROUP,
            onFocus = { interacted = true },
            imeAction = ImeAction.Done,
        )
        StatusLine(check, useNeighbor, onUseNeighbor, onUseGroup)
    }
}

@Composable
private fun StatusLine(check: AddressCheck, useNeighbor: Boolean, onUseNeighbor: (Boolean) -> Unit, onUseGroup: (String) -> Unit) {
    val group = check.group?.removePrefix("GPV").orEmpty()
    val error = MaterialTheme.colorScheme.error
    val good = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when (check.state) {
        AddressState.EMPTY -> stringResource(R.string.addr_hint) to muted
        AddressState.CHECKING -> stringResource(R.string.addr_checking) to muted
        AddressState.CITY_UNKNOWN -> stringResource(R.string.addr_city_not_found) to error
        AddressState.STREET_UNKNOWN -> stringResource(R.string.addr_street_not_found) to error
        AddressState.HOUSE_UNKNOWN -> stringResource(R.string.addr_house_not_found) to error
        AddressState.NO_GROUP -> stringResource(R.string.addr_no_group) to error
        AddressState.FOUND -> stringResource(R.string.addr_found, group) to good
        AddressState.EMERGENCY_NO_GROUP -> stringResource(R.string.addr_emergency_no_group) to muted
        AddressState.SAVED -> stringResource(R.string.addr_saved, group) to good
        AddressState.UNVERIFIED -> stringResource(R.string.addr_unverified) to muted
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (check.state == AddressState.CHECKING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
        }
        val suggested = check.suggestedGroup?.removePrefix("GPV")
        if (suggested != null) {
            Text(
                stringResource(R.string.addr_street_group, suggested),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onUseNeighbor(!useNeighbor) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = useNeighbor, onCheckedChange = onUseNeighbor)
                Text(stringResource(R.string.addr_use_neighbor, suggested), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onUseGroup(suggested) }) {
                Text(stringResource(R.string.addr_use_group, suggested))
            }
        }
    }
}

/** A text field with a list of matches right below it while it has focus. */
@Composable
private fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    isError: Boolean,
    onFocus: () -> Unit,
    imeAction: ImeAction = ImeAction.Next,
) {
    var focused by remember { mutableStateOf(false) }
    // Once the text equals a suggestion (it was tapped, or typed in full) the list has done its job.
    val showList = focused && suggestions.isNotEmpty() && suggestions.none { it == value }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                },
        )
        if (showList) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                suggestions.forEach { item ->
                    Text(
                        text = item,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onValueChange(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
